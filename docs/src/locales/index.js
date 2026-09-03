import ar from './ar.js';
import be from './be.js';
import de from './de.js';
import en from './en.js';
import es from './es.js';
import fr from './fr.js';
import it from './it.js';
import kk from './kk.js';
import pl from './pl.js';
import ru from './ru.js';
import tr from './tr.js';
import uk from './uk.js';
import uz from './uz.js';

export const localeOrder = ['fr', 'en', 'de', 'it', 'tr', 'es', 'ru', 'uk', 'ar', 'uz', 'kk', 'be', 'pl']; // Matches activity_welcome.xml Row1=FR/EN/DE Row2=IT/TR/ES Row3=RU/UK/AR Row4=UZ/KK/BE Row5=PL

export const locales = {
  ar,
  be,
  de,
  en,
  es,
  fr,
  it,
  kk,
  pl,
  ru,
  tr,
  uk,
  uz,
};

export const languages = localeOrder.map((code) => locales[code]);

// AUD-157 — membership test, not a bare index. `locales[code]` also reaches
// Object.prototype, so ?lang=constructor / __proto__ / toString / valueOf and friends
// used to return a truthy function or object, which the caller read as "this is a
// locale" and mounted the manual with. Only the thirteen codes we actually ship
// resolve; anything else falls back to the landing page, as 'zz' already did.
export function resolveLocale(code) {
  return localeOrder.includes(code) ? locales[code] : null;
}
