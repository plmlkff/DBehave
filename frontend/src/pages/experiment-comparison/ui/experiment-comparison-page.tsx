import { ArrowLeft, ExternalLink } from 'lucide-react'
import { Link, useSearchParams } from 'react-router'
import {
  experimentStateLabel,
  getExperimentComparisonGrafanaUrl,
  isExperimentFinished,
  useExperimentComparison,
} from '@/entities/experiment'
import { routes } from '@/shared/config/routes'
import { Button } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { Panel } from '@/shared/ui/panel'
import styles from './experiment-comparison-page.module.css'

export function ExperimentComparisonPage() {
  const [searchParams] = useSearchParams()
  const runIds = [...new Set(searchParams.getAll('runId').filter(Boolean))]
  const { data, error, loading, retry } = useExperimentComparison(runIds)
  const grafanaUrl = data?.grafanaUrl ? getExperimentComparisonGrafanaUrl(data.grafanaUrl, data.runs) : null
  const active = data?.runs.some(run => !isExperimentFinished(run.state))
  const historyQuery = new URLSearchParams()
  for (const id of runIds) historyQuery.append('runId', id)

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Эксперименты / Сравнение"
        title="Сравнение запусков"
        description="Параметры и графики выбранных экспериментов на общей временной шкале."
        actions={(
          <Link className={styles.backLink} to={`${routes.experiments}?${historyQuery}`}>
            <ArrowLeft size={16} aria-hidden="true" /> История запусков
          </Link>
        )}
      />
      {error && (
        <Panel className={styles.loadError} aria-label="Ошибка загрузки сравнения">
          <p role="alert">Не удалось загрузить сравнение. {error}</p>
          <Button variant="secondary" onClick={retry} disabled={loading}>Повторить</Button>
        </Panel>
      )}
      {!data && loading && <Panel><p className={styles.notice} role="status">Подготавливаем сравнение…</p></Panel>}
      {data?.validationError && <Panel><p role="alert">{data.validationError}</p></Panel>}
      {data && !data.validationError && (
        <>
          <Panel aria-labelledby="comparison-runs-title">
            <div className={styles.sectionHeading}>
              <h2 id="comparison-runs-title">Выбранные запуски · {data.runs.length}</h2>
              <p className={styles.notice}>{data.runs[0]?.pluginTitle} · {data.runs[0]?.scenarioTitle}</p>
            </div>
            <ul className={styles.runs}>
              {data.runs.map((run, index) => (
                <li key={run.id} className={styles.run}>
                  <div className={styles.runHeading}>
                    <span>Запуск {index + 1}</span>
                    <span className={styles.state} data-active={!isExperimentFinished(run.state)} data-error={Boolean(run.error)}>
                      {experimentStateLabel(run.state)}
                    </span>
                  </div>
                  <Link className={styles.runId} to={routes.experiment(run.id)}>{run.id}</Link>
                  <p className={styles.notice}>Создан: <time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString('ru-RU')}</time></p>
                </li>
              ))}
            </ul>
          </Panel>
          <Panel aria-labelledby="comparison-charts-title">
            <div className={styles.sectionHeading}>
              <h2 id="comparison-charts-title">Графики сравнения</h2>
              <p className={styles.notice}>{active ? 'Статусы и графики обновляются автоматически.' : 'Все выбранные запуски завершены. Период графиков зафиксирован.'}</p>
            </div>
            {grafanaUrl ? (
              <>
                <iframe className={styles.grafanaFrame} title="Графики сравнения запусков" src={grafanaUrl} loading="lazy" allow="fullscreen" />
                <a className={styles.grafanaLink} href={grafanaUrl} target="_blank" rel="noopener noreferrer">
                  Открыть Grafana отдельно <ExternalLink size={16} aria-hidden="true" />
                </a>
              </>
            ) : <p className={styles.notice}>Не удалось определить ссылку или период графиков для выбранных запусков.</p>}
          </Panel>
        </>
      )}
    </div>
  )
}
