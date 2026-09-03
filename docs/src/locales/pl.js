export default {
  code: 'pl',
  flag: '🇵🇱',
  name: 'Polski',
  title: 'DashCast — Instrukcja obsługi',
  manualName: 'Instrukcja obsługi',
  meta: 'BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Spis treści',

  intro: {
    title: '0. Wprowadzenie',
    lead:
      'DashCast wyświetla dowolną aplikację Android z centralnego ekranu Twojego BYD na zestawie wskaźników (cyfrowym klastrze za kierownicą). Maps, Waze, Spotify lub ABRP tuż przed Tobą — a w trybie Layouts kilka aplikacji naraz, każda w swojej strefie. Instaluje się jak zwykła aplikacja i niczego nie zmienia w systemie.',
    bullets: [
      '✅ Działa na DiLink 3 (Seal EU / 6125F) i DiLink 5 (nowsze jednostki BYD).',
      '✅ Bez modyfikacji systemu: DashCast instaluje się jak każda inna aplikacja.',
      '✅ Lokalne ADB po TCP — po pierwszej autoryzacji komputer nie jest potrzebny.',
      '✅ 13 języków interfejsu, wybieranych przy pierwszym uruchomieniu, zmienialnych w każdej chwili.',
      '✅ Dotykowe lustro w czasie rzeczywistym: steruj klastrem z centralnego ekranu.',
      '✅ Tryb Layouts: kilka aplikacji obok siebie na klastrze, strefy rysowane palcem.',
      '✅ Autostart: projekcja + aplikacja (lub ulubiony layout) zaraz po otwarciu DashCast.',
      '✅ Marginesy (overscan) zapamiętywane dla każdej aplikacji.',
      '✅ Strzałki nawigacji zakręt po zakręcie na szybowym HUD DiLink 3 (obsługiwany firmware).',
      '✅ Wbudowany asystent hotspotu Wi-Fi dla DiLink 3 (użyj własnej karty SIM).',
      '✅ Zgłaszanie błędów bez klawiatury, jednym dotknięciem wysyłasz diagnostykę do wsparcia.',
      '✅ Automatyczne aktualizacje OTA, które instalują się po cichu i ponownie uruchamiają aplikację.',
    ],
    note:
      '💡 Jedyny wymóg: włączyć bezprzewodowe debugowanie ADB w Ustawieniach BYD → Opcje programisty. Przy pierwszym uruchomieniu pojawi się okno „Zezwolić na debugowanie?” — zaznacz „Zawsze zezwalaj z tego komputera” i potwierdź. Nigdy nie trzeba tego powtarzać.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Ekran powitalny — wybór języka',
      lead:
        'Przy pierwszym uruchomieniu DashCast pokazuje siatkę z 13 dostępnymi językami. Dotknij swojego: wybór jest zapamiętywany i ekran już się nie pojawi. Język można zmienić w każdej chwili w Ustawieniach.',
      mockupLabel: 'Zobacz ekran 1 (Powitanie)',
      featuresTitle: 'Szczegóły',
      features: [
        {
          title: '13 obsługiwanych języków',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Wybrany język jest stosowany natychmiast, bez restartu.",
        },
        {
          title: 'Automatyczny kierunek czytania',
          text:
            'Arabski automatycznie przełącza się na układ od prawej do lewej (RTL): pasek nawigacji przechodzi na prawo, a listy są odbijane lustrzanie.',
        },
        {
          title: 'Zmiana w każdej chwili',
          text: 'Aby zmienić język później: Ustawienia → Język. Stosowane od razu.',
        },
      ],
      howTo: {
        title: 'Jak to zrobić',
        steps: [
          'Uruchom DashCast (niebieska ikona w szufladzie aplikacji BYD).',
          'Pojawi się ekran powitalny z siatką języków.',
          'Dotknij swojego języka. Interfejs przełącza się natychmiast.',
          'Otwiera się ekran główny — gotowe.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Ekran główny — Aplikacje i Klaster',
      lead:
        'Ekran startowy DashCast. Po lewej: wszystkie aplikacje z wyszukiwaniem, filtrami i ulubionymi oraz boczny pasek nawigacji. Po prawej: podgląd klastra w czasie rzeczywistym, przyciski Lustro na pełnym ekranie / Zatrzymaj projekcję oraz — w trybie Layouts — zwijany selektor „Układ klastra”.',
      mockupLabel: 'Zobacz ekran 2 (Główny)',
      featuresTitle: 'Wszystko, co możesz zrobić',
      features: [
        {
          title: '👆 Krótkie dotknięcie — projekcja',
          text:
            'Dotknij aplikacji, aby wysłać ją na klaster. Jeśli projekcja nie jest aktywna, uruchamia się automatycznie (~2 s rozgrzewki), a aplikacja pojawia się za kierownicą.',
        },
        {
          title: '👆⏱️ Długie przytrzymanie — menu akcji',
          text:
            'Przytrzymaj aplikację: ⭐ Ulubiona, Auto-launch (projekcja tej aplikacji przy każdym starcie DashCast), Przenieś na klaster / ekran główny, ✕ Wymuś zatrzymanie.',
        },
        {
          title: '🔍 Wyszukiwanie i filtry',
          text:
            'Pasek wyszukiwania filtruje podczas pisania (nazwa lub pakiet). Chipy kategorii (Wszystkie / Nawigacja / Multimedia…) grupują aplikacje; przycisk ▦ przełącza listę/siatkę.',
        },
        {
          title: '🚦 Podgląd klastra w czasie rzeczywistym',
          text:
            'Prawy panel odzwierciedla na żywo to, co jest na zestawie wskaźników. Twoje dotknięcia podglądu są przekazywane do projektowanej aplikacji — przewijanie, zoom, klawiatura, wszystko działa.',
        },
        {
          title: '👁️ Lustro na pełnym ekranie',
          text:
            'Rozszerza podgląd na cały centralny ekran: idealne do wpisania adresu w Maps z pełną klawiaturą. Wszystko jest replikowane na klastrze w czasie rzeczywistym.',
        },
        {
          title: '⏹ Zatrzymaj projekcję',
          text:
            'Czysto kończy projekcję i przywraca oryginalny pulpit BYD (prędkość, wskaźniki, ADAS) z rozmiarem ekranu ustawionym w Ustawieniach.',
        },
        {
          title: '🗂️ Selektor układu klastra (domyślnie zwinięty)',
          text:
            'W trybie Layouts pod przyciskami znajduje się kompaktowy nagłówek „UKŁAD KLASTRA”. Dotknij go, aby rozwinąć: „Uruchom aplikacje layoutu” oraz karta dla każdego zapisanego layoutu (Tryb wolny / Twoje presety / ＋ Zarządzaj). Domyślnie jest zwinięty, aby podgląd na żywo zachował pełną wysokość.',
        },
        {
          title: '📺 Pływający przycisk',
          text:
            'Przycisk 📺 pozostaje nad innymi aplikacjami: dotknięcie = otwórz lustro, przytrzymanie = szybkie przełączanie między ostatnio projektowanymi aplikacjami.',
        },
        {
          title: '🧭 Boczny pasek nawigacji',
          text:
            'Szybki dostęp do Aplikacji, Ustawień, Systemu, Dziennika, zgłaszania błędów oraz — na DiLink 3 z własną kartą SIM — asystenta hotspotu.',
        },
      ],
      howTo: {
        title: 'Jak wyświetlić aplikację na klastrze',
        steps: [
          'Znajdź żądaną aplikację (np. Maps) — wyszukiwanie lub filtry w razie potrzeby.',
          'Dotknij jej ikony → projekcja startuje, a klaster przełącza się na aplikację w ~2 s.',
          'Prawy podgląd pokazuje na żywo zawartość klastra.',
          'Aby wpisać tekst: „Lustro na pełnym ekranie” → wpisz adres → wszystko jest replikowane.',
          'Aby zatrzymać: „Zatrzymaj projekcję” — klaster wraca do natywnego BYD.',
        ],
      },
      tipsTitle: 'Wskazówki',
      tips: [
        '💡 Auto-launch: wybierz jedną aplikację (przytrzymanie → Auto-launch), aby projektowała się automatycznie przy każdym starcie DashCast — projekcja aktywuje się sama.',
        '💡 Ulubiony layout: karta wybrana w selektorze „Układ klastra” to ta, którą aktywuje autostart (zobacz sekcję Layouts).',
        '💡 Marginesy: jeśli aplikacja wychodzi poza klaster, Ustawienia → Marginesy, suwaki poziomy/pionowy. Zapamiętane per aplikacja.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ustawienia',
      lead:
        'Opcje globalne: rozmiar klastra, język, marginesy, zachowanie przy starcie, tryb Layouts i aktualizacje. Boczny pasek pozostaje dostępny — przełączaj ekrany bez utraty pozycji.',
      mockupLabel: 'Zobacz ekran 3 (Ustawienia)',
      featuresTitle: 'Główne sekcje',
      features: [
        {
          title: '📺 Typ klastra',
          text:
            'Fizyczny rozmiar zestawu wskaźników: 8.8″, 12.3″ (zalecany w Seal EU — naprawia rozciąganie ADAS) lub 10.25″. Używany przez „Zatrzymaj projekcję” do przywrócenia właściwego trybu.',
        },
        {
          title: '↔️↕️ Marginesy (overscan)',
          text:
            'Suwaki poziomy/pionowy (0–200 px) kompensujące ucięte krawędzie. Zapamiętane per aplikacja: Maps może mieć 80 px, a Spotify pozostaje na 0. „Zastosuj” dostosowuje projekcję na żywo.',
        },
        {
          title: '🚗 Start z pojazdem',
          text:
            'Jeśli włączone, DashCast startuje z samochodem i przywraca ostatnio projektowaną aplikację (lub ulubiony layout). W przeciwnym razie uruchom go z szuflady BYD.',
        },
        {
          title: '🗂️ Tryb Layouts',
          text:
            'Włącza projekcję wieloaplikacyjną z niestandardowymi strefami (wymaga Proxy ADB Daemon, zarządzanego automatycznie). Pokazuje selektor „Układ klastra” na ekranie głównym oraz kartę Layouts.',
        },
        {
          title: '⭐ Automatyczny ulubiony layout',
          text:
            'Przy starcie DashCast: aktywuje projekcję klastra, ulubiony layout, a następnie uruchamia aplikacje powiązane z każdą strefą. Pełna konfiguracja multi-app bez jednego dotknięcia.',
        },
        {
          title: '⚡ Wstępne tworzenie slotów przy starcie',
          text:
            'Przygotowuje wirtualne ekrany ulubionego layoutu zaraz po otwarciu DashCast (bez uruchamiania aplikacji) — aktywacja layoutu jest potem niemal natychmiastowa.',
        },
        {
          title: '📶 Użyj własnej karty SIM (DiLink 3)',
          text:
            'Decyduje, czy asystent hotspotu pojawia się w bocznym pasku nawigacji. Zostaw włączone, jeśli udostępniasz samochodowi internet przez własny telefon/dane SIM. Zobacz sekcję Hotspot.',
        },
        {
          title: '📦 Aktualizacje OTA',
          text:
            'DashCast sprawdza GitHub pod kątem nowych wersji przy każdym starcie. Aktualizacje instalują się teraz po cichu i same ponownie uruchamiają aplikację (zobacz sekcję Aktualizacje). Zaznacz „Uwzględnij wersje wstępne” dla kanału beta.',
        },
        {
          title: '🌐 Język',
          text: '13 języków — zmiana jest natychmiastowa.',
        },
      ],
      howTo: {
        title: 'Jak dostosować marginesy aplikacji',
        steps: [
          'Wyświetl aplikację do regulacji (np. Waze).',
          'Ustawienia → Marginesy.',
          'Przesuwaj suwak poziomy, aż lewa/prawa krawędź będą poprawne.',
          'To samo w pionie, potem „Zastosuj” — regulacja na żywo, bez restartu aplikacji.',
          'Ustawienie jest zapisywane tylko dla tej aplikacji.',
        ],
      },
      note:
        '⚠️ Po zmianie typu klastra zatrzymaj i ponownie uruchom projekcję, aby przywracanie używało właściwego trybu.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layouts — kilka aplikacji na klastrze',
      lead:
        'Tryb Layouts dzieli klaster na niestandardowe strefy, każda z własną aplikacją: na przykład Waze po lewej, Spotify po prawej. Rysujesz strefy palcem, wiążesz aplikację z każdą strefą, a layout aktywuje się jednym dotknięciem — lub sam przy starcie.',
      mockupLabel: 'Zobacz ekran 7 (Layouts)',
      featuresTitle: 'Funkcje',
      features: [
        {
          title: '✏️ Rysowanie strefy',
          text:
            'Na kanwie (replice klastra) przeciągnij palcem, aby narysować prostokąt. Otwiera się okno: nazwa, pozycja/wymiary co do piksela oraz aplikacja do powiązania.',
        },
        {
          title: '🔗 Wiązanie aplikacji',
          text:
            'Każdą strefę można powiązać z aplikacją: przy aktywacji layoutu aplikacja uruchamia się automatycznie w swojej strefie. Strefa bez aplikacji pozostaje wolna — umieść tam coś później.',
        },
        {
          title: '✋ Przenoszenie i zmiana rozmiaru',
          text:
            'Przeciągnij strefę, aby ją przesunąć; białe uchwyty w rogach zmieniają rozmiar. Krawędzie przyciągają się automatycznie do granic klastra i sąsiednich stref.',
        },
        {
          title: '✏️ Edycja istniejącej strefy',
          text:
            'Dotknij strefy (na kanwie lub jej chipa poniżej): zmień nazwę, dostosuj geometrię, zmień powiązaną aplikację lub usuń ją. Przytrzymanie strefy = szybkie usunięcie.',
        },
        {
          title: '💾 Zapisane layouty',
          text:
            'Zapisz dowolną liczbę layoutów („Nav+Media”, „Potrójny ekran”…). Panel boczny wyświetla je z opcjami Aktywuj / Dezaktywuj / Edytuj / Usuń.',
        },
        {
          title: '⭐ Ulubiony i autostart',
          text:
            'Przycisk „Ulubiony” (lub dotknięcie karty selektora na ekranie głównym) wyznacza layout, który „Automatyczny ulubiony layout” aktywuje przy starcie DashCast — wraz z projekcją.',
        },
      ],
      howTo: {
        title: 'Utwórz swój pierwszy layout',
        steps: [
          'Włącz „Tryb Layouts” w Ustawieniach.',
          'Otwórz kartę Layouts (boczny pasek).',
          'Przeciągnij palcem po kanwie, aby narysować pierwszą strefę (np. lewa połowa).',
          'W oknie: nazwij ją, dotknij „Powiąż aplikację” → wybierz Waze → Dodaj.',
          'Narysuj drugą strefę (prawa połowa), powiąż Spotify.',
          '„Zapisz” → nazwij layout (np. Nav+Media).',
          '„Ulubiony”, aby go wybrać, potem aktywuj: obie aplikacje startują, każda w swojej strefie.',
        ],
      },
      tipsTitle: 'Wskazówki',
      tips: [
        '💡 W połączeniu z „Automatycznym ulubionym layoutem” (Ustawienia) pełna konfiguracja multi-app buduje się sama przy każdym starcie DashCast.',
        '💡 Mini-podgląd na każdej karcie selektora pokazuje rzeczywiste strefy layoutu — rozpoznawalny na pierwszy rzut oka.',
        '💡 Aplikacja nie chce się wyświetlić w swojej strefie? Niektóre wymuszają swoje proporcje; spróbuj strefy bliższej 16:9.',
      ],
      note:
        'ℹ️ Tryb Layouts opiera się na Proxy ADB Daemon (uruchamianym automatycznie). Pierwszy zimny start: odczekaj 6–8 s, zanim aplikacje się pojawią — to sekwencja aktywacji klastra.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. Strzałki nawigacji na HUD (DiLink 3)',
      lead:
        'W samochodach DiLink 3, których szybowy wyświetlacz HUD obsługuje strzałki skrętu, DashCast może rysować na HUD prowadzenie zakręt po zakręcie z Twojej aplikacji nawigacyjnej — strzałkę manewru i odległość do niego, prosto na szybie.',
      mockupLabel: 'Zobacz ekran 2 (Główny)',
      featuresTitle: 'Jak to działa',
      features: [
        {
          title: '🧭 Prowadzenie z Maps / Waze',
          text:
            'DashCast odczytuje powiadomienie zakręt po zakręcie, które Twoja aplikacja nawigacyjna już publikuje (Google Maps, Waze), i przekazuje manewr + odległość do HUD przez magistralę CAN samochodu. Nie jest potrzebna żadna dodatkowa aplikacja.',
        },
        {
          title: '🚗 Zależne od firmware',
          text:
            'Tylko nowsze wersje firmware HUD DiLink 3 potrafią rysować strzałki. Jeśli Twoja nie potrafi, strzałki po prostu się nie pojawią — DashCast nie może dodać funkcji, której sprzęt HUD nie posiada.',
        },
        {
          title: '➡️ Poprawne symbole kierunku',
          text:
            'Manewry prosto / w lewo / w prawo są mapowane na odpowiedni symbol HUD, z odliczaną na żywo odległością do zakrętu.',
        },
      ],
      howTo: {
        title: 'Jak uzyskać strzałki na HUD',
        steps: [
          'Upewnij się, że DashCast ma dostęp do powiadomień (prosi o to przy pierwszym użyciu).',
          'Włącz szybowy HUD i ustaw go w tryb wyświetlania nawigacji w menu HUD BYD.',
          'Rozpocznij trasę w Google Maps lub Waze.',
          'Strzałka manewru i odległość pojawiają się na HUD w miarę zbliżania się do każdego zakrętu.',
        ],
      },
      note:
        'ℹ️ Strzałki HUD to funkcja DiLink 3 i zależą od firmware Twojego HUD. Jeśli nic się nie pojawia, Twój HUD może być starszy niż obsługa strzałek — to ograniczenie sprzętowe, nie błąd DashCast.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Asystent hotspotu Wi-Fi (DiLink 3)',
      lead:
        'Na DiLink 3, jeśli łączysz samochód z internetem przez własną kartę SIM/telefon, asystent hotspotu utrzymuje to połączenie przy życiu, aby nawigacja i streaming działały dalej. Pojawia się w bocznym pasku nawigacji tylko wtedy, gdy jest dla Ciebie istotny.',
      mockupLabel: 'Zobacz ekran 3 (Ustawienia)',
      featuresTitle: 'Funkcje',
      features: [
        {
          title: '📶 Podtrzymywanie połączenia',
          text:
            'Ponownie uzbraja udostępnianie Wi-Fi, gdy samochód się wybudza (np. po włączeniu ACC), abyś nie musiał włączać go ręcznie przy każdej jeździe.',
        },
        {
          title: '👁️ Stan na żywo',
          text:
            'Pokazuje, czy hotspot jest aktywny i ilu klientów jest podłączonych, abyś mógł potwierdzić, że samochód rzeczywiście jest online.',
        },
        {
          title: '⚙️ Pokazywany tylko gdy przydatny',
          text:
            'Pozycja Hotspot pojawia się tylko na DiLink 3 i tylko wtedy, gdy w Ustawieniach włączone jest „Użyj własnej karty SIM”. W innych konfiguracjach pozostaje ukryta.',
        },
      ],
      howTo: {
        title: 'Jak go używać',
        steps: [
          'Ustawienia → upewnij się, że „Użyj własnej karty SIM” jest włączone.',
          'Otwórz „Hotspot” z bocznego paska nawigacji.',
          'Uruchom / potwierdź udostępnianie — stan pokazuje, że jest aktywne.',
          'Uzbraja się samo przy następnym wybudzeniu samochodu.',
        ],
      },
      note:
        'ℹ️ Ten asystent jest dla samochodów DiLink 3 połączonych przez własne dane. Jeśli Twój samochód ma własny wbudowany pakiet danych, nie potrzebujesz go.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Zgłoś problem (zgłaszanie błędów)',
      lead:
        'Zgłaszanie błędów w samochodzie, bez klawiatury. W trzech dotknięciach wybierasz, co poszło nie tak; DashCast przechwytuje ograniczoną migawkę diagnostyczną (dzienniki + stan systemu) i wysyła ją prosto na kanał wsparcia — bez pisania, bez kabli.',
      mockupLabel: 'Zobacz ekran 6 (Zgłoszenie)',
      featuresTitle: 'Jak to działa',
      features: [
        {
          title: '1️⃣ Kategoria',
          text:
            'Wybierz, którego obszaru dotyczy problem: lustro, aplikacja, dźwięk, połączenie, zawieszenie, HUD… Sześć dużych kafelków, tylko dotknięcie.',
        },
        {
          title: '2️⃣ Aplikacja',
          text:
            'DashCast automatycznie wykrywa aplikację aktualnie na klastrze i proponuje ją, plus „Brak konkretnej aplikacji” oraz „Inna”.',
        },
        {
          title: '3️⃣ Problem',
          text:
            'Wybierz najbliższy objaw z krótkiej listy. Opcjonalne pole tekstowe pozwala dodać szczegóły, jeśli chcesz — ale nigdy nie jest wymagane.',
        },
        {
          title: '📎 Automatyczna diagnostyka',
          text:
            'Zgłoszenie łączy ostatnie dzienniki oraz stan systemu/klastra w momencie problemu — dokładnie to, czego potrzebuje wsparcie, przechwycone za Ciebie.',
        },
        {
          title: '🚀 Wysyłka jednym dotknięciem',
          text:
            'Jeśli skonfigurowano kanał wsparcia, zgłoszenie jest przesyłane bezpośrednio; w przeciwnym razie DashCast otwiera menu udostępniania Androida, abyś mógł wysłać je przez Telegram, e-mail lub GitHub.',
        },
        {
          title: '📺 Z dowolnego miejsca',
          text:
            'Pływający przycisk 📺 oraz boczny pasek nawigacji otwierają zgłaszanie błędów, więc możesz złożyć zgłoszenie nawet wtedy, gdy projektowana jest inna aplikacja.',
        },
      ],
      howTo: {
        title: 'Jak wysłać zgłoszenie',
        steps: [
          'Otwórz zgłaszanie błędów (boczny pasek nawigacji lub pływający przycisk).',
          'Dotknij kategorii pasującej do problemu.',
          'Potwierdź aplikację (lub wybierz „Brak konkretnej aplikacji”).',
          'Wybierz najbliższy problem; dodaj notatkę, jeśli przydatna.',
          'Dotknij Wyślij — diagnostyka trafia do wsparcia automatycznie.',
        ],
      },
      note:
        '🔒 Przed wysłaniem DashCast usuwa numer nadwozia, nazwy sieci Wi-Fi, adresy sprzętowe i położenia. Zostają dziennik DashCast i dziennik systemu Android, kopiowane bez zmian — zawierają to, co zapisały inne aplikacje. Pytamy raz przed jakimkolwiek wysłaniem, a jeśli odmówisz, nic nie opuszcza samochodu.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Raport systemowy',
      lead:
        'Panel tylko do odczytu: wersje, wykryte ekrany i stan usług DashCast na żywo. Pierwszy ekran do sprawdzenia, gdy coś wygląda nieprawidłowo.',
      mockupLabel: 'Zobacz ekran 5 (System)',
      featuresTitle: 'Wyświetlane informacje',
      features: [
        {
          title: '🖥️ Ekrany',
          text:
            'Ekran główny (rozdzielczość, gęstość) i wirtualny ekran klastra ze stanem w czasie rzeczywistym.',
        },
        {
          title: '⚙️ Usługi',
          text:
            'ClusterService (projekcja), MirrorDaemon (lustro), Proxy ADB Daemon (operacje uprzywilejowane), AdbLocalClient (tunel ADB) — każda z zieloną/czerwoną kropką i przyciskiem restartu, gdy zatrzymana.',
        },
        {
          title: '📱 Wersje',
          text:
            'Zainstalowana wersja DashCast, firmware BYD, wersja Android/API, identyfikatory buildu DiLink.',
        },
        {
          title: '🔁 Replay projekcji',
          text:
            'Przycisk do ponownego odtworzenia pełnej sekwencji aktywacji klastra (przydatny, gdy zestaw wskaźników utknął w stanie pośrednim).',
        },
      ],
      tipsTitle: 'Wskazówki',
      tips: [
        '💡 „Proxy ADB Daemon” musi być zielony (RUN) dla trybu Layouts — w przeciwnym razie dotknij jego wiersza, aby go zrestartować.',
        '💡 Jeśli coś wygląda nieprawidłowo, sprawdź najpierw ten ekran, a następnie wyślij zgłoszenie przez zgłaszanie błędów.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Dziennik',
      lead:
        'Wewnętrzny dziennik DashCast: każde ważne działanie (projekcje, przywracanie, błędy ADB, aktualizacje) jest stale rejestrowane. Przydatny do zrozumienia nieoczekiwanego zachowania; to także dane, które zgłaszanie błędów dołącza za Ciebie.',
      mockupLabel: 'Zobacz ekran 6 (Dziennik)',
      featuresTitle: 'Funkcje',
      features: [
        {
          title: '🔍 Filtry',
          text:
            'Filtruj według poziomu (DEBUG / INFO / WARN / ERROR) lub słowa kluczowego (np. „ADB”, „Maps”, „error”).',
        },
        {
          title: '🎨 Kod kolorów',
          text:
            '🟢 INFO — normalna operacja. 🟠 WARN — uwaga. 🔴 ERROR — niepowodzenie. ⚪ DEBUG — szczegół techniczny.',
        },
        {
          title: '📤 Udostępnij',
          text:
            'Eksportuje dziennik jako .txt i otwiera menu udostępniania Androida. Zawiera wersję DashCast i model BYD.',
        },
        {
          title: '⏰ Znaczniki czasu',
          text:
            'Każdy wiersz jest poprzedzony czasem lokalnym (HH:mm:ss.mmm); długie operacje są mierzone.',
        },
      ],
      howTo: {
        title: 'Preferuj zgłaszanie błędów',
        steps: [
          'W przypadku większości problemów użyj zgłaszania błędów (sekcja 7) — automatycznie przechwytuje dziennik oraz stan systemu.',
          'Ekran Dziennik jest tu, gdy chcesz sam odczytać ślad lub udostępnić tylko surowy dziennik.',
        ],
      },
      note:
        '🔒 Dziennik zapisuje, co robi DashCast, w tym nazwy pakietów i wynik wykonywanych poleceń. Udostępnienie go z tego ekranu wysyła go bez zmian — filtrowanie usuwające numer nadwozia, nazwy sieci i położenia działa przy zgłoszeniach błędów, nie tutaj.',
    },
  ],

  faq: {
    title: '10. FAQ — Najczęstsze pytania',
    items: [
      {
        question: '❓ Klaster pozostaje czarny, gdy dotykam aplikacji',
        answer:
          'Trzy możliwe przyczyny: (1) bezprzewodowe ADB wyłączone — sprawdź Ustawienia BYD → Opcje programisty. (2) Zatrzymana usługa — ekran System, zrestartuj czerwony wiersz. (3) Aplikacja właśnie się zawiesiła — dotknij ponownie jej ikony. Nadal nie działa? Wyślij zgłoszenie przez zgłaszanie błędów.',
      },
      {
        question: '❓ Obraz wychodzi poza klaster / jest przycięty',
        answer:
          'Ustawienia → Marginesy: reguluj suwaki poziomy/pionowy, aż krawędzie będą poprawne. Zapamiętane per aplikacja — zrobisz to tylko raz.',
      },
      {
        question: '❓ Jak wrócić do oryginalnego pulpitu BYD?',
        answer:
          'Dotknij „Zatrzymaj projekcję” na ekranie głównym: DashCast przywraca natywny klaster z rozmiarem ustawionym w Ustawieniach. Jeśli wyświetlacz wygląda na zablokowany, ekran System → „Replay projekcji”, potem zatrzymaj ponownie.',
      },
      {
        question: '❓ Mój ulubiony layout nie startuje przy uruchomieniu',
        answer:
          'Sprawdź trzy warunki w Ustawieniach: „Tryb Layouts” włączony, „Automatyczny ulubiony layout” włączony i layout oznaczony ⭐ jako ulubiony (selektor na ekranie głównym lub przycisk Ulubiony w karcie Layouts). Przy zimnym starcie odczekaj 6–8 s.',
      },
      {
        question: '❓ Brak strzałek nawigacji na moim HUD',
        answer:
          'Strzałki HUD to funkcja DiLink 3 i wymagają firmware HUD, który potrafi je rysować. Upewnij się, że przyznano dostęp do powiadomień, HUD jest włączony w trybie wyświetlania nawigacji, a trasa jest uruchomiona w Maps/Waze. Jeśli nic się nie pojawia, firmware Twojego HUD jest prawdopodobnie starszy niż obsługa strzałek — to ograniczenie sprzętowe, nie błąd.',
      },
      {
        question: '❓ Czy potrzebuję asystenta hotspotu?',
        answer:
          'Tylko na DiLink 3, jeśli łączysz samochód z internetem przez własną kartę SIM/telefon. Utrzymuje to połączenie przy życiu między wybudzeniami. Jeśli Twój samochód ma własny pakiet danych, zignoruj go — pozostaje ukryty, chyba że włączone jest „Użyj własnej karty SIM”.',
      },
      {
        question: '❓ Jak instalują się teraz aktualizacje?',
        answer:
          'DashCast sprawdza GitHub przy każdym starcie. Gdy aktualizacja zostanie pobrana, instaluje się po cichu i sama ponownie uruchamia aplikację — bez pytania „Zainstalować?”. Na samochodzie, gdzie nie jest to możliwe, wraca do normalnego instalatora systemowego. Pierwsza aktualizacja zaraz po przejściu na wersję z tą funkcją może jeszcze raz zapytać.',
      },
      {
        question: '❓ Czy DashCast rozładowuje akumulator 12 V?',
        answer:
          'Nie — DashCast zatrzymuje się wraz z samochodem. Żadna usługa w tle nie pozostaje aktywna przy wyłączonym silniku.',
      },
      {
        question: '❓ Jakie aplikacje działają na klastrze?',
        items: [
          '✅ Nawigacja: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Multimedia: Spotify, YouTube, YouTube Music (preferuj poziomo).',
          '✅ System: kamera, pogoda, kalendarz.',
          '⚠️ Aplikacje z DRM (Netflix, Disney+, Prime Video): mogą odmówić wyświetlania na wirtualnym ekranie — ograniczenie Androida, nie DashCast.',
        ],
      },
      {
        question: '❓ Aktualizacje: stabilne czy beta?',
        answer:
          'Kanał stabilny (domyślny) jest testowany w pojeździe przed publikacją. Kanał beta (Ustawienia → Aktualizacje → „Uwzględnij wersje wstępne”) otrzymuje nowości od razu po kompilacji — przydatny do wczesnych testów, z ryzykiem tymczasowych regresji.',
      },
      {
        question: '❓ Chcę pomóc lub zgłosić błąd',
        answer:
          'Użyj wbudowanego zgłaszania błędów, aby uzyskać najszybszą drogę (dołącza diagnostykę za Ciebie). Dla kodu i propozycji funkcji: GitHub https://github.com/Kiroha/byd-dashcast — Issues dla błędów, Discussions dla pytań.',
      },
    ],
  },

  footer:
    'DashCast to projekt open-source rozpowszechniany na licencji MIT. Brak powiązań z BYD Auto Co., Ltd.',
};
