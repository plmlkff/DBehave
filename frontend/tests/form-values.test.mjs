import assert from 'node:assert/strict'
import test from 'node:test'
import { buildExperimentRequest, createInitialValues } from '../src/entities/experiment/model/form-values.ts'

function numericField(name, type, defaultValue, min, max = null, minExclusive = false) {
  return { name, title: name, description: '', type, defaultValue, min, max, minExclusive, unit: null, options: [] }
}

function screen() {
  return {
    schemaVersion: 1,
    screenId: 'new-experiment',
    scenarios: [],
    form: {
      pluginId: 'postgres',
      pluginTitle: 'PostgreSQL',
      scenarioId: 'mixed',
      description: '',
      sections: [
        { id: 'settings', fields: [numericField('settings.shared_buffers', 'INTEGER', 256, 16, 1024)] },
        { id: 'params', fields: [numericField('params.rps', 'INTEGER', 100, 1, 10000)] },
        { id: 'loaderImageContext', fields: [
          numericField('loaderImageContext.core_max', 'NUMBER', null, 0, null, true),
          numericField('loaderImageContext.mem_max', 'INTEGER', null, 1, 9223372036854775807),
        ] },
        { id: 'databaseImageContext', fields: [
          numericField('databaseImageContext.core_max', 'NUMBER', 2, 0, null, true),
          numericField('databaseImageContext.mem_max', 'INTEGER', 1073741824, 1, 9223372036854775807),
        ] },
      ],
    },
  }
}

test('initial values leave optional resource controls blank and preserve configured defaults', () => {
  assert.deepEqual(createInitialValues(screen()), {
    'settings.shared_buffers': 256,
    'params.rps': 100,
    'loaderImageContext.core_max': '',
    'loaderImageContext.mem_max': '',
    'databaseImageContext.core_max': 2,
    'databaseImageContext.mem_max': 1073741824,
  })
})

test('blank resource values are omitted for all representations of an empty input', () => {
  const form = screen()
  for (const blank of ['', '   ', null, undefined]) {
    const values = createInitialValues(form)
    for (const section of form.form.sections.slice(2)) {
      for (const field of section.fields) values[field.name] = blank
    }
    const { payload, errors } = buildExperimentRequest(form, values)
    assert.deepEqual(errors, {})
    assert.deepEqual(JSON.parse(JSON.stringify(payload)), {
      pluginId: 'postgres',
      scenarioId: 'mixed',
      settings: { shared_buffers: 256 },
      params: { rps: 100 },
      loaderImageContext: {},
      databaseImageContext: {},
    })
  }
})

test('serializes CPU fractions and integer byte limits into their own container contexts', () => {
  const form = screen()
  const { payload, errors } = buildExperimentRequest(form, {
    ...createInitialValues(form),
    'settings.shared_buffers': '128',
    'params.rps': '250',
    'loaderImageContext.core_max': '0.5',
    'loaderImageContext.mem_max': '536870912',
    'databaseImageContext.core_max': '1.25',
    'databaseImageContext.mem_max': '2147483648',
  })
  assert.deepEqual(errors, {})
  assert.deepEqual(payload, {
    pluginId: 'postgres',
    scenarioId: 'mixed',
    settings: { shared_buffers: 128 },
    params: { rps: 250 },
    loaderImageContext: { core_max: 0.5, mem_max: 536870912 },
    databaseImageContext: { core_max: 1.25, mem_max: 2147483648 },
  })
})

test('rejects zero, negative and nonnumeric CPU limits for either container', () => {
  const form = screen()
  for (const section of ['loaderImageContext', 'databaseImageContext']) {
    const field = `${section}.core_max`
    for (const value of [0, '0', -0.5, '-1', 'invalid', 'Infinity', Number.NaN, true]) {
      const { errors } = buildExperimentRequest(form, { ...createInitialValues(form), [field]: value })
      assert.ok(errors[field], `${field} must reject ${String(value)}`)
    }
  }
})

