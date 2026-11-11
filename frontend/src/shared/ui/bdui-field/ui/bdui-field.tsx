import { useId } from 'react'
import { ChevronDown, ChevronUp } from 'lucide-react'
import type { FieldSpec, FieldValue } from '@/shared/lib/bdui'
import { Select } from '@/shared/ui/select'
import { stepNumber } from './number-step'
import styles from './bdui-field.module.css'

type BduiFieldProps = {
  field: FieldSpec
  value: FieldValue
  error?: string
  disabled?: boolean
  onChange: (value: FieldValue) => void
}

export function BduiField({ field, value, error, disabled = false, onChange }: BduiFieldProps) {
  const id = useId()
  const descriptionId = `${id}-description`
  const errorId = `${id}-error`
  const isNumeric = field.type === 'INTEGER' || field.type === 'NUMBER'
  const numericValue = Number(value)
  const hasNumber = value !== null && value !== undefined && String(value).trim() !== '' && Number.isFinite(numericValue)

  function changeNumber(direction: 1 | -1) {
    const next = stepNumber(field, hasNumber ? numericValue : null, direction)
    if (next !== null) onChange(String(next))
  }

  const accessibility = {
    id,
    name: field.name,
    disabled,
    'aria-describedby': [field.description && descriptionId, error && errorId].filter(Boolean).join(' ') || undefined,
    'aria-invalid': Boolean(error),
  }

  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={id}>{field.title}</label>
      {field.description && <p id={descriptionId} className={styles.description}>{field.description}</p>}
      {field.type === 'BOOLEAN' ? (
        <div className={styles.checkboxRow}>
          <input {...accessibility} type="checkbox" checked={value === true} onChange={event => onChange(event.target.checked)} />
          <span aria-hidden="true">{value === true ? 'Включено' : 'Выключено'}</span>
        </div>
      ) : field.type === 'ENUM' ? (
        <Select {...accessibility} value={String(value ?? '')} options={field.options} onValueChange={onChange} />
      ) : (
        <div className={styles.inputRow}>
          <input
            {...accessibility}
            className={styles.control}
            type={field.type === 'STRING' ? 'text' : 'number'}
            value={String(value ?? '')}
            min={field.min ?? undefined}
            max={field.max ?? undefined}
            step={field.type === 'INTEGER' ? 1 : 'any'}
            onChange={event => onChange(event.target.value)}
            onKeyDown={event => {
              if (isNumeric && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
                event.preventDefault()
                changeNumber(event.key === 'ArrowUp' ? 1 : -1)
              }
            }}
          />
          {field.unit && <span className={styles.unit}>{field.unit}</span>}
          {isNumeric && (
            <div className={styles.stepper}>
              <button
                type="button"
                className={styles.stepButton}
                aria-label={`Увеличить ${field.title}`}
                aria-controls={id}
                disabled={disabled || (hasNumber && field.max !== null && numericValue >= field.max)}
                onClick={() => changeNumber(1)}
              >
                <ChevronUp size={18} strokeWidth={1.75} aria-hidden="true" />
              </button>
              <button
                type="button"
                className={styles.stepButton}
                aria-label={`Уменьшить ${field.title}`}
                aria-controls={id}
                disabled={disabled || (hasNumber && field.min !== null && numericValue <= field.min)}
                onClick={() => changeNumber(-1)}
              >
                <ChevronDown size={18} strokeWidth={1.75} aria-hidden="true" />
              </button>
            </div>
          )}
        </div>
      )}
      {error && <p id={errorId} className={styles.error}>{error}</p>}
    </div>
  )
}
