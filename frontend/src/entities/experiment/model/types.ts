import type { FieldSpec, FieldValue } from '@/shared/lib/bdui'

export type ExperimentSection = {
  id: 'settings' | 'params' | 'loaderImageContext' | 'databaseImageContext'
  fields: FieldSpec[]
}

export type ScenarioSummary = {
  id: string
  title: string
  description: string
}

export type ExperimentScreen = {
  schemaVersion: number
  screenId: string
  scenarios: (ScenarioSummary & { selected: boolean })[]
  form: {
    pluginId: string
    pluginTitle: string
    scenarioId: string
    description: string
    sections: ExperimentSection[]
  }
}

export type ExperimentRequest = {
  pluginId: string
  scenarioId: string
  settings: Record<string, FieldValue>
  params: Record<string, FieldValue>
  loaderImageContext: Record<string, FieldValue>
  databaseImageContext: Record<string, FieldValue>
}

export type ValidationResult = { status: string; execution: string }
export type ExperimentComparison = { grafanaUrl: string }

export type ExperimentRun = {
  metricsId?: string
  id: string
  state: string
  createdAt: string
  updatedAt: string
  heartbeat: string | null
  workerId: string | null
  error: string | null
  grafanaUrl: string | null
  pluginId: string | null
  pluginTitle: string | null
  scenarioId: string | null
  scenarioTitle: string | null
  configuration: ExperimentRequest | null
  grafanaUrlTemplate: string | null
}
