import type { FieldSpec } from '@/shared/lib/bdui'

type NumericBounds = Pick<FieldSpec, 'type' | 'min' | 'max' | 'minExclusive'>

export function stepNumber(field: NumericBounds, value: number | null, direction: 1 | -1): number | null {
  if (field.type === 'INTEGER') {
    let minimum = -Number.MAX_SAFE_INTEGER
    if (field.min !== null) {
      minimum = field.minExclusive ? Math.floor(field.min) + 1 : Math.ceil(field.min)
    }
    const maximum = field.max === null ? Number.MAX_SAFE_INTEGER : Math.floor(field.max)
    const lowerBound = Math.max(-Number.MAX_SAFE_INTEGER, minimum)
    const upperBound = Math.min(Number.MAX_SAFE_INTEGER, maximum)
    if (lowerBound > upperBound) return null

    const current = value ?? 0
    const next = direction === 1 ? Math.floor(current) + 1 : Math.ceil(current) - 1
    return Math.max(lowerBound, Math.min(upperBound, next))
  }

  let next = Number(((value ?? 0) + direction * 0.1).toPrecision(15))
  if (field.min !== null && field.minExclusive && next <= field.min) {
    if (value !== null && value > field.min) return value
    next = field.min + 0.1
  }
  if (field.min !== null) next = Math.max(field.min, next)
  if (field.max !== null) next = Math.min(field.max, next)
  if (!Number.isFinite(next) || (field.minExclusive && field.min !== null && next <= field.min)) return null
  return next
}
