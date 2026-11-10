import { useId, useState } from 'react'
import { Database } from 'lucide-react'
import { usePlugins } from '@/features/configure-experiment'
import { Button } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { Select } from '@/shared/ui/select'
import { ScenarioPanel } from './scenario-panel'
import { ScenarioSetup } from './scenario-setup'
import styles from './new-experiment-page.module.css'

export function NewExperimentPage() {
  const pluginSelectId = useId()
  const [pluginId, setPluginId] = useState('')
  const { data: plugins, error, loading, retry } = usePlugins()
  const selectedPlugin = plugins?.find(plugin => plugin.id === pluginId)

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Эксперименты / Создание"
        title="Новый эксперимент"
        description="Выберите СУБД и сценарий, настройте среду и проверьте гипотезу."
        actions={(
          <div className={styles.databaseSelect}>
            <label htmlFor={pluginSelectId} className={styles.databaseLabel}>
              <Database size={14} aria-hidden="true" />
              База данных
            </label>
            <Select
              id={pluginSelectId}
              name="pluginId"
              value={selectedPlugin?.id ?? ''}
              placeholder={loading ? 'Загружаем СУБД…' : 'Выберите СУБД'}
              options={plugins?.map(plugin => ({ value: plugin.id, label: plugin.title })) ?? []}
              disabled={loading || !plugins?.length}
              onValueChange={setPluginId}
            />
          </div>
        )}
      />
      {selectedPlugin ? (
        <ScenarioSetup key={selectedPlugin.id} pluginId={selectedPlugin.id} />
      ) : (
        <ScenarioPanel>
          {loading ? <p className={styles.notice} role="status">Загружаем список СУБД…</p> : error ? (
            <div className={styles.errorState}>
              <p role="alert">Не удалось загрузить СУБД. {error}</p>
              <Button variant="secondary" onClick={retry}>Повторить</Button>
            </div>
          ) : (
            <div className={styles.emptyState}>
              <Database size={36} strokeWidth={1.25} aria-hidden="true" />
              <h3>{plugins?.length ? 'Сначала выберите базу данных' : 'Пока нет доступных СУБД'}</h3>
              <p>{plugins?.length
                ? 'После выбора появятся доступные сценарии экспериментов.'
                : 'В системе пока не зарегистрированы плагины баз данных.'}</p>
            </div>
          )}
        </ScenarioPanel>
      )}
    </div>
  )
}
