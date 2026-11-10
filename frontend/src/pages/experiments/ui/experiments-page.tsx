import { History, Plus } from 'lucide-react'
import { useNavigate } from 'react-router'
import { routes } from '@/shared/config/routes'
import { Button } from '@/shared/ui/button'
import { PageHeader } from '@/shared/ui/page-header'
import { Panel } from '@/shared/ui/panel'
import styles from './experiments-page.module.css'

export function ExperimentsPage() {
  const navigate = useNavigate()
  const createExperiment = () => navigate(routes.newExperiment)

  return (
    <div className={styles.page}>
      <PageHeader
        eyebrow="Рабочее пространство"
        title="История запусков"
        description="Возвращайтесь к экспериментам, повторяйте и сравнивайте результаты."
        actions={(
          <Button onClick={createExperiment}>
            <Plus size={16} aria-hidden="true" />
            Новый эксперимент
          </Button>
        )}
      />
      <Panel className={styles.emptyState} aria-labelledby="history-empty-title">
        <History size={36} strokeWidth={1.25} className={styles.emptyIcon} aria-hidden="true" />
        <h2 id="history-empty-title">Ваши эксперименты появятся здесь</h2>
        <p>Сохраняйте результаты запусков, чтобы исследовать влияние настроек и сравнивать конфигурации.</p>
        <Button variant="secondary" onClick={createExperiment}>Создать эксперимент</Button>
      </Panel>
    </div>
  )
}
