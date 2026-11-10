import { List, Plus } from 'lucide-react'
import { NavLink } from 'react-router'
import { routes } from '@/shared/config/routes'
import styles from './sidebar.module.css'

const navigation = [
  { to: routes.newExperiment, title: 'Новый эксперимент', icon: Plus },
  { to: routes.experiments, title: 'История запусков', icon: List },
]

export function Sidebar() {
  return (
    <aside className={styles.sidebar}>
      <div className={styles.brand}>
        <NavLink to={routes.newExperiment} className={styles.logo} aria-label="DBehave — новый эксперимент">
          <span className={styles.mark} aria-hidden="true">D</span>
          <span>DBehave</span>
        </NavLink>
        <p className={styles.tagline}>Лаборатория баз данных</p>
      </div>
      <nav className={styles.navigation} aria-label="Основная навигация">
        {navigation.map(({ to, title, icon: Icon }) => (
          <NavLink key={to} to={to} end className={({ isActive }) => [styles.link, isActive && styles.active].filter(Boolean).join(' ')}>
            <Icon size={16} strokeWidth={1.75} aria-hidden="true" />
            <span>{title}</span>
          </NavLink>
        ))}
      </nav>
      <footer className={styles.footer}>
        <p>Учитесь на экспериментах</p>
        <p>Исследуйте поведение СУБД</p>
        <p className={styles.footerLabel}>DBehave / Open Source</p>
      </footer>
    </aside>
  )
}
