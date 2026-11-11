import { useCallback, useRef } from 'react'
import { useRemoteResource } from '@/shared/lib/react'
import { compareExperiments, getExperiment } from '../api/experiment-api'
import { getExperimentComparisonError } from './comparison'
import { isExperimentFinished } from './run-state'
import type { ExperimentRun } from './types'

type Comparison = {
  runs: ExperimentRun[]
  grafanaUrl: string | null
  validationError: string | null
}

function hasActiveRuns(comparison: Comparison) {
  return !comparison.validationError && comparison.runs.some(run => !isExperimentFinished(run.state))
}

export function useExperimentComparison(runIds: readonly string[]) {
  const selectionKey = JSON.stringify(runIds)
  const cachedUrl = useRef<{ key: string; url: string } | null>(null)
  const load = useCallback(async (signal: AbortSignal): Promise<Comparison> => {
    const ids: string[] = JSON.parse(selectionKey)
    if (ids.length < 2) {
      return { runs: [], grafanaUrl: null, validationError: 'Для сравнения выберите минимум два запуска в истории.' }
    }
    const runs = await Promise.all(ids.map(id => getExperiment(id, signal)))
    const validationError = getExperimentComparisonError(runs)
    if (validationError) return { runs, grafanaUrl: null, validationError }

    let grafanaUrl = cachedUrl.current?.key === selectionKey ? cachedUrl.current.url : null
    if (!grafanaUrl) {
      const result = await compareExperiments(ids, signal)
      grafanaUrl = result.grafanaUrl
      if (!signal.aborted) cachedUrl.current = { key: selectionKey, url: grafanaUrl }
    }
    return { runs, grafanaUrl, validationError: null }
  }, [selectionKey])

  return useRemoteResource(`comparison:${selectionKey}`, load, {
    intervalMs: 2000,
    shouldPoll: hasActiveRuns,
  })
}
