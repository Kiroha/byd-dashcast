import be from './be.js';
import de from './de.js';
import en from './en.js';
import fr from './fr.js';
import it from './it.js';
import kk from './kk.js';
import ru from './ru.js';
import tr from './tr.js';
import uk from './uk.js';
import uz from './uz.js';

export const localeOrder = ['fr', 'en', 'de', 'tr', 'it', 'ru', 'uz', 'kk', 'be', 'uk'];

export const locales = {
  be,
  de,
  en,
  fr,
  it,
  kk,
  ru,
  tr,
  uk,
  uz,
};

export const languages = localeOrder.map((code) => locales[code]);

export function resolveLocale(code) {
  return locales[code] || null;
}
