import { useCallback } from 'react'
import { getExperimentScreen } from '@/entities/experiment'
import { useRemoteResource } from '@/shared/lib/react'

export function useExperimentScreen(pluginId: string, scenarioId: string) {
  const load = useCallback(
    (signal: AbortSignal) => getExperimentScreen(pluginId, scenarioId, signal),
    [pluginId, scenarioId],
  )
  const { data, ...state } = useRemoteResource(JSON.stringify([pluginId, scenarioId]), load)
  return { screen: data, ...state }
}
