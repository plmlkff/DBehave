import assert from 'node:assert/strict'
import test from 'node:test'
import { experimentStateLabel, isExperimentFinished } from '../src/entities/experiment/model/run-state.ts'

test('unknown worker states remain literal text, including names inherited from Object.prototype', () => {
  for (const state of ['CUSTOM_STATE', '__proto__', 'constructor', 'toString']) {
    assert.equal(experimentStateLabel(state), state)
    assert.equal(isExperimentFinished(state), false)
  }
})

test('known running and terminal states retain their labels and polling behavior', () => {
  assert.equal(experimentStateLabel('QUEUED'), 'В очереди')
  assert.equal(isExperimentFinished('QUEUED'), false)
  assert.equal(experimentStateLabel('RUNNING'), 'Выполняется')
  assert.equal(isExperimentFinished('RUNNING'), false)
  assert.equal(experimentStateLabel('DONE'), 'Завершён')
  assert.equal(experimentStateLabel('FAILED'), 'Ошибка')
  assert.equal(experimentStateLabel('CANCELLED'), 'Отменён')
  for (const state of ['SUCCEEDED', 'DONE', 'COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED']) {
    assert.equal(isExperimentFinished(state), true)
  }
})
