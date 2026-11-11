import assert from 'node:assert/strict'
import test from 'node:test'
import { stepNumber } from '../src/shared/ui/bdui-field/ui/number-step.ts'

const integer = { type: 'INTEGER', min: null, max: null }
const decimal = { type: 'NUMBER', min: null, max: null }

test('increments large safe integers exactly without rounding to 15 significant digits', () => {
  assert.equal(stepNumber(integer, 1000000000000001, 1), 1000000000000002)
  assert.equal(stepNumber(integer, 1000000000000001, -1), 1000000000000000)
})

test('integer steps respect the effective integer range of fractional metadata bounds', () => {
  const field = { ...integer, min: 0.5, max: 3.5 }
  assert.equal(stepNumber(field, 1, -1), 1)
  assert.equal(stepNumber(field, 3, 1), 3)
  assert.equal(stepNumber(field, 1.5, 1), 2)
  assert.equal(stepNumber(field, 1.5, -1), 1)
  assert.equal(stepNumber({ ...integer, min: 0.5, max: 0.75 }, null, 1), null)
})

test('integer steps honor exclusive minimums', () => {
  assert.equal(stepNumber({ ...integer, min: 1, minExclusive: true }, 2, -1), 2)
  assert.equal(stepNumber({ ...integer, min: 1, minExclusive: true }, null, 1), 2)
  assert.equal(stepNumber({ ...integer, min: 1.5, minExclusive: true }, 2, -1), 2)
})

test('integer steps remain within the JavaScript safe integer range', () => {
  assert.equal(stepNumber(integer, Number.MAX_SAFE_INTEGER - 1, 1), Number.MAX_SAFE_INTEGER)
  assert.equal(stepNumber(integer, Number.MAX_SAFE_INTEGER, 1), Number.MAX_SAFE_INTEGER)
  assert.equal(stepNumber(integer, -Number.MAX_SAFE_INTEGER, -1), -Number.MAX_SAFE_INTEGER)
  assert.equal(stepNumber({ ...integer, max: 9223372036854775807 }, Number.MAX_SAFE_INTEGER, 1), Number.MAX_SAFE_INTEGER)
  assert.equal(stepNumber({ ...integer, min: Number.MAX_SAFE_INTEGER, minExclusive: true }, null, 1), null)
})

test('decimal steps keep 0.1 arithmetic free of floating point display artifacts', () => {
  assert.equal(stepNumber(decimal, 0.2, 1), 0.3)
  assert.equal(stepNumber(decimal, 0.3, -1), 0.2)
  assert.equal(stepNumber(decimal, null, 1), 0.1)
  assert.equal(stepNumber({ ...decimal, max: 0.95 }, 0.9, 1), 0.95)
})

test('CPU steps preserve a strictly positive value at the exclusive zero boundary', () => {
  const cpu = { ...decimal, min: 0, minExclusive: true }
  assert.equal(stepNumber(cpu, 0.1, -1), 0.1)
  assert.equal(stepNumber(cpu, 0.05, -1), 0.05)
  assert.equal(stepNumber(cpu, null, -1), 0.1)
  assert.equal(stepNumber(cpu, 0, 1), 0.1)
  assert.equal(stepNumber({ ...decimal, min: 0 }, 0.1, -1), 0)
})
