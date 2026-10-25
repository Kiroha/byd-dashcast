// Resolve only explicitly registered languages, including the Brazilian regional tag.
import { resolveLocale } from './index.js';

export function readLocaleFromLocation(location) {
  const hashCode = location.hash.match(/^#\/([a-z]{2}(?:-[A-Z]{2})?)(?:\/|$)/)?.[1];
  const queryCode = new URLSearchParams(location.search).get('lang');
  const code = hashCode || queryCode || '';
  return resolveLocale(code) ? code : '';
}

export function readSectionFromLocation(location) {
  return location.hash.match(/^#\/[a-z]{2}(?:-[A-Z]{2})?\/(s[1-9])\b/)?.[1] || '';
}
