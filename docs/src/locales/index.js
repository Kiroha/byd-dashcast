import ar from './ar.js';
import be from './be.js';
import de from './de.js';
import en from './en.js';
import es from './es.js';
import fr from './fr.js';
import it from './it.js';
import kk from './kk.js';
import ru from './ru.js';
import tr from './tr.js';
import uk from './uk.js';
import uz from './uz.js';

export const localeOrder = ['fr', 'en', 'de', 'it', 'tr', 'es', 'ru', 'uk', 'ar', 'uz', 'kk', 'be']; // Matches activity_welcome.xml Row1=FR/EN/DE Row2=IT/TR/ES Row3=RU/UK/AR Row4=UZ/KK/BE

export const locales = {
  ar,
  be,
  de,
  en,
  es,
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
