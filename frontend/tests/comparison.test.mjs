import assert from 'node:assert/strict'
import test from 'node:test'
import { getExperimentComparisonError } from '../src/entities/experiment/model/comparison.ts'
import { getExperimentComparisonGrafanaUrl } from '../src/entities/experiment/model/grafana-url.ts'

const first = {
  id: 'run-a',
  pluginId: 'postgres',
  scenarioId: 'mixed',
  grafanaUrlTemplate: 'http://localhost:3001/d/postgres?var-run_id={runId}',
  grafanaUrl: 'http://localhost:3001/d/postgres?var-run_id=run-a',
  createdAt: '2026-11-11T11:00:00Z',
  updatedAt: '2026-11-11T11:00:40Z',
  state: 'SUCCEEDED',
}
const second = {
  ...first,
  id: 'run-b',
  createdAt: '2026-11-11T12:00:00Z',
  updatedAt: '2026-11-11T12:01:00Z',
}
const comparisonUrl = 'http://localhost:3001/d/postgres?var-run_id=run-a&var-run_id=run-b&kiosk=1&refresh=30s#panel-4'

test('comparison requires two runs and rejects different plugins even with matching scenarios and dashboards', () => {
  assert.match(getExperimentComparisonError([]), /минимум два/)
  assert.match(getExperimentComparisonError([first]), /минимум два/)
  assert.equal(getExperimentComparisonError([first, second]), null)
  assert.match(getExperimentComparisonError([first, { ...second, pluginId: 'mysql' }]), /одного плагина/)
  assert.match(getExperimentComparisonError([first, { ...second, scenarioId: 'read-heavy' }]), /одного сценария/)
  assert.match(getExperimentComparisonError([first, { ...second, grafanaUrlTemplate: 'http://localhost:3001/d/other?var-run_id={runId}' }]), /одинаковый шаблон/)
})

test('old runs with missing metadata cannot be treated as compatible', () => {
  for (const field of ['pluginId', 'scenarioId', 'grafanaUrlTemplate']) {
    for (const value of [null, undefined, '', '   ']) {
      assert.match(getExperimentComparisonError([first, { ...second, [field]: value }]), /отсутствуют данные/)
    }
  }
})

test('completed comparison covers all runs regardless of history order and preserves backend-selected IDs', () => {
  const url = new URL(getExperimentComparisonGrafanaUrl(comparisonUrl, [second, first]))
  assert.equal(url.searchParams.get('from'), String(Date.parse(first.createdAt) - 5000))
  assert.equal(url.searchParams.get('to'), String(Date.parse(second.updatedAt) + 10_000))
  assert.deepEqual(url.searchParams.getAll('var-run_id'), ['run-a', 'run-b'])
  assert.equal(url.searchParams.get('kiosk'), '1')
  assert.equal(url.hash, '#panel-4')
  assert.equal(url.searchParams.has('refresh'), false)
})

test('comparison refreshes while any run is active and stops after the last run finishes or fails', () => {
  const active = { ...second, state: 'RUNNING' }
  const liveUrl = getExperimentComparisonGrafanaUrl(comparisonUrl, [first, active])
  const url = new URL(liveUrl)
  assert.equal(url.searchParams.get('from'), String(Date.parse(first.createdAt) - 5000))
  assert.equal(url.searchParams.get('to'), 'now')
  assert.equal(url.searchParams.get('refresh'), '1s')
  assert.equal(getExperimentComparisonGrafanaUrl(comparisonUrl, [first, { ...active, updatedAt: '2026-11-11T12:00:55Z' }]), liveUrl)

  const finished = new URL(getExperimentComparisonGrafanaUrl(comparisonUrl, [first, { ...active, state: 'FAILED' }]))
  assert.equal(finished.searchParams.get('to'), String(Date.parse(second.updatedAt) + 10_000))
  assert.equal(finished.searchParams.has('refresh'), false)
})

test('comparison refuses missing runs, invalid timestamps and invalid Grafana URLs', () => {
  assert.equal(getExperimentComparisonGrafanaUrl(comparisonUrl, []), null)
  assert.equal(getExperimentComparisonGrafanaUrl(comparisonUrl, [first]), null)
  assert.equal(getExperimentComparisonGrafanaUrl('invalid-url', [first, second]), null)
  assert.equal(getExperimentComparisonGrafanaUrl(comparisonUrl, [first, { ...second, createdAt: 'invalid' }]), null)
  assert.equal(getExperimentComparisonGrafanaUrl(comparisonUrl, [first, { ...second, updatedAt: 'invalid' }]), null)
})
