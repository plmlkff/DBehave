import { ArrowUpRight, History, Plus, RefreshCw } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { experimentStateLabel, isExperimentFinished, useExperimentHistory } from '@/entities/experiment'
import { routes } from '@/shared/config/routes'
import { Button } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { Panel } from '@/shared/ui/panel'
import styles from './experiments-page.module.css'

export function ExperimentsPage() {
  const navigate = useNavigate()
  const { runs, error, loading, retry } = useExperimentHistory()
  const createExperiment = () => navigate(routes.newExperiment)

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
          <h2 id="history-title">Последние 100 запусков</h2>
          <Button variant="secondary" onClick={retry} disabled={loading}>
            <RefreshCw size={15} aria-hidden="true" />
            {loading ? 'Обновляем…' : 'Обновить'}
          </Button>
        </div>

        {error && (
          <div className={styles.errorState}>
            <p role="alert">Не удалось обновить историю. {error}</p>
            <Button variant="secondary" onClick={retry} disabled={loading}>Повторить</Button>
          </div>
        )}

        {runs?.length ? (
          <ul className={styles.runs} aria-label="Запуски экспериментов">
            {runs.map(run => (
              <li key={run.id} className={styles.run}>
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
    </div>
  )
}

function formatCreatedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
