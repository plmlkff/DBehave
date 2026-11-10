import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { buildExperimentRequest, createInitialValues, validateExperiment } from '@/entities/experiment'
import type { ExperimentScreen } from '@/entities/experiment'
import { ApiError } from '@/shared/api'
import type { FieldErrors, FieldValue } from '@/shared/lib/bdui'
import { BduiField } from '@/shared/ui/bdui-field'
import { Button } from '@/shared/ui/button'
import { Panel } from '@/shared/ui/panel'
import styles from './experiment-config-form.module.css'

const sectionTitles = { settings: 'Настройки базы данных', params: 'Условия эксперимента' }

function getServerFieldErrors(error: ApiError): FieldErrors {
  const body = error.body
  if (!body || typeof body !== 'object' || !('fieldErrors' in body) || !Array.isArray(body.fieldErrors)) return {}
  return Object.fromEntries(body.fieldErrors.flatMap(item =>
    item && typeof item.field === 'string' && typeof item.message === 'string'
      ? [[item.field, item.message]] : [],
  ))
}

export function ExperimentConfigForm({ screen }: { screen: ExperimentScreen }) {
  const [values, setValues] = useState(() => createInitialValues(screen))
  const [errors, setErrors] = useState<FieldErrors>({})
  const [pending, setPending] = useState(false)
  const [feedback, setFeedback] = useState<{ kind: 'success' | 'error'; message: string } | null>(null)
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
    if (requestRef.current && !requestRef.current.signal.aborted && pending) return
    const { payload, errors: clientErrors } = buildExperimentRequest(screen, values)
    setFeedback(null)
    if (Object.keys(clientErrors).length) {
      showErrors(clientErrors)
      setFeedback({ kind: 'error', message: 'Проверьте выделенные поля.' })
      return
    }

    const controller = new AbortController()
    requestRef.current?.abort()
    requestRef.current = controller
    setPending(true)
    setErrors({})

    try {
      const result = await validateExperiment(payload, controller.signal)
      if (controller.signal.aborted) return
      if (result.status !== 'VALIDATED') throw new Error('Сервер не подтвердил конфигурацию.')
      setFeedback({
        kind: 'success',
        message: result.execution === 'NOT_IMPLEMENTED'
          ? 'Конфигурация проверена. Запуск экспериментов ещё не реализован на сервере.'
          : 'Конфигурация проверена.',
      })
    } catch (error) {
      if (controller.signal.aborted) return
      if (error instanceof ApiError) {
        const fieldErrors = getServerFieldErrors(error)
        showErrors(fieldErrors)
        setFeedback({ kind: 'error', message: Object.keys(fieldErrors).length
          ? 'Сервер отклонил значения. Проверьте выделенные поля.'
          : `Не удалось проверить конфигурацию (HTTP ${error.status}).` })
      } else {
        setFeedback({ kind: 'error', message: error instanceof TypeError
          ? 'Не удалось связаться с сервером. Попробуйте ещё раз.'
          : error instanceof Error ? error.message : 'Не удалось проверить конфигурацию.' })
      }
    } finally {
      if (!controller.signal.aborted) setPending(false)
    }
  }

  return (
    <form ref={formRef} className={styles.form} onSubmit={submit} noValidate>
      <div className={styles.sections}>
        {screen.form.sections.map((section, index) => (
          <Panel key={section.id} aria-labelledby={`section-${section.id}`}>
            <h2 id={`section-${section.id}`} className={styles.sectionTitle}>
              <span aria-hidden="true">{String(index + 2).padStart(2, '0')}</span>
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
      <Panel className={styles.submitPanel} aria-label="Проверка эксперимента">
        <div>
          <h2 className={styles.submitTitle}>Проверьте конфигурацию</h2>
          <p className={styles.description}>Проверка параметров перед запуском эксперимента.</p>
        </div>
        <div className={styles.actions}>
          <Button type="button" variant="secondary" disabled={pending} onClick={() => {
            setValues(createInitialValues(screen))
            setErrors({})
            setFeedback(null)
          }}>Сбросить</Button>
          <Button type="submit" disabled={pending}>{pending ? 'Проверяем…' : 'Проверить конфигурацию'}</Button>
        </div>
        {feedback && <p role={feedback.kind === 'error' ? 'alert' : 'status'} className={`${styles.feedback} ${styles[feedback.kind]}`}>{feedback.message}</p>}
      </Panel>
    </form>
  )
}