test('rejects fractional, nonpositive and unsafe integer memory limits', () => {
  const form = screen()
  for (const section of ['loaderImageContext', 'databaseImageContext']) {
    const field = `${section}.mem_max`
    for (const value of [0, -1, 1.5, '1.5', 9007199254740992, '9007199254740993', false]) {
      const { errors } = buildExperimentRequest(form, { ...createInitialValues(form), [field]: value })
      assert.ok(errors[field], `${field} must reject ${String(value)}`)
    }
    const { payload, errors } = buildExperimentRequest(form, { ...createInitialValues(form), [field]: '1' })
    assert.deepEqual(errors, {})
    assert.equal(payload[section].mem_max, 1)
  }
})

test('uses inclusive bounds for fields without an exclusive minimum', () => {
  const form = screen()
  const field = form.form.sections[2].fields[0]
  delete field.minExclusive
  const { payload, errors } = buildExperimentRequest(form, {
    ...createInitialValues(form),
    'loaderImageContext.core_max': 0,
    'settings.shared_buffers': 16,
  })
  assert.deepEqual(errors, {})
  assert.equal(payload.loaderImageContext.core_max, 0)
  assert.equal(payload.settings.shared_buffers, 16)
})

test('only declared fields enter the request and dotted setting keys remain intact', () => {
  const form = screen()
  form.form.sections[0].fields.push(numericField('settings.extension.limit', 'INTEGER', 4, 1, 16))
  const { payload, errors } = buildExperimentRequest(form, {
    ...createInitialValues(form),
    'settings.extension.limit': '8',
    'settings.undeclared': 123,
    'params.undeclared': 456,
    'loaderImageContext.command': 'untrusted command',
    'databaseImageContext.image': 'untrusted:image',
  })
  assert.deepEqual(errors, {})
  assert.deepEqual(payload.settings, { shared_buffers: 256, 'extension.limit': 8 })
  assert.deepEqual(payload.params, { rps: 100 })
  assert.deepEqual(payload.loaderImageContext, {})
  assert.deepEqual(payload.databaseImageContext, { core_max: 2, mem_max: 1073741824 })
})

test('preserves an explicitly declared __proto__ field without changing object prototypes', () => {
  const form = screen()
  form.form.sections[0].fields.push({
    name: 'settings.__proto__', title: 'Custom setting', description: '', type: 'STRING',
    defaultValue: 'default', min: null, max: null, unit: null, options: [],
  })
  const originalPrototype = Object.getPrototypeOf({})
  const originalProperties = Object.getOwnPropertyDescriptors(originalPrototype)
  const { payload, errors } = buildExperimentRequest(form, {
    ...createInitialValues(form),
    'settings.__proto__': 'user override',
  })

  assert.deepEqual(errors, {})
  assert.equal(Object.hasOwn(payload.settings, '__proto__'), true)
  assert.equal(payload.settings.__proto__, 'user override')
  assert.equal(JSON.parse(JSON.stringify(payload)).settings.__proto__, 'user override')
  assert.equal(Object.getPrototypeOf(payload.settings), originalPrototype)
  assert.deepEqual(Object.getOwnPropertyDescriptors(originalPrototype), originalProperties)
})

test('blank or null required scenario inputs still produce validation errors', () => {
  const form = screen()
  for (const blank of ['', '   ', null, undefined]) {
    const { errors } = buildExperimentRequest(form, {
      ...createInitialValues(form),
      'settings.shared_buffers': blank,
      'params.rps': blank,
    })
    assert.ok(errors['settings.shared_buffers'])
    assert.ok(errors['params.rps'])
  }
})

test('preserves boolean, enum and string scenario input validation', () => {
  const form = screen()
  form.form.sections[1].fields.push(
    { ...numericField('params.enabled', 'BOOLEAN', false, null), options: [] },
    { ...numericField('params.mode', 'ENUM', 'uniform', null), options: ['uniform', 'hotspot'] },
    { ...numericField('params.label', 'STRING', '', null), options: [] },
  )
  const values = createInitialValues(form)
  const valid = buildExperimentRequest(form, { ...values, 'params.enabled': true, 'params.mode': 'hotspot', 'params.label': 'trial' })
  assert.deepEqual(valid.errors, {})
  assert.deepEqual(valid.payload.params, { rps: 100, enabled: true, mode: 'hotspot', label: 'trial' })
  const invalid = buildExperimentRequest(form, { ...values, 'params.enabled': 'true', 'params.mode': 'missing', 'params.label': null })
  assert.deepEqual(Object.keys(invalid.errors).sort(), ['params.enabled', 'params.label', 'params.mode'])
})
