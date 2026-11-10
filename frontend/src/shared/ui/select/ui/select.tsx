import type { ComponentPropsWithoutRef } from 'react'
import * as SelectPrimitive from '@radix-ui/react-select'
import { Check, ChevronDown, ChevronUp } from 'lucide-react'
import styles from './select.module.css'

type SelectProps = Pick<ComponentPropsWithoutRef<'button'>,
  'id' | 'aria-label' | 'aria-labelledby' | 'aria-describedby' | 'aria-invalid'
> & {
  name: string
  value: string
  options: (string | { value: string; label: string })[]
  placeholder?: string
  disabled?: boolean
  onValueChange: (value: string) => void
}

export function Select({ name, value, options, placeholder, disabled, onValueChange, ...accessibility }: SelectProps) {
  return (
    <SelectPrimitive.Root name={name} value={value} onValueChange={onValueChange} disabled={disabled}>
      <SelectPrimitive.Trigger {...accessibility} className={styles.trigger}>
        <SelectPrimitive.Value placeholder={placeholder} />
        <SelectPrimitive.Icon asChild>
          <ChevronDown size={18} strokeWidth={1.75} className={styles.chevron} aria-hidden="true" />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Content
          className={styles.content}
          position="popper"
          side="bottom"
          align="start"
          sideOffset={8}
          collisionPadding={12}
          hideWhenDetached
        >
          <SelectPrimitive.ScrollUpButton className={styles.scrollButton}>
            <ChevronUp size={16} aria-hidden="true" />
          </SelectPrimitive.ScrollUpButton>
          <SelectPrimitive.Viewport className={styles.viewport}>
            {options.map(option => {
              const { value: optionValue, label } = typeof option === 'string'
                ? { value: option, label: option } : option

              return (
                <SelectPrimitive.Item key={optionValue} value={optionValue} className={styles.item}>
                  <SelectPrimitive.ItemText>{label}</SelectPrimitive.ItemText>
                  <SelectPrimitive.ItemIndicator className={styles.indicator}>
                    <Check size={16} strokeWidth={2} aria-hidden="true" />
                  </SelectPrimitive.ItemIndicator>
                </SelectPrimitive.Item>
              )
            })}
          </SelectPrimitive.Viewport>
          <SelectPrimitive.ScrollDownButton className={styles.scrollButton}>
            <ChevronDown size={16} aria-hidden="true" />
          </SelectPrimitive.ScrollDownButton>
        </SelectPrimitive.Content>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  )
}
