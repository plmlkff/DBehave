import type { ReactNode } from 'react'
import { Panel } from '@/shared/ui/panel'
import styles from './new-experiment-page.module.css'

export function ScenarioPanel({ children }: { children: ReactNode }) {
  return (
    <Panel aria-labelledby="scenario-title">
      <div className={styles.sectionHeading}>
        <span className={styles.step} aria-hidden="true">01</span>
        <h2 id="scenario-title">Выбор сценария</h2>
      </div>
      <p className={styles.sectionDescription}>Выберите, какое поведение базы данных хотите исследовать.</p>
      {children}
    </Panel>
  )
}
