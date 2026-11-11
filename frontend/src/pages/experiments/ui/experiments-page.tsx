import { useEffect, useRef, useState } from 'react'
import type { MouseEvent, SyntheticEvent } from 'react'
import { ArrowUpRight, History, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import {
  compareExperiments,
  deleteExperiments,
  experimentStateLabel,
  isExperimentFinished,
  useExperimentHistory,
} from '@/entities/experiment'
import { ApiError } from '@/shared/api'
import { routes } from '@/shared/config/routes'
import { Button } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { Panel } from '@/shared/ui/panel'
import { matchesSelection, selectedRunIds, toggleAllRunSelection, toggleRunSelection, validationMessages } from '../model/history-actions'
import styles from './experiments-page.module.css'

type ComparisonLink = { runIds: string[]; grafanaUrl: string }

export function ExperimentsPage() {
  const navigate = useNavigate()
  const { runs, error, loading, retry } = useExperimentHistory()
  const [selection, setSelection] = useState<string[]>([])
  const [deletion, setDeletion] = useState<string[] | null>(null)
  const [comparison, setComparison] = useState<ComparisonLink | null>(null)
  const [pending, setPending] = useState<'delete' | 'compare' | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const requestRef = useRef<AbortController | null>(null)
  const dialogRef = useRef<HTMLDialogElement | null>(null)
  const deleteButtonRef = useRef<HTMLButtonElement | null>(null)
  const historyTitleRef = useRef<HTMLHeadingElement | null>(null)
  const selectAllRef = useRef<HTMLInputElement | null>(null)
  const selected = selectedRunIds(runs ?? [], selection)
  const comparisonReady = comparison && matchesSelection(comparison.runIds, selected)
  const busy = pending !== null
  const allSelected = !!runs?.length && selected.length === runs.length
  const createExperiment = () => navigate(routes.newExperiment)

  useEffect(() => () => requestRef.current?.abort(), [])
  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (deletion && !dialog.open) dialog.showModal()
    if (!deletion && dialog.open) dialog.close()
  }, [deletion])
  useEffect(() => {
    if (selectAllRef.current) selectAllRef.current.indeterminate = selected.length > 0 && !allSelected
  }, [selected.length, allSelected])

  function changeSelection(id: string) {
    setSelection(current => toggleRunSelection(current, id))
    setComparison(null)
    setActionError(null)
  }

  function clearSelection() {
    setSelection([])
    setComparison(null)
    setActionError(null)
  }

  function toggleAllDisplayed() {
    setSelection(current => toggleAllRunSelection(runs ?? [], current))
    setComparison(null)
    setActionError(null)
  }

  function cancelDelete() {
    if (requestRef.current) return
    setDeletion(null)
    setActionError(null)
  }

  function showDeleteConfirmation(event: MouseEvent<HTMLButtonElement>) {
    if (requestRef.current || !selected.length) return
    deleteButtonRef.current = event.currentTarget
    setDeletion(selected)
    setActionError(null)
  }

  function cancelDialog(event: SyntheticEvent<HTMLDialogElement>) {
    event.preventDefault()
    cancelDelete()
  }

  function dialogClosed() {
    setDeletion(null)
    if (deleteButtonRef.current && !deleteButtonRef.current.disabled) deleteButtonRef.current.focus()
    else historyTitleRef.current?.focus()
  }

  async function deleteConfirmed() {
    if (!deletion?.length || requestRef.current) return
    const runIds = deletion
    const controller = new AbortController()
    requestRef.current = controller
    setPending('delete')
    setActionError(null)

    try {
      await deleteExperiments(runIds, controller.signal)
      if (controller.signal.aborted) return
      const deleted = new Set(runIds)
      setDeletion(null)
      setSelection(current => current.filter(id => !deleted.has(id)))
      setComparison(null)
      retry()
    } catch (caught) {
      if (!controller.signal.aborted) {
        setActionError(actionErrorMessage(caught, 'Не удалось удалить выбранные записи'))
      }
    } finally {
      if (requestRef.current === controller) requestRef.current = null
      if (!controller.signal.aborted) setPending(null)
    }
  }

  async function compareSelected() {
    if (selected.length < 2 || requestRef.current) return
    const runIds = selected
    const controller = new AbortController()
    requestRef.current = controller
    setPending('compare')
    setComparison(null)
    setActionError(null)

    try {
      const result = await compareExperiments(runIds, controller.signal)
      if (!controller.signal.aborted) setComparison({ runIds, grafanaUrl: result.grafanaUrl })
    } catch (caught) {
      if (!controller.signal.aborted) setActionError(actionErrorMessage(caught, 'Не удалось подготовить сравнение'))
    } finally {
      if (requestRef.current === controller) requestRef.current = null
      if (!controller.signal.aborted) setPending(null)
    }
  }

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Рабочее пространство"
        title="История запусков"
        description="Статусы и результаты экспериментов, сохранённых в платформе."
        actions={(
          <Button onClick={createExperiment}>
            <Plus size={16} aria-hidden="true" />
            Новый эксперимент
          </Button>
        )}
      />
      <Panel className={styles.history} aria-labelledby="history-title">
        <div className={styles.toolbar}>
          <h2 id="history-title" ref={historyTitleRef} tabIndex={-1}>Последние 100 запусков</h2>
          <div className={styles.toolbarActions}>
            <Button variant="secondary" onClick={retry} disabled={loading || busy}>
              <RefreshCw size={15} aria-hidden="true" />
              {loading ? 'Обновляем…' : 'Обновить'}
            </Button>
          </div>
        </div>

        {!!runs?.length && (
          <div className={styles.selectionBar}>
            <label className={styles.selectAll}>
              <input
                ref={selectAllRef}
                className={styles.checkbox}
                type="checkbox"
                aria-label="Выбрать все отображаемые запуски (последние 100)"
                checked={allSelected}
                disabled={busy}
                onChange={toggleAllDisplayed}
              />
              Выбрать все отображаемые
            </label>
            <p id="comparison-selection-hint" role="status">{selected.length
              ? `Выбрано ${selected.length}`
              : 'Выберите запуски для сравнения или удаления.'}</p>
            <div className={styles.toolbarActions}>
              {comparisonReady ? (
                <a className={styles.comparisonLink} href={comparison.grafanaUrl} target="_blank" rel="noopener noreferrer">
                  Открыть сравнение в Grafana <ArrowUpRight size={15} aria-hidden="true" />
                </a>
              ) : (
                <Button onClick={compareSelected} disabled={selected.length < 2 || busy || !!deletion} aria-describedby="comparison-selection-hint" title={selected.length < 2 ? 'Для сравнения выберите минимум два запуска' : undefined}>
                  {pending === 'compare' ? 'Готовим сравнение…' : 'Сравнить в Grafana'}
                </Button>
              )}
              <Button variant="secondary" className={styles.dangerButton} onClick={showDeleteConfirmation} disabled={!selected.length || busy}>
                <Trash2 size={15} aria-hidden="true" />
                Удалить выбранные
              </Button>
              {!!selected.length && (
                <Button variant="secondary" disabled={busy} onClick={clearSelection}>Снять выделение</Button>
              )}
            </div>
          </div>
        )}

        {actionError && !deletion && <div className={styles.errorState}><p role="alert">{actionError}</p></div>}

        {error && (
          <div className={styles.errorState}>
            <p role="alert">Не удалось обновить историю. {error}</p>
            <Button variant="secondary" onClick={retry} disabled={loading || busy}>Повторить</Button>
          </div>
        )}

        {runs?.length ? (
          <ul className={styles.runs} aria-label="Запуски экспериментов">
            {runs.map(run => (
              <li key={run.id} className={styles.run}>
                <input
                  className={styles.checkbox}
                  type="checkbox"
                  aria-label={`Выбрать запуск ${run.id}`}
                  checked={selected.includes(run.id)}
                  disabled={busy}
                  onChange={() => changeSelection(run.id)}
                />
                <div className={styles.experiment}>
                  <p className={styles.pluginTitle}>{run.pluginTitle ?? run.pluginId ?? 'Плагин не указан'}</p>
                  <p className={styles.scenarioTitle}>{run.scenarioTitle ?? run.scenarioId ?? 'Сценарий не указан'}</p>
                  <Link className={styles.runId} to={routes.experiment(run.id)}>{run.id}</Link>
                </div>
                <div className={styles.details}>
                  <span className={styles.dateLabel}>Создан</span>
                  <time dateTime={run.createdAt}>{formatCreatedAt(run.createdAt)}</time>
                  <span
                    className={styles.status}
                    data-active={!isExperimentFinished(run.state)}
                    data-error={Boolean(run.error)}
                  >
                    {experimentStateLabel(run.state)}
                  </span>
                </div>
                <div className={styles.runActions}>
                  <Link className={styles.actionLink} to={routes.experiment(run.id)}>Открыть запуск</Link>
                  {run.grafanaUrl && (
                    <a className={styles.actionLink} href={run.grafanaUrl} target="_blank" rel="noreferrer">
                      Grafana
                      <ArrowUpRight size={14} aria-hidden="true" />
                    </a>
                  )}
                </div>
                {run.error && <p className={styles.runError}>{run.error}</p>}
              </li>
            ))}
          </ul>
        ) : loading ? (
          <p className={styles.notice} role="status">Загружаем историю запусков…</p>
        ) : !error && (
          <div className={styles.emptyState}>
            <History size={36} strokeWidth={1.25} className={styles.emptyIcon} aria-hidden="true" />
            <h3>Запусков пока нет</h3>
            <p>Создайте эксперимент, чтобы его состояние и результаты появились в истории.</p>
            <Button variant="secondary" onClick={createExperiment}>Создать эксперимент</Button>
          </div>
        )}
      </Panel>
      <dialog
        ref={dialogRef}
        className={styles.deleteDialog}
        aria-labelledby="history-delete-title"
        aria-describedby="history-delete-description"
        aria-busy={pending === 'delete'}
        onCancel={cancelDialog}
        onClose={dialogClosed}
      >
        <h2 id="history-delete-title">Удалить выбранные запуски?</h2>
        <p id="history-delete-description">Выбранные запуски ({deletion?.length ?? 0}) будут скрыты из истории. Выполняющиеся задачи продолжат работу.</p>
        {actionError && <p className={styles.dialogError} role="alert">{actionError}</p>}
        <div className={styles.toolbarActions}>
          <Button variant="secondary" disabled={busy} onClick={cancelDelete} autoFocus>Отмена</Button>
          <Button variant="secondary" className={styles.dangerButton} disabled={busy} onClick={deleteConfirmed}>
            {pending === 'delete' ? 'Удаляем…' : 'Удалить выбранные'}
          </Button>
        </div>
      </dialog>
    </div>
  )
}

function actionErrorMessage(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    const messages = validationMessages(error.body)
    return messages.length ? messages.join(' ') : `${fallback} (HTTP ${error.status}).`
  }
  return `${fallback}. Проверьте соединение с сервером.`
}

function formatCreatedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
