import assert from 'node:assert/strict'
import test from 'node:test'
import { grafanaEmbedUrl } from '../src/pages/experiment-run/model/grafana-url.ts'

test('normalizes missing, bare, empty, tv and duplicate kiosk parameters to exactly one explicit enabled value', () => {
  for (const query of ['', '?kiosk', '?kiosk=', '?kiosk=tv', '?kiosk=tv&kiosk&kiosk=0', '?kiosk=1']) {
    const result = new URL(grafanaEmbedUrl(`http://localhost:3001/d/postgres${query}`))
    assert.deepEqual(result.searchParams.getAll('kiosk'), ['1'])
    assert.deepEqual(result.searchParams.getAll('refresh'), ['1s'])
    assert.match(result.search, /(?:\?|&)kiosk=1(?:&|$)/)
  }
})

test('preserves repeated run IDs, other query values and the dashboard fragment', () => {
  const original = new URL('https://grafana.example/d/postgres?orgId=1&var-run_id=run-a&var-run_id=run-b&var-label=A%2BB%26C&from=now-1h&refresh=30s&refresh=5s&kiosk=tv#panel-7')
  const result = new URL(grafanaEmbedUrl(original.toString()))
  assert.equal(result.origin, original.origin)
  assert.equal(result.pathname, original.pathname)
  assert.deepEqual(result.searchParams.getAll('var-run_id'), ['run-a', 'run-b'])
  for (const key of ['orgId', 'var-label', 'from']) {
    assert.deepEqual(result.searchParams.getAll(key), original.searchParams.getAll(key))
  }
  assert.deepEqual(result.searchParams.getAll('refresh'), ['1s'])
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
