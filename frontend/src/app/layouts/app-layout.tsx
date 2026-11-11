import { useEffect, useRef } from 'react'
import { Outlet, useLocation } from 'react-router'
import { routes } from '@/shared/config/routes'
import { Sidebar } from '@/widgets/sidebar'
import styles from './app-layout.module.css'

export function AppLayout() {
  const { pathname } = useLocation()
  const mainRef = useRef<HTMLElement>(null)

  useEffect(() => {
    let title = 'Новый эксперимент'
    if (pathname === routes.experiments) title = 'История запусков'
    else if (pathname.startsWith(`${routes.experiments}/`) && pathname !== routes.newExperiment) title = 'Запуск эксперимента'
    document.title = `${title} — DBehave`
    mainRef.current?.focus({ preventScroll: true })
  }, [pathname])

  return (
    <div className={styles.layout}>
      <a className={styles.skipLink} href="#main-content">Перейти к содержимому</a>
      <Sidebar />
      <main id="main-content" className={styles.main} ref={mainRef} tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
