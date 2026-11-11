import { useCallback } from 'react'
import { useRemoteResource } from '@/shared/lib/react'
import { getExperiment, getExperiments } from '../api/experiment-api'
import { isExperimentFinished } from './run-state'
import type { ExperimentRun } from './types'

function isActive(run: ExperimentRun) {
  return !isExperimentFinished(run.state)
}

function hasActiveRuns(runs: ExperimentRun[]) {
  return runs.some(isActive)
}

export function useExperimentRun(id: string) {
  const load = useCallback((signal: AbortSignal) => getExperiment(id, signal), [id])
  const { data, ...resource } = useRemoteResource(`experiment:${id}`, load, {
    intervalMs: 2000,
    shouldPoll: isActive,
  })
  return { run: data, ...resource }
}

export function useExperimentHistory() {
  const { data, ...resource } = useRemoteResource('experiment-history', getExperiments, {
    intervalMs: 2000,
    shouldPoll: hasActiveRuns,
  })
  return { runs: data, ...resource }
}
