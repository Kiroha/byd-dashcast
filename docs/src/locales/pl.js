export default {
  code: 'pl',
  flag: '🇵🇱',
  name: 'Polski',
  title: 'DashCast — Instrukcja obsługi',
  manualName: 'Instrukcja obsługi',
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Spis treści',

  intro: {
    title: '0. Wprowadzenie',
    lead:
      'DashCast wyświetla dowolną aplikację Android z centralnego ekranu Twojego BYD na zestawie wskaźników (cyfrowym klastrze). Maps, Waze, Spotify lub ABRP tuż za kierownicą — a w trybie Layouts kilka aplikacji naraz, każda w swojej strefie. Wszystko bez modyfikacji systemu.',
    bullets: [
      '✅ Kompatybilny z BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).',
      '✅ Bez modyfikacji systemu: DashCast instaluje się jak zwykła aplikacja.',
      '✅ Lokalne ADB po TCP — po pierwszej autoryzacji komputer nie jest potrzebny.',
      '✅ 13 języków interfejsu, wybór przy pierwszym uruchomieniu.',
      '✅ Dotykowe lustro w czasie rzeczywistym: steruj klastrem z centralnego ekranu.',
      '✅ Tryb Layouts: kilka aplikacji obok siebie na klastrze, strefy rysowane palcem.',
      '✅ Autostart: projekcja + aplikacja (lub ulubiony layout) zaraz po otwarciu DashCast.',
      '✅ Marginesy (overscan) zapamiętywane dla każdej aplikacji.',
      '✅ Wbudowane aktualizacje OTA (opcjonalny kanał beta).',
    ],
    note:
      '💡 Jedyny wymóg: włączyć bezprzewodowe debugowanie ADB w Ustawieniach BYD → Programista. Przy pierwszym uruchomieniu pojawi się okno „Zezwolić na debugowanie?” — zaznacz „Zawsze zezwalaj” i potwierdź. Nigdy nie trzeba tego powtarzać.',
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
            'Arabski automatycznie przełącza się na układ od prawej do lewej (RTL): pasek nawigacji przechodzi na prawo, listy się odwracają.',
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
        'Centralny ekran DashCast. Po lewej: wszystkie aplikacje z wyszukiwaniem, filtrami i ulubionymi. Po prawej: podgląd klastra w czasie rzeczywistym, przyciski Lustro na pełnym ekranie / Zatrzymaj projekcję oraz karuzela layoutów do wyboru ulubionego układu multi-app.',
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
            'Prawy panel odzwierciedla na żywo klaster. Twoje dotknięcia podglądu są przekazywane do projektowanej aplikacji — przewijanie, zoom, klawiatura, wszystko działa.',
        },
        {
          title: '👁️ Lustro na pełnym ekranie',
          text:
            'Rozszerza podgląd na cały centralny ekran: idealne do wpisania adresu w Maps z pełną klawiaturą. Wszystko jest replikowane na klastrze w czasie rzeczywistym.',
        },
        {
          title: '⏹ Zatrzymaj projekcję',
          text:
            'Czysto kończy projekcję i przywraca oryginalny pulpit BYD (prędkość, wskaźniki, ADAS) z rozmiarem ustawionym w Ustawieniach.',
        },
        {
          title: '🗂️ Karuzela layoutów',
          text:
            'Pod przyciskami każda karta pokazuje mini-podgląd stref layoutu. Dotknij karty, aby ustawić ulubiony layout (gwiazdka + niebieska ramka). „Tryb wolny” wyłącza layouty; „＋ Zarządzaj” otwiera edytor.',
        },
        {
          title: '📺 Pływający przycisk',
          text:
            'Przycisk 📺 pozostaje nad innymi aplikacjami: dotknięcie = otwórz lustro, przytrzymanie = szybkie przełączanie między ostatnio projektowanymi aplikacjami.',
        },
      ],
      howTo: {
        title: 'Jak wyświetlić aplikację na klastrze',
        steps: [
          'Znajdź żądaną aplikację (np. Maps) — wyszukiwanie lub filtry w razie potrzeby.',
          'Dotknij jej ikony → projekcja startuje, klaster przełącza się na aplikację w ~2 s.',
          'Prawy podgląd pokazuje na żywo zawartość klastra.',
          'Aby wpisać tekst: „Lustro na pełnym ekranie” → wpisz adres → wszystko jest replikowane.',
          'Aby zatrzymać: „Zatrzymaj projekcję” — klaster wraca do natywnego BYD.',
        ],
      },
      tipsTitle: 'Wskazówki',
      tips: [
        '💡 Auto-launch: wybierz aplikację (przytrzymanie → Auto-launch), aby projektowała się automatycznie przy każdym starcie DashCast — projekcja aktywuje się sama.',
        '💡 Ulubiony layout: karta wybrana w karuzeli to ta, którą aktywuje autostart (zobacz sekcję Layouts).',
        '💡 Marginesy: jeśli aplikacja wychodzi poza klaster, Ustawienia → Marginesy, suwaki poziomy/pionowy. Zapamiętane per aplikacja.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ustawienia',
      lead:
        'Opcje globalne: rozmiar klastra, język, marginesy, zachowanie przy starcie, tryb Layouts i aktualizacje. Pasek boczny pozostaje dostępny — przełączaj ekrany bez utraty pozycji.',
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
            'Suwaki poziomy/pionowy (0–200 px) kompensujące ucięte krawędzie. Zapamiętane per aplikacja: Maps może mieć 80 px, a Spotify 0. „Zastosuj” dostosowuje projekcję na gorąco.',
        },
        {
          title: '🚗 Start z pojazdem',
          text:
            'Jeśli włączone, DashCast startuje z samochodem i przywraca ostatnio projektowaną aplikację. W przeciwnym razie uruchom go z szuflady BYD.',
        },
        {
          title: '🗂️ Tryb Layouts',
          text:
            'Włącza projekcję wieloaplikacyjną z niestandardowymi strefami (wymaga Proxy ADB Daemon, zarządzanego automatycznie). Pokazuje karuzelę na ekranie głównym i kartę Layouts.',
        },
        {
          title: '⭐ Automatyczny ulubiony layout',
          text:
            'Przy starcie DashCast: aktywuje projekcję klastra, ulubiony layout i uruchamia aplikacje powiązane z każdą strefą. Pełna konfiguracja multi-app bez jednego dotknięcia.',
        },
        {
          title: '⚡ Wstępne tworzenie slotów przy starcie',
          text:
            'Przygotowuje wirtualne ekrany ulubionego layoutu przy otwarciu (bez uruchamiania aplikacji) — aktywacja layoutu jest potem niemal natychmiastowa.',
        },
        {
          title: '📦 Aktualizacje OTA',
          text:
            'DashCast sprawdza GitHub przy każdym starcie. Zaznacz „Uwzględnij wersje wstępne” dla kanału beta (nowości wcześniej, mniejsza stabilność).',
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
          'To samo w pionie, potem „Zastosuj” — regulacja na gorąco, bez restartu aplikacji.',
          'Ustawienie zapisywane tylko dla tej aplikacji.',
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
        'Tryb Layouts dzieli klaster na niestandardowe strefy, każda z własną aplikacją: Waze po lewej, Spotify po prawej, na przykład. Rysujesz strefy palcem, wiążesz aplikację z każdą strefą, a layout aktywuje się jednym dotknięciem — lub sam przy starcie.',
      mockupLabel: 'Zobacz ekran 7 (Layouts)',
      featuresTitle: 'Funkcje',
      features: [
        {
          title: '✏️ Rysowanie strefy',
          text:
            'Na kanwie (replika klastra 1920×720) przeciągnij palcem, aby narysować prostokąt. Otwiera się okno: nazwa, pozycja/wymiary co do piksela i aplikacja do powiązania.',
        },
        {
          title: '🔗 Wiązanie aplikacji',
          text:
            'Każdą strefę można powiązać z aplikacją: przy aktywacji layoutu aplikacja uruchamia się automatycznie w swojej strefie. Strefa bez aplikacji pozostaje wolna.',
        },
        {
          title: '✋ Przenoszenie i zmiana rozmiaru',
          text:
            'Przeciągnij strefę, aby ją przesunąć; białe uchwyty w rogach zmieniają rozmiar. Krawędzie przyciągają się automatycznie do granic klastra i sąsiednich stref.',
        },
        {
          title: '✏️ Edycja istniejącej strefy',
          text:
            'Dotknij strefy (na kanwie lub jej chipa poniżej): zmień nazwę, geometrię, powiązaną aplikację lub usuń. Przytrzymanie = szybkie usunięcie.',
        },
        {
          title: '💾 Zapisane layouty',
          text:
            'Zapisz dowolną liczbę layoutów („Nav+Media”, „Potrójny ekran”…). Panel boczny wyświetla je z opcjami Aktywuj / Dezaktywuj / Edytuj / Usuń.',
        },
        {
          title: '⭐ Ulubiony i autostart',
          text:
            'Przycisk „Ulubiony” (lub dotknięcie karty karuzeli na ekranie głównym) wyznacza layout, który „Automatyczny ulubiony layout” aktywuje przy starcie DashCast — wraz z projekcją.',
        },
      ],
      howTo: {
        title: 'Utwórz swój pierwszy layout',
        steps: [
          'Włącz „Tryb Layouts” w Ustawieniach.',
          'Otwórz kartę Layouts (pasek boczny).',
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
        '💡 Mini-podgląd każdej karty karuzeli pokazuje rzeczywiste strefy layoutu — rozpoznawalny na pierwszy rzut oka.',
        '💡 Aplikacja nie chce się wyświetlić w swojej strefie? Niektóre wymuszają proporcje; spróbuj strefy bliższej 16:9.',
      ],
      note:
        'ℹ️ Tryb Layouts opiera się na Proxy ADB Daemon (uruchamianym automatycznie). Pierwszy zimny start: odczekaj 6–8 s, zanim aplikacje się pojawią — to sekwencja aktywacji klastra.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnostyka',
      lead:
        'Wewnętrzny panel na sytuacje, gdy projekcja nie działa zgodnie z oczekiwaniami. Większość użytkowników nigdy go nie użyje — istnieje dla wsparcia i debugowania.',
      mockupLabel: 'Zobacz ekran 4 (Diagnostyka)',
      featuresTitle: 'Dostępne narzędzia',
      features: [
        {
          title: 'Testy połączenia',
          text:
            'Sprawdza lokalny tunel ADB (localhost:5555), stan ClusterService i obecność wirtualnego ekranu klastra.',
        },
        {
          title: 'Sondy platformy',
          text:
            'Wykrywanie DiLink (2/3/4/5), inwentarz ekranów, instancjonowanie API pojazdu BYD (prędkość, energia) i stan uprawnień BYDAUTO.',
        },
        {
          title: 'Raport do udostępnienia',
          text:
            'Generuje pełny raport (system, ekrany, usługi, uprawnienia, metryki demona) eksportowalny jako tekst dla wsparcia.',
        },
      ],
      howTo: {
        title: 'Kiedy używać tej karty',
        steps: [
          'Klaster pozostaje czarny po dotknięciu aplikacji → sprawdź ClusterService i wirtualny ekran.',
          'Aplikacja zgłasza „ADB niedostępne” → przycisk „Testuj ADB”.',
          'Wsparcie prosi o raport → wygeneruj i udostępnij.',
        ],
      },
      note: 'ℹ️ Przyciski to testy tylko do odczytu, chyba że wyraźnie zaznaczono inaczej.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Raport systemowy',
      lead:
        'Panel tylko do odczytu: wersje, wykryte ekrany i stan usług DashCast na żywo. Pierwszy ekran do sprawdzenia, gdy coś wygląda nieprawidłowo.',
      mockupLabel: 'Zobacz ekran 5 (System)',
      featuresTitle: 'Wyświetlane informacje',
      features: [
        {
          title: '🖥️ Ekrany',
          text:
            'Ekran główny (rozdzielczość, gęstość) i wirtualny ekran klastra (1920×720) ze stanem w czasie rzeczywistym.',
        },
        {
          title: '⚙️ Usługi',
          text:
            'ClusterService (projekcja), MirrorDaemon (lustro), Proxy ADB Daemon (operacje uprzywilejowane), AdbLocalClient (tunel ADB) — każda z zieloną/czerwoną kropką i przyciskiem restartu.',
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
        '💡 Pełny raport można wyeksportować z tego ekranu, aby dołączyć do zgłoszenia błędu.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Dziennik',
      lead:
        'Wewnętrzny dziennik DashCast: wszystkie ważne działania (projekcje, przywracanie, błędy ADB, aktualizacje) są stale rejestrowane. Przydatny do zrozumienia nieoczekiwanego zachowania lub dostarczenia raportu wsparciu.',
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
            'Każdy wiersz ma czas lokalny (HH:mm:ss.mmm); długie operacje są mierzone.',
        },
      ],
      howTo: {
        title: 'Wysłanie zgłoszenia błędu',
        steps: [
          'Odtwórz problem.',
          'Otwórz Dziennik → „Udostępnij”.',
          'Wybierz kanał (Telegram, e-mail, GitHub Issues).',
          'Załączony plik zawiera ślad i kontekst (wersja, model, firmware).',
        ],
      },
      note:
        '🔒 Żadne dane osobowe (kontakty, pozycja GPS, zawartość aplikacji) nie są rejestrowane — tylko działania DashCast i techniczne kody zwrotne.',
    },
  ],

  faq: {
    title: '8. FAQ — Najczęstsze pytania',
    items: [
      {
        question: '❓ Klaster pozostaje czarny, gdy dotykam aplikacji',
        answer:
          'Trzy możliwe przyczyny: (1) bezprzewodowe ADB wyłączone — sprawdź Ustawienia BYD → Programista. (2) Zatrzymana usługa — karta System, zrestartuj czerwony wiersz. (3) Aplikacja właśnie się zawiesiła — dotknij ponownie jej ikony.',
      },
      {
        question: '❓ Obraz wychodzi poza klaster / jest przycięty',
        answer:
          'Ustawienia → Marginesy: reguluj suwaki poziomy/pionowy, aż krawędzie będą poprawne. Zapamiętane per aplikacja — zrobisz to tylko raz.',
      },
      {
        question: '❓ Jak wrócić do oryginalnego pulpitu BYD?',
        answer:
          'Dotknij „Zatrzymaj projekcję” na ekranie głównym: DashCast przywraca natywny klaster z rozmiarem z Ustawień. Jeśli zestaw wygląda na zablokowany: ekran System → „Replay projekcji”, potem zatrzymaj ponownie.',
      },
      {
        question: '❓ Mój ulubiony layout nie startuje przy uruchomieniu',
        answer:
          'Sprawdź trzy warunki w Ustawieniach: „Tryb Layouts” włączony, „Automatyczny ulubiony layout” włączony i layout oznaczony ⭐ jako ulubiony (karuzela ekranu głównego lub przycisk Ulubiony w karcie Layouts). Przy zimnym starcie odczekaj 6–8 s.',
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
          'GitHub: https://github.com/Kiroha/byd-dashcast — Issues dla błędów, Discussions dla pytań. Dołącz eksport Dziennika, aby przyspieszyć diagnozę.',
      },
    ],
  },

  footer:
    'DashCast to projekt open-source na licencji MIT. Brak powiązań z BYD Auto Co., Ltd.',
};
