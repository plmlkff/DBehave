import type { ExperimentRun } from './types.ts'

type ComparableRun = Pick<ExperimentRun, 'pluginId' | 'scenarioId' | 'grafanaUrlTemplate'>

export function getExperimentComparisonError(runs: readonly ComparableRun[]) {
  if (runs.length < 2) return 'Для сравнения выберите минимум два запуска.'
  if (runs.some(run => !run.pluginId?.trim() || !run.scenarioId?.trim() || !run.grafanaUrlTemplate?.trim())) {
    return 'У выбранных запусков отсутствуют данные плагина, сценария или шаблон дашборда. Выберите другие запуски.'
  }

  const first = runs[0]
  if (runs.some(run => run.pluginId !== first.pluginId)) return 'Можно сравнивать только запуски одного плагина.'
  if (runs.some(run => run.scenarioId !== first.scenarioId)) return 'Можно сравнивать только запуски одного сценария.'
  if (runs.some(run => run.grafanaUrlTemplate !== first.grafanaUrlTemplate)) {
    return 'Для сравнения у запусков должен быть одинаковый шаблон дашборда Grafana.'
  }
  return null
}
