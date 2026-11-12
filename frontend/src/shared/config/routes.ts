export const routes = {
  newExperiment: '/experiments/new',
  experiments: '/experiments',
  experimentComparisonPattern: '/experiments/compare',
  experimentComparison: (runIds: readonly string[]) => {
    const query = new URLSearchParams()
    for (const id of runIds) query.append('runId', id)
    return `/experiments/compare?${query}`
  },
  experimentPattern: '/experiments/:id',
  experiment: (id: string) => `/experiments/${encodeURIComponent(id)}`,
} as const
