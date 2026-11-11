export const routes = {
  newExperiment: '/experiments/new',
  experiments: '/experiments',
  experimentPattern: '/experiments/:id',
  experiment: (id: string) => `/experiments/${encodeURIComponent(id)}`,
} as const
