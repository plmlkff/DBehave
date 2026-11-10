import { request } from '@/shared/api'
import type { PluginSummary } from '../model/types'

export function getPlugins(signal: AbortSignal) {
  return request<PluginSummary[]>('/plugins', { signal })
}
