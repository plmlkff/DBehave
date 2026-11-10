import type { ComponentPropsWithoutRef } from 'react'
import styles from './panel.module.css'

export function Panel({ className, children, ...props }: ComponentPropsWithoutRef<'section'>) {
  return (
    <section {...props} className={[styles.panel, className].filter(Boolean).join(' ')}>
      {children}
    </section>
  )
}
