import type { FieldErrors, FieldValues, FieldValue } from '@/shared/lib/bdui'
import type { ExperimentRequest, ExperimentScreen } from './types'

export function createInitialValues(screen: ExperimentScreen): FieldValues {
  return Object.fromEntries(screen.form.sections.flatMap(section =>
    section.fields.map(field => [field.name, field.defaultValue ?? '']),
  ))
}

export function buildExperimentRequest(screen: ExperimentScreen, values: FieldValues) {
  const errors: FieldErrors = {}
  const payload: ExperimentRequest = {
    pluginId: screen.form.pluginId,
    scenarioId: screen.form.scenarioId,
    settings: {},
    params: {},
    loaderImageContext: {},
    databaseImageContext: {},
  }

  for (const section of screen.form.sections) {
    const optional = section.id === 'loaderImageContext' || section.id === 'databaseImageContext'
    for (const field of section.fields) {
      const raw = values[field.name]
      if (optional && (raw === null || raw === undefined || (typeof raw === 'string' && raw.trim() === ''))) {
        continue
      }
      let value: FieldValue = raw

      if (field.type === 'INTEGER' || field.type === 'NUMBER') {
        value = typeof raw === 'number' ? raw : Number(raw)
        if (typeof raw === 'boolean' || raw === null || raw === undefined || String(raw).trim() === '' || !Number.isFinite(value)) {
          errors[field.name] = 'Введите число'
        } else if (field.type === 'INTEGER' && !Number.isSafeInteger(value)) {
          errors[field.name] = 'Введите целое число'
        } else if (field.min !== null && field.minExclusive && value <= field.min) {
          errors[field.name] = `Значение должно быть больше ${field.min}`
        } else if (field.min !== null && value < field.min) {
          errors[field.name] = `Минимальное значение — ${field.min}`
        } else if (field.max !== null && value > field.max) {
          errors[field.name] = `Максимальное значение — ${field.max}`
        }
      } else if (field.type === 'BOOLEAN' && typeof raw !== 'boolean') {
        errors[field.name] = 'Выберите логическое значение'
      } else if (field.type === 'ENUM' && (typeof raw !== 'string' || !field.options.includes(raw))) {
        errors[field.name] = 'Выберите одно из допустимых значений'
      } else if (field.type === 'STRING' && typeof raw !== 'string') {
        errors[field.name] = 'Введите строку'
      }

      payload[section.id][field.name.slice(section.id.length + 1)] = value
    }
  }

  return { payload, errors }
}
