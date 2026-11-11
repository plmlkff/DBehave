type RunIdentity = { id: string }

export function selectedRunIds(runs: readonly RunIdentity[], selection: readonly string[]) {
  const selected = new Set(selection)
  return runs.filter(run => selected.has(run.id)).map(run => run.id)
}

export function toggleRunSelection(selection: readonly string[], id: string) {
  return selection.includes(id) ? selection.filter(selected => selected !== id) : [...selection, id]
}

export function toggleAllRunSelection(runs: readonly RunIdentity[], selection: readonly string[]) {
  const visibleIds = runs.map(run => run.id)
  return selectedRunIds(runs, selection).length === visibleIds.length ? [] : visibleIds
}

export function matchesSelection(comparison: readonly string[], selection: readonly string[]) {
  const compared = new Set(comparison)
  const selected = new Set(selection)
  return compared.size === selected.size && [...compared].every(id => selected.has(id))
}

export function validationMessages(body: unknown): string[] {
  if (!body || typeof body !== 'object' || !('fieldErrors' in body) || !Array.isArray(body.fieldErrors)) return []
  const messages = body.fieldErrors.flatMap(error => (
    error && typeof error === 'object' && 'message' in error && typeof error.message === 'string'
      ? [error.message] : []
  ))
  return [...new Set(messages)]
}
