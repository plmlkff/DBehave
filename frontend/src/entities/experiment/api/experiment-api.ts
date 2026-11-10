import { request } from '@/shared/api'
import type { ExperimentRequest, ExperimentRun, ExperimentScreen, ScenarioSummary, ValidationResult } from '../model/types'

export function getScenarios(pluginId: string, signal: AbortSignal) {
  const query = new URLSearchParams({ pluginId })
  return request<ScenarioSummary[]>(`/scenarios?${query}`, { signal })
}

export async function getExperimentScreen(pluginId: string, scenarioId: string, signal: AbortSignal) {
  const query = new URLSearchParams({ pluginId, scenarioId })
  const screen = await request<ExperimentScreen>(`/ui/experiment?${query}`, { signal })

  if (screen.schemaVersion !== 1 || screen.screenId !== 'new-experiment') {
    throw new Error('Эта версия формы пока не поддерживается. Обновите фронтенд.')
  }

  const supportedTypes = ['INTEGER', 'NUMBER', 'BOOLEAN', 'STRING', 'ENUM']
  const supportedSections = ['settings', 'params', 'loaderImageContext', 'databaseImageContext']
  for (const section of screen.form.sections) {
    if (!supportedSections.includes(section.id)) {
      throw new Error(`Неизвестная секция формы: ${section.id}`)
    }
    for (const field of section.fields) {
      if (!supportedTypes.includes(field.type) || !field.name.startsWith(`${section.id}.`)) {
        throw new Error(`Неподдерживаемое поле формы: ${field.name}`)
      }
    }
  }

  return screen
}

export function validateExperiment(payload: ExperimentRequest, signal: AbortSignal) {
  return request<ValidationResult>('/experiments/validate', {
    method: 'POST',
    body: JSON.stringify(payload),
    signal,
  })
}

export function createExperiment(payload: ExperimentRequest, signal: AbortSignal) {
  return request<ExperimentRun>('/experiments', {
    method: 'POST',
    body: JSON.stringify(payload),
    signal,
  })
}

export function getExperiment(id: string, signal: AbortSignal) {
  return request<ExperimentRun>(`/experiments/${encodeURIComponent(id)}`, { signal })
}

export function getExperiments(signal: AbortSignal) {
  return request<ExperimentRun[]>('/experiments', { signal })
}
