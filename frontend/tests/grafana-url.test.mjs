import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { getExperimentGrafanaUrl } from '../src/entities/experiment/model/grafana-url.ts'
import { grafanaEmbedUrl } from '../src/pages/experiment-run/model/grafana-url.ts'

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

test('a queued numeric run supplies a zero offset before any metrics exist', () => {
  const url = new URL(getExperimentGrafanaUrl({
    ...run, state: 'QUEUED', grafanaUrl: 'http://localhost:3001/d/dbheave-mongo?var-run_id=8',
  }))
  assert.equal(url.searchParams.get('var-run_offsets'),
    'label_replace(vector(0), "run_id", "8", "__name__", ".*")')
  assert.equal(getExperimentGrafanaUrl({ ...run, grafanaUrl: url.toString() }), url.toString())
})

test('processing a dashboard URL preserves explicit comparison offsets', () => {
  const offsets = 'label_replace(vector(1000), "run_id", "1", "__name__", ".*")'
  const value = new URL('http://localhost:3001/d/test?var-run_id=1')
  value.searchParams.set('var-run_offsets', offsets)
  const url = new URL(getExperimentGrafanaUrl({ ...run, grafanaUrl: value.toString() }))
  assert.equal(url.searchParams.get('var-run_offsets'), offsets)
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

test('normalizes missing, bare, empty, tv and duplicate kiosk parameters to exactly one explicit enabled value', () => {
  for (const query of ['', '?kiosk', '?kiosk=', '?kiosk=tv', '?kiosk=tv&kiosk&kiosk=0', '?kiosk=1']) {
    const result = new URL(grafanaEmbedUrl(`http://localhost:3001/d/postgres${query}`))
    assert.deepEqual(result.searchParams.getAll('kiosk'), ['1'])
    assert.deepEqual(result.searchParams.getAll('refresh'), [])
    assert.match(result.search, /(?:\?|&)kiosk=1(?:&|$)/)
  }
})

test('preserves repeated run IDs, other query values and the dashboard fragment', () => {
  const original = new URL('https://grafana.example/d/postgres?orgId=1&var-run_id=run-a&var-run_id=run-b&var-label=A%2BB%26C&from=now-1h&refresh=30s&refresh=5s&kiosk=tv#panel-7')
  const result = new URL(grafanaEmbedUrl(original.toString()))
  assert.equal(result.origin, original.origin)
  assert.equal(result.pathname, original.pathname)
  assert.deepEqual(result.searchParams.getAll('var-run_id'), ['run-a', 'run-b'])
  for (const key of ['orgId', 'var-label', 'from', 'refresh']) {
    assert.deepEqual(result.searchParams.getAll(key), original.searchParams.getAll(key))
  }
  assert.equal(result.hash, original.hash)
})

test('keeps the explicit kiosk mode stable when an iframe URL is processed again', () => {
  const result = grafanaEmbedUrl('http://localhost:3001/d/postgres?var-run_id=run-a&kiosk#panel-1')
  assert.equal(grafanaEmbedUrl(result), result)
  assert.equal(new URL(result).searchParams.get('kiosk'), '1')
})

test('invalid URLs produce no iframe source', () => {
  for (const value of ['', 'not a URL', '/relative/dashboard', 'http://']) {
    assert.equal(grafanaEmbedUrl(value), null)
  }
})

test('embedding a run keeps its live or terminal refresh policy and time range', () => {
  for (const state of ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED']) {
    const original = new URL(getExperimentGrafanaUrl({ ...run, state }))
    const embedded = new URL(grafanaEmbedUrl(original.toString()))
    assert.equal(embedded.searchParams.get('kiosk'), '1')
    for (const key of ['var-run_id', 'from', 'to', 'refresh']) {
      assert.deepEqual(embedded.searchParams.getAll(key), original.searchParams.getAll(key))
    }
  }
})
