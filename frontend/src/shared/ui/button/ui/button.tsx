import type { ComponentPropsWithoutRef } from 'react'
import styles from './button.module.css'

type ButtonProps = ComponentPropsWithoutRef<'button'> & {
  variant?: 'primary' | 'secondary'
}

export function Button({ variant = 'primary', className, type = 'button', children, ...props }: ButtonProps) {
  return (
    <button {...props} type={type} className={[styles.button, styles[variant], className].filter(Boolean).join(' ')}>
      {children}
    </button>
  )
}
