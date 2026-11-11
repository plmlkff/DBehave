import { ExternalLink } from 'lucide-react'
import { useNavigate, useParams } from 'react-router'
import { experimentStateLabel, isExperimentFinished, useExperimentRun } from '@/entities/experiment'
import { routes } from '@/shared/config/routes'
import { Button } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { Panel } from '@/shared/ui/panel'
import styles from './experiment-run-page.module.css'

function timestamp(value: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ru-RU')
}

function grafanaEmbedUrl(value: string) {
  try {
    const url = new URL(value)
    url.searchParams.set('kiosk', 'tv')
    url.searchParams.set('refresh', '1s')
    return url.toString()
  } catch {
    return null
  }
}

export function ExperimentRunPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { run, error, loading, retry } = useExperimentRun(id)
  const chartUrl = run?.grafanaUrl ? grafanaEmbedUrl(run.grafanaUrl) : null

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Эксперименты / Запуск"
        title={run?.scenarioTitle || run?.scenarioId || 'Эксперимент'}
        description={run?.pluginTitle || run?.pluginId || `Запуск ${id}`}
        actions={(
          <div className={styles.actions}>
            <Button variant="secondary" onClick={() => navigate(routes.experiments)}>История запусков</Button>
            <Button onClick={() => navigate(routes.newExperiment)}>Новый эксперимент</Button>
          </div>
        )}
      />
      {error && (
        <Panel className={styles.loadError} aria-label="Ошибка обновления статуса">
          <p role="alert">Не удалось получить статус запуска. {error}</p>
          <Button variant="secondary" onClick={retry} disabled={loading}>Повторить</Button>
        </Panel>
      )}
      {!run && loading && <Panel><p className={styles.notice} role="status">Загружаем запуск…</p></Panel>}
      {run && (
        <>
          <Panel aria-labelledby="run-status-title">
            <div className={styles.statusHeading}>
              <h2 id="run-status-title" className={styles.sectionTitle}>Статус запуска</h2>
              <p className={styles.state} role="status" aria-live="polite">{experimentStateLabel(run.state)}</p>
            </div>
            {!isExperimentFinished(run.state) && <p className={styles.notice}>Статус обновляется автоматически.</p>}
            <dl className={styles.details}>
              <div className={styles.runId}><dt>ID запуска</dt><dd>{run.id}</dd></div>
              <div><dt>Создан</dt><dd><time dateTime={run.createdAt}>{timestamp(run.createdAt)}</time></dd></div>
              <div><dt>Обновлён</dt><dd><time dateTime={run.updatedAt}>{timestamp(run.updatedAt)}</time></dd></div>
            </dl>
          </Panel>
          {run.error && (
            <Panel aria-labelledby="run-error-title">
              <h2 id="run-error-title" className={styles.sectionTitle}>Ошибка выполнения</h2>
              <pre className={styles.executionError}>{run.error}</pre>
            </Panel>
          )}
          <Panel aria-labelledby="run-charts-title">
            <h2 id="run-charts-title" className={styles.sectionTitle}>Графики</h2>
            {chartUrl ? (
              <>
                <iframe
                  className={styles.grafanaFrame}
                  title={`Графики запуска ${run.id}`}
                  src={chartUrl}
                  loading="lazy"
                  allow="fullscreen"
                />
                <a className={styles.grafanaLink} href={run.grafanaUrl ?? chartUrl} target="_blank" rel="noopener noreferrer">
                  Открыть Grafana отдельно <ExternalLink size={16} aria-hidden="true" />
                </a>
              </>
            ) : <p className={styles.notice}>Ссылка на графики для этого запуска отсутствует или некорректна.</p>}
          </Panel>
        </>
      )}
    </div>
  )
}
