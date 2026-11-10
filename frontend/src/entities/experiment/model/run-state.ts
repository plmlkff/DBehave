const stateLabels: Record<string, string> = {
  QUEUED: 'В очереди',
  STARTING: 'Запускается',
  RUNNING: 'Выполняется',
  SUCCEEDED: 'Завершён',
  DONE: 'Завершён',
  COMPLETED: 'Завершён',
  FAILED: 'Ошибка',
  CANCELLED: 'Отменён',
  CANCELED: 'Отменён',
}

const finishedStates = new Set(['SUCCEEDED', 'DONE', 'COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'])

export function experimentStateLabel(state: string) {
  return stateLabels[state] ?? state
}

export function isExperimentFinished(state: string) {
  return finishedStates.has(state)
}
