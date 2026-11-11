import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { buildExperimentRequest, createExperiment, createInitialValues } from '@/entities/experiment'
import type { ExperimentScreen } from '@/entities/experiment'
import { ApiError } from '@/shared/api'
import { routes } from '@/shared/config/routes'
import type { FieldErrors, FieldValue } from '@/shared/lib/bdui'
import { BduiField } from '@/shared/ui/bdui-field'
import { Button } from '@/shared/ui/button'
import { Panel } from '@/shared/ui/panel'
import styles from './experiment-config-form.module.css'

const sectionTitles = {
  settings: 'Настройки базы данных',
  params: 'Условия эксперимента',
  loaderImageContext: 'Ресурсы нагрузчика',
  databaseImageContext: 'Ресурсы базы данных',
}

function getServerFieldErrors(error: ApiError): FieldErrors {
  const body = error.body
  if (!body || typeof body !== 'object' || !('fieldErrors' in body) || !Array.isArray(body.fieldErrors)) {
    return {}
  }
  return Object.fromEntries(body.fieldErrors.flatMap(item =>
    item && typeof item.field === 'string' && typeof item.message === 'string'
      ? [[item.field, item.message]] : [],
  ))
}

export function ExperimentConfigForm({ screen }: { screen: ExperimentScreen }) {
  const navigate = useNavigate()
  const [values, setValues] = useState(() => createInitialValues(screen))
  const [errors, setErrors] = useState<FieldErrors>({})
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState<{ message: string; checkHistory?: boolean } | null>(null)
  const requestRef = useRef<AbortController | null>(null)
  const formRef = useRef<HTMLFormElement>(null)

  useEffect(() => () => requestRef.current?.abort(), [])

  function changeField(name: string, value: FieldValue) {
    setValues(current => ({ ...current, [name]: value }))
    setErrors(current => {
      const next = { ...current }
      delete next[name]
      return next
    })
    setFeedback(null)
  }

  function showErrors(fieldErrors: FieldErrors) {
    setErrors(fieldErrors)
    requestAnimationFrame(() => {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    })
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (requestRef.current && !requestRef.current.signal.aborted) return
    const { payload, errors: clientErrors } = buildExperimentRequest(screen, values)
    setFeedback(null)
    if (Object.keys(clientErrors).length) {
      showErrors(clientErrors)
      setFeedback({ message: 'Проверьте выделенные поля.' })
      return
    }

    const controller = new AbortController()
    requestRef.current?.abort()
    requestRef.current = controller
    setPending(true)
    setErrors({})

    try {
      const result = await createExperiment(payload, controller.signal)
      if (controller.signal.aborted) return
      navigate(routes.experiment(result.id))
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError) {
        const fieldErrors = getServerFieldErrors(error)
        const hasFieldErrors = Object.keys(fieldErrors).length > 0
        const fieldNames = new Set(screen.form.sections.flatMap(section => section.fields.map(field => field.name)))
        const generalErrors = Object.entries(fieldErrors)
          .filter(([name]) => !fieldNames.has(name))
          .map(([, message]) => message)
        let message = `Не удалось запустить эксперимент (HTTP ${error.status}).`
        if (hasFieldErrors) message = 'Сервер отклонил значения. Проверьте выделенные поля.'
        if (generalErrors.length > 0) message = generalErrors.join(' ')
        showErrors(fieldErrors)
        setFeedback({
          message,
          checkHistory: error.status >= 500,
        })
      } else {
        setFeedback({
          message: 'Не удалось получить подтверждение запуска. Проверьте историю перед повторной отправкой.',
          checkHistory: true,
        })
      }
    } finally {
      if (requestRef.current === controller) requestRef.current = null
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return (
    <form ref={formRef} className={styles.form} onSubmit={submit} noValidate>
      <div className={styles.sections}>
        {screen.form.sections.map(section => (
          <Panel key={section.id} aria-labelledby={`section-${section.id}`}>
            <h2 id={`section-${section.id}`} className={styles.sectionTitle}>
              {sectionTitles[section.id]}
            </h2>
            <fieldset className={styles.fields} disabled={pending}>
              <legend className={styles.srOnly}>{sectionTitles[section.id]}</legend>
              {section.fields.map(field => (
                <BduiField key={field.name} field={field} value={values[field.name]} error={errors[field.name]} disabled={pending} onChange={value => changeField(field.name, value)} />
              ))}
            </fieldset>
          </Panel>
        ))}
      </div>
      <Panel className={styles.submitPanel} aria-label="Запуск эксперимента">
        <h2 className={styles.submitTitle}>Запуск эксперимента</h2>
        <div className={styles.actions}>
          <Button type="button" variant="secondary" disabled={pending} onClick={() => {
            setValues(createInitialValues(screen))
            setErrors({})
            setFeedback(null)
          }}>Сбросить</Button>
          <Button type="submit" disabled={pending}>{pending ? 'Запускаем…' : 'Запустить эксперимент'}</Button>
        </div>
        {feedback && <p role="alert" className={styles.feedback}>
          {feedback.message}
          {feedback.checkHistory && <> <Link to={routes.experiments}>Открыть историю запусков</Link></>}
        </p>}
      </Panel>
    </form>
  )
}
