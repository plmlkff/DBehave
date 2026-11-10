import { useEffect, useRef } from 'react'
import { Outlet, useLocation } from 'react-router'
import { routes } from '@/shared/config/routes'
import { Sidebar } from '@/widgets/sidebar'
import styles from './app-layout.module.css'

export function AppLayout() {
  const { pathname } = useLocation()
  const mainRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const title = pathname === routes.experiments ? 'История запусков' : 'Новый эксперимент'
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
