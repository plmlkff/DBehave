import type { FieldSpec, FieldValue } from '@/shared/lib/bdui'

export type ExperimentSection = {
  id: 'settings' | 'params'
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
}

export type ValidationResult = { status: string; execution: string }
