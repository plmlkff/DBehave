import { useCallback } from 'react'
import { getPlugins } from '@/entities/database-plugin'
import { getScenarios } from '@/entities/experiment'
import { useRemoteResource } from '@/shared/lib/react'

export function usePlugins() {
  return useRemoteResource('plugins', getPlugins)
}

export function useScenarios(pluginId: string) {
  const load = useCallback((signal: AbortSignal) => getScenarios(pluginId, signal), [pluginId])
  return useRemoteResource(pluginId, load)
}
