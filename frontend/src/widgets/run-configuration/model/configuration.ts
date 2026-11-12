import type { ExperimentRequest } from '@/entities/experiment'
import type { FieldValue } from '@/shared/lib/bdui'

const sections = [
  { id: 'settings', title: 'Настройки базы данных' },
  { id: 'params', title: 'Параметры сценария' },
  { id: 'loaderImageContext', title: 'Ресурсы нагрузчика' },
  { id: 'databaseImageContext', title: 'Ресурсы базы данных' },
] as const

export function configurationSections(configuration: ExperimentRequest) {
  return sections.map(section => ({
    ...section,
    fields: Object.entries(configuration[section.id] ?? {}).map(([key, value]) => ({
      key,
      title: section.id === 'loaderImageContext' || section.id === 'databaseImageContext' ? resourceTitle(key) : key,
      value: configurationValue(value),
    })),
  }))
}

function resourceTitle(key: string) {
  if (key === 'core_max') return 'CPU (ядра)'
  if (key === 'mem_max') return 'Память (байт)'
  return key
}

export function configurationValue(value: FieldValue) {
  if (value === true) return 'Да'
  if (value === false) return 'Нет'
  if (value === null) return 'Не задано'
  if (value === '') return 'Пустая строка'
  return String(value)
}
