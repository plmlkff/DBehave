import { useState } from 'react'
import { ExperimentConfigForm, useExperimentScreen, useScenarios } from '@/features/configure-experiment'
import { Button } from '@/shared/ui/button'
import { Panel } from '@/shared/ui/panel'
import { ScenarioPanel } from './scenario-panel'
import styles from './new-experiment-page.module.css'

function SelectedScenario({ pluginId, scenarioId }: { pluginId: string; scenarioId: string }) {
  const { screen, error, loading, retry } = useExperimentScreen(pluginId, scenarioId)

  if (loading) {
    return <Panel><p className={styles.notice} role="status">Загружаем настройки сценария…</p></Panel>
  }

  if (error) {
    return (
      <Panel>
        <div className={styles.errorState}>
          <p role="alert">Не удалось загрузить форму. {error}</p>
          <Button variant="secondary" onClick={retry}>Повторить</Button>
        </div>
      </Panel>
    )
  }

  return screen ? <ExperimentConfigForm screen={screen} /> : null
}

export function ScenarioSetup({ pluginId }: { pluginId: string }) {
  const { data: scenarios, error, loading, retry } = useScenarios(pluginId)
  const [scenarioId, setScenarioId] = useState('')
  const selectedScenario = scenarios?.find(scenario => scenario.id === scenarioId)

  return (
    <>
      <ScenarioPanel>
        {loading && <p className={styles.notice} role="status">Загружаем сценарии…</p>}
        {error && (
          <div className={styles.errorState}>
            <p role="alert">Не удалось загрузить сценарии. {error}</p>
            <Button variant="secondary" onClick={retry}>Повторить</Button>
          </div>
        )}
        {scenarios?.length === 0 && <p className={styles.notice}>Для этой СУБД пока нет сценариев.</p>}
        {scenarios && scenarios.length > 0 && (
          <>
            <div className={styles.scenarios} aria-label="Сценарии эксперимента">
              {scenarios.map(scenario => (
                <button
                  key={scenario.id}
                  type="button"
                  className={`${styles.scenario} ${scenarioId === scenario.id ? styles.selected : ''}`}
                  aria-pressed={scenarioId === scenario.id}
                  onClick={() => setScenarioId(scenario.id)}
                >
                  <span className={styles.scenarioTitle}>{scenario.title}</span>
                  <span className={styles.scenarioDescription}>{scenario.description}</span>
                </button>
              ))}
            </div>
            {!selectedScenario && <p className={styles.selectionHint}>Выберите сценарий, чтобы перейти к настройкам.</p>}
          </>
        )}
      </ScenarioPanel>
      {selectedScenario && (
        <SelectedScenario key={selectedScenario.id} pluginId={pluginId} scenarioId={selectedScenario.id} />
      )}
    </>
  )
}
