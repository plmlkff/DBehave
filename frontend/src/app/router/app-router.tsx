import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { ExperimentsPage } from '@/pages/experiments'
import { NewExperimentPage } from '@/pages/new-experiment'
import { routes } from '@/shared/config/routes'
import { AppLayout } from '../layouts/app-layout'

export function AppRouter() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to={routes.newExperiment} replace />} />
          <Route path={routes.newExperiment} element={<NewExperimentPage />} />
          <Route path={routes.experiments} element={<ExperimentsPage />} />
          <Route path="*" element={<Navigate to={routes.newExperiment} replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
