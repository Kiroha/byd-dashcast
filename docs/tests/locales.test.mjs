import assert from 'node:assert/strict';
import { test } from 'node:test';
import { languages, locales, localeOrder, resolveLocale } from '../src/locales/index.js';
import { readLocaleFromLocation, readSectionFromLocation } from '../src/locales/navigation.js';

function shape(value) {
  if (Array.isArray(value)) return value.map(shape);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, shape(value[key])]));
  }
  return typeof value;
}

test('Brazilian Portuguese exposes the complete manual and a distinct regional identity', () => {
  const portuguese = resolveLocale('pt-BR');
  assert.equal(portuguese.code, 'pt-BR');
  assert.equal(portuguese.name, 'Português (Brasil)');
  assert.equal(portuguese.flag, '🇧🇷');
  assert.deepEqual(shape(portuguese), shape(locales.en));
  assert.equal(new Set(localeOrder).size, 14);
  assert.deepEqual(languages.map((language) => language.code), localeOrder);
});

test('regional manual links survive reload, query selection and section navigation', () => {
  assert.equal(readLocaleFromLocation({ hash: '#/pt-BR', search: '' }), 'pt-BR');
  assert.equal(readLocaleFromLocation({ hash: '', search: '?lang=pt-BR' }), 'pt-BR');
  assert.equal(readLocaleFromLocation({ hash: '#/pt-BR/s3', search: '?lang=fr' }), 'pt-BR');
  assert.equal(readSectionFromLocation({ hash: '#/pt-BR/s3' }), 's3');
  for (const code of localeOrder) {
    assert.equal(readLocaleFromLocation({ hash: `#/${code}/s2`, search: '' }), code);
    assert.equal(readSectionFromLocation({ hash: `#/${code}/s2` }), 's2');
  }
});

test('unknown and inherited locale names never resolve to a manual', () => {
  for (const code of ['pt', 'pt-PT', 'constructor', '__proto__', 'toString', 'zz', '../en']) {
    assert.equal(resolveLocale(code), null);
    assert.equal(readLocaleFromLocation({ hash: '', search: `?lang=${code}` }), '');
    assert.equal(readLocaleFromLocation({ hash: `#/${code}`, search: '' }), '');
  }
  assert.equal(readSectionFromLocation({ hash: '#/pt-BR/s10' }), '');
});
