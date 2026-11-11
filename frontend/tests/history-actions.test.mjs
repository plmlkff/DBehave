import assert from 'node:assert/strict'
import test from 'node:test'
import { matchesSelection, selectedRunIds, toggleAllRunSelection, toggleRunSelection, validationMessages } from '../src/pages/experiments/model/history-actions.ts'

test('selection only includes runs still present in the refreshed history', () => {
  const selection = ['run-b', 'deleted-run', 'run-a']
  const runs = [{ id: 'run-a' }, { id: 'run-b' }, { id: 'run-c' }]
  assert.deepEqual(selectedRunIds(runs, selection), ['run-a', 'run-b'])
  assert.deepEqual(selectedRunIds([{ id: 'run-b' }], selection), ['run-b'])
  assert.deepEqual(selectedRunIds([], selection), [])
  assert.deepEqual(selection, ['run-b', 'deleted-run', 'run-a'])
})

test('toggling selection preserves other choices without mutating the original state', () => {
  const selected = ['run-a']
  assert.deepEqual(toggleRunSelection(selected, 'run-b'), ['run-a', 'run-b'])
  assert.deepEqual(toggleRunSelection(selected, 'run-a'), [])
  assert.deepEqual(selected, ['run-a'])
})

test('selecting all is limited to displayed runs and never includes older or deleted history entries', () => {
  const runs = [{ id: 'visible-a' }, { id: 'visible-b' }]
  const selection = ['older-outside-page', 'visible-a']
  assert.deepEqual(toggleAllRunSelection(runs, selection), ['visible-a', 'visible-b'])
  assert.deepEqual(toggleAllRunSelection(runs, ['visible-a', 'visible-b']), [])
  assert.deepEqual(toggleAllRunSelection([], selection), [])
  assert.deepEqual(selection, ['older-outside-page', 'visible-a'])
})

test('a prepared comparison becomes unavailable when any selected run changes or disappears', () => {
  assert.equal(matchesSelection(['run-a', 'run-b'], ['run-b', 'run-a']), true)
  assert.equal(matchesSelection(['run-a', 'run-b'], ['run-a', 'run-c']), false)
  assert.equal(matchesSelection(['run-a', 'run-b'], ['run-a']), false)
  assert.equal(matchesSelection(['run-a', 'run-b'], []), false)
})

test('comparison incompatibility messages remain readable and repeated messages are collapsed', () => {
  assert.deepEqual(validationMessages({ fieldErrors: [
    { field: 'runIds', message: 'Выберите запуски одной доски Grafana.' },
    { field: 'runIds[1]', message: 'Выберите запуски одной доски Grafana.' },
    { field: 'runIds', message: 'Требуется минимум два запуска.' },
  ] }), ['Выберите запуски одной доски Grafana.', 'Требуется минимум два запуска.'])
  for (const body of [null, undefined, 'error', {}, { fieldErrors: null }, { fieldErrors: [null, 1, { message: 404 }] }]) {
    assert.deepEqual(validationMessages(body), [])
  }
})
