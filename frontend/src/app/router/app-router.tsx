import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { ExperimentsPage } from '@/pages/experiments'
import { ExperimentRunPage } from '@/pages/experiment-run'
import { ExperimentComparisonPage } from '@/pages/experiment-comparison'
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
          <Route path={routes.experimentComparisonPattern} element={<ExperimentComparisonPage />} />
          <Route path={routes.experimentPattern} element={<ExperimentRunPage />} />
          <Route path="*" element={<Navigate to={routes.newExperiment} replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
