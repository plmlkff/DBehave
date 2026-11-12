import { isExperimentFinished } from './run-state.ts'
import type { ExperimentRun } from './types.ts'

const START_PADDING_MS = 5000
const END_PADDING_MS = 10_000

type GrafanaRun = Pick<ExperimentRun, 'grafanaUrl' | 'createdAt' | 'updatedAt' | 'state'>

export function getExperimentGrafanaUrl(run: GrafanaRun) {
  if (!run.grafanaUrl) return null

  try {
    const url = new URL(run.grafanaUrl)
    const from = Date.parse(run.createdAt)
    const finished = isExperimentFinished(run.state)
    const to = finished ? Date.parse(run.updatedAt) : null
    if (!Number.isFinite(from) || (to !== null && !Number.isFinite(to))) return null

    url.searchParams.set('from', String(from - START_PADDING_MS))
    url.searchParams.set('to', to === null ? 'now' : String(Math.max(from, to) + END_PADDING_MS))
    if (finished) {
      url.searchParams.delete('refresh')
    } else {
      url.searchParams.set('refresh', '1s')
    }
    // The dashboard must resolve its repeated row even before the first metrics scrape.
    const runIds = url.searchParams.getAll('var-run_id').filter(Boolean)
    if (runIds.length && !url.searchParams.has('var-run_offsets')) {
      url.searchParams.set('var-run_offsets', runIds.map(id =>
        `label_replace(vector(0), "run_id", ${JSON.stringify(id)}, "__name__", ".*")`,
      ).join(' or '))
    }
    return url.toString()
  } catch {
    return null
  }
}

type ComparisonRun = GrafanaRun & Pick<ExperimentRun, 'id' | 'metricsId'>

export function getExperimentComparisonGrafanaUrl(value: string, runs: readonly ComparisonRun[]) {
  if (runs.length < 2) return null
  const starts = runs.map(run => Date.parse(run.createdAt))
  const ends = runs.map(run => Date.parse(run.updatedAt))
  if (![...starts, ...ends].every(Number.isFinite)) return null

  const finished = runs.every(run => isExperimentFinished(run.state))
  const anchor = finished ? Math.max(...starts) : Math.min(...starts)
  const longestDuration = Math.max(...ends.map((end, index) => Math.max(0, end - starts[index])))
  const result = getExperimentGrafanaUrl({
    grafanaUrl: value,
    createdAt: new Date(anchor).toISOString(),
    updatedAt: new Date(finished ? anchor + longestDuration : Math.max(...ends)).toISOString(),
    state: finished ? 'SUCCEEDED' : 'RUNNING',
  })
  if (!result) return null

  const url = new URL(result)
  // Grafana повторяет строки по run_window: value = run_id, text = смещение в мс.
  // Векторы передают только метаданные сравнения; метрики остаются в Prometheus.
  const offsets = runs.map((run, index) => {
    const offsetMs = finished ? anchor - starts[index] : 0
    return `label_replace(vector(${offsetMs}), "run_id", ${JSON.stringify(run.metricsId ?? run.id)}, "__name__", ".*")`
  })
  url.searchParams.set('var-run_offsets', offsets.join(' or '))
  return url.toString()
}
