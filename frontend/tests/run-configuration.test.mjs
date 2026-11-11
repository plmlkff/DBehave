import assert from 'node:assert/strict'
import test from 'node:test'
import { configurationSections, configurationValue } from '../src/pages/experiment-run/model/configuration.ts'

test('snapshot sections preserve actual settings, scenario values and effective resource limits', () => {
  const configuration = {
    pluginId: 'custom',
    scenarioId: 'load',
    settings: { 'extension.limit': 128, enabled: false },
    params: { rps: 250, distribution: 'uniform' },
    loaderImageContext: { core_max: 0.5, mem_max: 536870912 },
    databaseImageContext: { core_max: 2, mem_max: 1073741824 },
  }
  const sections = configurationSections(configuration)
  assert.deepEqual(sections.map(section => section.id), ['settings', 'params', 'loaderImageContext', 'databaseImageContext'])
  assert.deepEqual(sections[0].fields, [
    { key: 'extension.limit', title: 'extension.limit', value: '128' },
    { key: 'enabled', title: 'enabled', value: 'Нет' },
  ])
  assert.deepEqual(sections[1].fields, [
    { key: 'rps', title: 'rps', value: '250' },
    { key: 'distribution', title: 'distribution', value: 'uniform' },
  ])
  assert.deepEqual(sections[2].fields, [
    { key: 'core_max', title: 'CPU (ядра)', value: '0.5' },
    { key: 'mem_max', title: 'Память (байт)', value: '536870912' },
  ])
  assert.equal(configuration.settings.enabled, false)
})

test('configuration values distinguish false, zero, empty strings and missing limits', () => {
  assert.equal(configurationValue(false), 'Нет')
  assert.equal(configurationValue(true), 'Да')
  assert.equal(configurationValue(0), '0')
  assert.equal(configurationValue(''), 'Пустая строка')
  assert.equal(configurationValue(null), 'Не задано')
  assert.equal(configurationValue('line 1\nline 2'), 'line 1\nline 2')
})

test('empty snapshot sections and custom prototype-named parameters can be displayed safely', () => {
  const sections = configurationSections({
    settings: JSON.parse('{"__proto__":"user value"}'),
    params: {},
    loaderImageContext: {},
    databaseImageContext: {},
  })
  assert.deepEqual(sections[0].fields, [{ key: '__proto__', title: '__proto__', value: 'user value' }])
  assert.deepEqual(sections.slice(1).map(section => section.fields), [[], [], []])
})
