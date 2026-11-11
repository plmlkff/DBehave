import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { getExperimentGrafanaUrl } from '../src/entities/experiment/model/grafana-url.ts'

const run = {
  grafanaUrl: 'http://localhost:3001/d/dbehave-postgres?var-run_id=test-run&refresh=10s',
  createdAt: '2026-11-11T11:27:42.897Z',
  updatedAt: '2026-11-11T11:28:28.106Z',
  state: 'RUNNING',
}

test('status polling keeps the live iframe URL stable until the run finishes', () => {
  const runningUrl = getExperimentGrafanaUrl(run)
  assert.equal(getExperimentGrafanaUrl({ ...run, updatedAt: '2026-11-11T11:28:30Z' }), runningUrl)
  assert.equal(getExperimentGrafanaUrl({ ...run, state: 'QUEUED' }), runningUrl)

  const url = new URL(runningUrl)
  assert.equal(url.searchParams.get('var-run_id'), 'test-run')
  assert.equal(url.searchParams.get('from'), String(Date.parse(run.createdAt) - 5000))
  assert.equal(url.searchParams.get('to'), 'now')
  assert.equal(url.searchParams.get('refresh'), '1s')
})

test('every terminal state switches to a fixed range with no dashboard refresh fallback', () => {
  const dashboard = JSON.parse(readFileSync(new URL('../../plugins/postgres-load/grafana/postgres-run.json', import.meta.url)))
  assert.equal(dashboard.refresh, '')

  for (const state of ['SUCCEEDED', 'DONE', 'COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED']) {
    const url = new URL(getExperimentGrafanaUrl({ ...run, state }))
    assert.equal(url.searchParams.get('var-run_id'), 'test-run')
    assert.equal(url.searchParams.get('from'), String(Date.parse(run.createdAt) - 5000))
    assert.equal(url.searchParams.get('to'), String(Date.parse(run.updatedAt) + 10_000))
    assert.equal(url.searchParams.has('refresh'), false)
  }
})
