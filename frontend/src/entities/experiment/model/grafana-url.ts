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
    return url.toString()
  } catch {
    return null
  }
}
