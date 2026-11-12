import type { ExperimentRequest } from '@/entities/experiment'
import { Panel } from '@/shared/ui/panel'
import { configurationSections } from '../model/configuration'
import styles from './run-configuration.module.css'

type RunConfigurationProps = {
  configuration: ExperimentRequest | null
  title?: string
}

export function RunConfiguration({ configuration, title = 'Параметры запуска' }: RunConfigurationProps) {
  return (
    <Panel>
      <details className={styles.configuration}>
        <summary>{title}</summary>
        {configuration ? (
          <div className={styles.sections}>
            {configurationSections(configuration).map(section => (
              <section key={section.id} className={styles.section}>
                <h3>{section.title}</h3>
                {section.fields.length ? (
                  <table aria-label={section.title}>
                    <thead><tr><th scope="col">Параметр</th><th scope="col">Значение</th></tr></thead>
                    <tbody>
                      {section.fields.map(field => (
                        <tr key={field.key}><th scope="row">{field.title}</th><td>{field.value}</td></tr>
                      ))}
                    </tbody>
                  </table>
                ) : <p className={styles.notice}>Параметры не заданы.</p>}
              </section>
            ))}
          </div>
        ) : <p className={styles.notice}>Для этого запуска параметры не сохранены.</p>}
      </details>
    </Panel>
  )
}
