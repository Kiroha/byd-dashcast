export default {
  code: 'pl',
  flag: '🇵🇱',
  name: 'Polski',
  title: 'DashCast — Instrukcja użytkownika',
  manualName: 'Instrukcja użytkownika',
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Spis treści',

  intro: {
    title: '0. Wprowadzenie',
    lead:
      'DashCast pozwala wyświetlić dowolną aplikację Android z centralnego ekranu BYD na cyfrowym zestawie wskaźników (klastrze). Możesz mieć Mapy, Waze, Spotify lub YouTube bezpośrednio za kierownicą, zachowując jednocześnie stały dostęp do oryginalnych wskazań BYD (prędkość, mierniki, zasięg).',
    bullets: [
      '✅ Kompatybilność z BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).',
      '✅ Bez modyfikacji systemu: DashCast instaluje się jak zwykła aplikacja.',
      '✅ Lokalne ADB przez TCP — po pierwszej autoryzacji komputer nie jest potrzebny.',
      '✅ 13 języków interfejsu, wybieranych przy pierwszym uruchomieniu.',
      '✅ Wbudowane aktualizacje OTA (opcjonalny kanał alpha).',
      '✅ Marginesy overscan ustawiane osobno dla każdej aplikacji.',
      '✅ Dotykowy mirror pełnoekranowy do sterowania klastrem z centralnego ekranu.',
      '✅ Tryb podziału (dwie aplikacje obok siebie na klastrze).',
    ],
    note:
      '💡 Jednorazowy warunek: włącz bezprzewodowe debugowanie ADB w Ustawieniach BYD → Programista. Przy pierwszym uruchomieniu pojawi się okno „Zezwolić na debugowanie?” — zaznacz „Zezwalaj zawsze” i zatwierdź. Nigdy nie trzeba powtarzać tego kroku.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Ekran powitalny — wybór języka',
      lead:
        'Przy pierwszym uruchomieniu DashCast pokazuje siatkę z dostępnymi językami. Dotknij wybranego; wybór zostanie zapisany i ekran powitalny nie pojawi się ponownie. Język można zmienić w dowolnej chwili w Ustawieniach → Język.',
      mockupLabel: 'Otwórz ekran 1 (Powitanie)',
      featuresTitle: 'Szczegóły',
      features: [
        {
          title: '13 obsługiwanych języków',
          text:
            'Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O‘zbekcha, Қазақша, Беларуская, Polski. Wybrany język jest stosowany natychmiast, bez restartu.',
        },
        {
          title: 'Automatyczny kierunek czytania',
          text:
            'Język arabski automatycznie przełącza interfejs na układ od prawej do lewej (RTL): pasek nawigacji przenosi się na prawą stronę, listy są odwracane, ikony pozostają czytelne.',
        },
        {
          title: 'Zmiana w dowolnym momencie',
          text:
            'Aby zmienić język później: przytrzymaj logo DashCast na górze paska bocznego → 🌐 Język. Nowy język zostanie zastosowany w locie.',
        },
      ],
      howTo: {
        title: 'Jak to zrobić',
        steps: [
          'Uruchom DashCast (niebieska ikona w szufladzie aplikacji BYD).',
          'Pojawi się ekran powitalny z siatką 4×3 języków.',
          'Dotknij swój język. Interfejs zmieni się natychmiast.',
          'Otworzy się ekran główny — możesz korzystać z DashCast.',
        ],
      },
      note:
        'ℹ️ Jeśli zmienisz język podczas trwającej projekcji, projekcja nie zostanie przerwana; przetłumaczony zostanie tylko interfejs DashCast.',
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Ekran główny — Aplikacje i Klaster',
      lead:
        'Centralny ekran DashCast. Po lewej lista wszystkich zainstalowanych aplikacji z wyszukiwarką, filtrami kategorii i ulubionymi. Po prawej podgląd klastra na żywo z głównymi akcjami: podgląd pełnoekranowy, zrzut ekranu, ponowne połączenie, zatrzymanie projekcji.',
      mockupLabel: 'Otwórz ekran 2 (Główny)',
      featuresTitle: 'Co możesz zrobić',
      features: [
        {
          title: '🔍 Pasek wyszukiwania',
          text:
            'Wpisz kilka liter, aby filtrować listę w locie (dopasowanie po nazwie i pakiecie). Przycisk ▦ po prawej przełącza widok listy i siatki.',
        },
        {
          title: '🏷️ Filtry kategorii',
          text:
            'Kolorowe etykiety (Wszystkie / Nawigacja / Multimedia / Komunikacja / System) automatycznie grupują aplikacje. Liczba w nawiasach pokazuje, ile aplikacji jest widocznych.',
        },
        {
          title: '⭐ Przypięte ulubione',
          text:
            'Sekcja „Ulubione” utrzymuje najczęściej używane aplikacje na górze. Aby dodać lub usunąć ulubione: przytrzymaj aplikację → ⭐ Dodaj/Usuń z ulubionych.',
        },
        {
          title: '👆 Krótkie dotknięcie — projekcja',
          text:
            'Dotknij aplikację, aby od razu wysłać ją na klaster. Jeśli projekcja nie była uruchomiona, włączy się automatycznie (rozgrzanie klastra ~2 s).',
        },
        {
          title: '👆⏱️ Długie przytrzymanie — menu akcji',
          text:
            'Przytrzymaj dowolną aplikację, aby otworzyć pełnoekranowe menu: ⭐ Ulubiona, Autostart (włącz przy projekcji), Przenieś na klaster / na ekran główny, ✕ Wymuś zatrzymanie.',
        },
        {
          title: '🚦 Podgląd klastra na żywo',
          text:
            'Prawy panel odzwierciedla to, co jest pokazane na klastrze (prędkość, bieg, bateria, zasięg). Wartość opóźnienia (12 ms) potwierdza poprawność połączenia.',
        },
        {
          title: '👁️ Podgląd pełnoekranowy',
          text:
            'Dotknij „Podgląd pełnoekranowy”, aby rozszerzyć podgląd na cały ekran centralny. Przydatne do wpisywania adresu w Mapach z pełną klawiaturą: każde wprowadzenie jest odzwierciedlane na klastrze.',
        },
        {
          title: '📸 Zrzut ekranu',
          text:
            'Przycisk „Zrzut ekranu” zapisuje aktualny widok klastra jako PNG do /sdcard/Pictures/DashCast/. Przydatne, aby udostępnić trasę lub rozwiązać problem.',
        },
        {
          title: '↻ Połącz ponownie',
          text:
            'Jeśli projektowana aplikacja zamarła lub przestała odpowiadać, „Połącz ponownie” odtwarza strumień wideo bez wpływu na oryginalny klaster.',
        },
        {
          title: '⏹ Zatrzymaj mirror',
          text:
            'Czysto kończy projekcję. Krótkie dotknięcie = miękkie zatrzymanie (klaster wraca do natywnego BYD przez ADB). Długie przytrzymanie = rozszerzone menu z opcją „Przywróć oryginalny klaster”, która wymusza sekwencję przywracania zgodnie z rozmiarem klastra z Ustawień.',
        },
      ],
      howTo: {
        title: 'Jak wyświetlić aplikację na klastrze',
        steps: [
          'Na ekranie głównym znajdź żądaną aplikację (np. Mapy).',
          'Dotknij ikonę → rozpoczyna się projekcja, klaster przełącza się na aplikację w ~2 s.',
          'Prawy panel pokazuje na żywo to, co znajduje się na klastrze.',
          'Aby wprowadzić tekst (wyszukać adres), dotknij „Podgląd pełnoekranowy” → aplikacja rozszerza się na cały ekran centralny → wpisz adres → wszystko jest odzwierciedlane na klastrze.',
          'Aby zatrzymać: dotknij „Zatrzymaj mirror” (klaster wraca do natywnego BYD).',
        ],
      },
      tipsTitle: 'Wskazówki',
      tips: [
        '💡 Autostart: włącz ten przełącznik dla aplikacji, aby była ona projektowana automatycznie przy każdym uruchomieniu DashCast.',
        '💡 Tryb podziału: w menu przytrzymania drugiej aplikacji wybierz „Wyślij jako podział”, aby wyświetlić 2 aplikacje obok siebie na klastrze.',
        '💡 Marginesy: jeśli aplikacja wykracza poza klaster, otwórz Ustawienia → Marginesy i dopasuj suwaki. Ustawienie jest zapisywane dla każdej aplikacji osobno.',
        '💡 Pełny ekran dotykowy: w trybie podglądu pełnoekranowego palce na ekranie centralnym faktycznie sterują aplikacją — klawiatura, przewijanie, gesty, wszystko działa.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ustawienia',
      lead:
        'Ekran Ustawień gromadzi opcje globalne i dostrojenie obrazu projekcji. Lewy pasek pozostaje dostępny — można przełączać się między Aplikacjami, Ustawieniami, Diagnostyką, Systemem i Dziennikiem bez utraty miejsca.',
      mockupLabel: 'Otwórz ekran 3 (Ustawienia)',
      featuresTitle: 'Dostępne sekcje',
      features: [
        {
          title: '📺 Typ klastra',
          text:
            'Wybierz fizyczny rozmiar swojego zestawu wskaźników: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, domyślnie) lub 10.25″ (cmd 31). Ta wartość jest używana w szczególności przez „Przywróć oryginalny klaster”, aby wywołać prawidłowy tryb.',
        },
        {
          title: '🌐 Język',
          text:
            'Dostępnych 13 języków. Przełączenie jest natychmiastowe — bez konieczności restartu DashCast.',
        },
        {
          title: '↔️ Margines poziomy (overscan)',
          text:
            'Suwak 0–200 px. Dodaje czarne pasy po lewej/prawej stronie, aby skompensować obcięte krawędzie na klastrze. Wartość zapisywana dla każdej aplikacji — Mapy mogą używać 80 px, a Spotify pozostać na 0.',
        },
        {
          title: '↕️ Margines pionowy (overscan)',
          text:
            'Suwak 0–200 px. Analogicznie dla góry/dołu. Połączone marginesy są stosowane na poziomie VirtualDisplay, więc projektowana aplikacja nigdy nie „widzi” obciętych stref.',
        },
        {
          title: '✅ Zastosuj / 🔄 Resetuj',
          text:
            '„Zastosuj” natychmiast wprowadza nowe marginesy w działającej projekcji. „Resetuj” przywraca aktualną aplikację do 0/0.',
        },
        {
          title: '📦 Aktualizacje OTA',
          text:
            'DashCast automatycznie sprawdza nowe wersje w GitHub Releases. Zaznacz „Uwzględnij wydania wstępne”, aby otrzymywać kanał alpha (częstsze, ale eksperymentalne aktualizacje).',
        },
        {
          title: '🚗 Auto-start z pojazdem',
          text:
            'Po włączeniu DashCast uruchamia się razem z samochodem i przywraca ostatnio projektowaną aplikację. W przeciwnym razie uruchamiasz go ręcznie z szuflady aplikacji BYD.',
        },
      ],
      howTo: {
        title: 'Jak dostroić marginesy aplikacji',
        steps: [
          'Projektuj aplikację, którą chcesz dostroić (np. Waze).',
          'Otwórz Ustawienia → Marginesy.',
          'Przesuń suwak poziomy aż lewa/prawa krawędź będą wyglądały prawidłowo.',
          'Analogicznie z suwakiem pionowym.',
          'Dotknij „Zastosuj” → projekcja jest aktualizowana na żywo, bez restartu aplikacji.',
          'Ustawienie jest zapisywane tylko dla tej aplikacji (każda aplikacja ma własne marginesy).',
        ],
      },
      note:
        '⚠️ Jeśli zmienisz typ klastra, zrestartuj DashCast, aby wartości referencyjne zostały przeliczone.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnostyka',
      lead:
        'Zakładka Diagnostyka to wewnętrzny panel zarezerwowany dla przypadków, gdy projekcja działa nieprawidłowo. Większość użytkowników nigdy go nie potrzebuje — jest dostępny dla wsparcia i debugowania.',
      mockupLabel: 'Otwórz ekran 4 (Diagnostyka)',
      featuresTitle: 'Dostępne narzędzia',
      features: [
        {
          title: 'Stan ClusterService',
          text:
            'Sprawdza, czy usługa Android obsługująca projekcję jest uruchomiona. Jeśli „nie podłączona”, przycisk ją restartuje.',
        },
        {
          title: 'Stan VirtualDisplay',
          text:
            'Pokazuje identyfikator wirtualnego wyświetlacza utworzonego dla klastra, jego rozdzielczość oraz czy podłączona jest powierzchnia Qt.',
        },
        {
          title: 'Lokalne połączenie ADB',
          text:
            'Szybki test tunelu ADB do localhost:5555. Jeśli test zakończy się niepowodzeniem, zazwyczaj wyłączono bezprzewodowe debugowanie w ustawieniach BYD.',
        },
        {
          title: 'Ukierunkowany logcat',
          text:
            'Przechwytuje ostatnie 200 wierszy logcata filtrowanych po DashCast / AutoContainer / xdja. Przycisk „Udostępnij” wysyła raport.',
        },
      ],
      howTo: {
        title: 'Kiedy używać tej zakładki',
        steps: [
          'Klaster pozostaje czarny po dotknięciu aplikacji → sprawdź stan ClusterService i VirtualDisplay.',
          'Aplikacja informuje „ADB niedostępne” → zakładka Diag → przycisk „Testuj ADB”.',
          'Wsparcie prosi o raport → Diag → „Udostępnij logcat”.',
          'Aktualizacja została właśnie zainstalowana i chcesz potwierdzić uruchomioną wersję.',
        ],
      },
      note:
        'ℹ️ Ta zakładka sama z siebie nic nie zmienia: przyciski uruchamiają testy tylko do odczytu, chyba że wyraźnie zaznaczono inaczej.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Informacje systemowe',
      lead:
        'Panel tylko do odczytu z informacjami o środowisku sprzętowo-programowym. Tutaj znajdziesz wersję DashCast, firmware BYD, wersję Androida oraz identyfikator klastra.',
      mockupLabel: 'Otwórz ekran 5 (System)',
      featuresTitle: 'Wyświetlane informacje',
      features: [
        {
          title: '🚗 Pojazd',
          text:
            'Wykryty model BYD, VIN (jeśli dostępny), build firmware (np. Di3.0 / 6125F), data buildu firmware.',
        },
        {
          title: '📱 Android',
          text:
            'Wersja Androida (10), poziom API (29), poprawka zabezpieczeń, identyfikator buildu DiLink.',
        },
        {
          title: '🔌 DashCast',
          text:
            'Zainstalowana wersja, versionCode, kanał aktualizacji (stable / alpha), data ostatniego sprawdzenia OTA, link do informacji o wydaniu.',
        },
        {
          title: '🖥️ Klaster',
          text:
            'Wykryty typ (8.8″ / 12.3″ / 10.25″), faktyczna rozdzielczość, aktualny identyfikator VirtualDisplay, aktywny pakiet Qt (com.xdja.containerservice).',
        },
        {
          title: '📦 Śledzone aplikacje',
          text:
            'Liczba aplikacji wykrytych przez DashCast, liczba przypiętych ulubionych, liczba aplikacji z włączonym autostartem.',
        },
      ],
      tipsTitle: 'Wskazówki',
      tips: [
        '💡 Przytrzymaj wiersz, aby skopiować wartość do schowka (przydatne do zgłoszenia błędu).',
        '💡 Przycisk „Eksportuj” na dole zapisuje wszystko do pliku tekstowego (/sdcard/DashCast/sysinfo.txt).',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Dziennik',
      lead:
        'Wewnętrzny dziennik DashCast: śledzi każdą ważną akcję (projekcje, przywracania, błędy ADB, aktualizacje). Przydatny do zrozumienia nieoczekiwanego zachowania lub wysłania raportu do wsparcia.',
      mockupLabel: 'Otwórz ekran 6 (Dziennik)',
      featuresTitle: 'Funkcje',
      features: [
        {
          title: '🔍 Filtr',
          text:
            'Wpisz słowo kluczowe, aby zachować tylko istotne wiersze (np. „ADB”, „Mapy”, „błąd”). Filtr nie rozróżnia wielkości liter.',
        },
        {
          title: '🎨 Kod kolorów',
          text:
            '🟢 INFO (zielony) — normalna praca. 🟠 WARN (pomarańczowy) — uwaga. 🔴 ERROR (czerwony) — coś się nie powiodło. ⚪ DEBUG (szary) — szczegół techniczny.',
        },
        {
          title: '🗑 Wyczyść',
          text:
            'Opróżnia dziennik. Systemowy ślad logcat nie jest naruszany — kasowana jest tylko historia DashCast w pamięci.',
        },
        {
          title: '📤 Udostępnij',
          text:
            'Eksportuje bieżący dziennik jako .txt i otwiera panel udostępniania Androida (e-mail, Telegram, plik). Automatycznie dołącza wersję DashCast i model BYD.',
        },
        {
          title: '⏰ Znaczniki czasu',
          text:
            'Każdy wiersz jest poprzedzony lokalnym czasem (HH:mm:ss.mmm). Długotrwałe operacje (uruchomienie Map, przywrócenie klastra) są mierzone i wyświetlane.',
        },
      ],
      howTo: {
        title: 'Jak wysłać raport o błędzie',
        steps: [
          'Odtwórz problem (np. aplikacja pozostaje czarna po uruchomieniu).',
          'Otwórz Dziennik.',
          'Dotknij „Udostępnij”.',
          'Wybierz kanał (Telegram, e-mail, GitHub Issues).',
          'Dołączony plik .txt zawiera pełen ślad oraz kontekst (wersja, model, firmware).',
        ],
      },
      note:
        '🔒 Żadne dane osobowe (kontakty, lokalizacja GPS, zawartość aplikacji) nie są logowane — tylko akcje DashCast i techniczne kody powrotu.',
    },
  ],

  faq: {
    title: '7. FAQ — częste pytania',
    items: [
      {
        question: '❓ Klaster pozostaje czarny po dotknięciu aplikacji',
        answer:
          'Trzy możliwe przyczyny: (1) wyłączone bezprzewodowe ADB — sprawdź Ustawienia BYD → Programista. (2) ClusterService nie działa — zakładka Diag, przycisk „Restartuj”. (3) Aplikacja właśnie się zawiesiła — dotknij „Połącz ponownie” w prawym panelu.',
      },
      {
        question: '❓ Obraz wykracza poza klaster / jest obcięty',
        answer:
          'Otwórz Ustawienia → Marginesy i dopasuj suwaki poziomy/pionowy aż krawędzie będą wyglądać prawidłowo. Ustawienie jest zapisywane tylko dla danej aplikacji — wystarczy zrobić to raz.',
      },
      {
        question: '❓ Jak wrócić do oryginalnego pulpitu BYD?',
        answer:
          'Krótkie dotknięcie „Zatrzymaj mirror” wystarcza w 95 % przypadków. Jeśli klaster jest zablokowany, przytrzymaj ten sam przycisk → menu → „Przywróć oryginalny klaster”: DashCast wymusza sekwencję sendInfo odpowiadającą Twojemu typowi klastra.',
      },
      {
        question: '❓ Czy DashCast rozładuje akumulator 12 V?',
        answer:
          'Nie — DashCast zatrzymuje się automatycznie, gdy samochód jest wyłączany (rozgłoszenia Android.intent.action.SCREEN_OFF + odłączenie BMS). Żadna usługa w tle nie pozostaje aktywna po wyłączeniu silnika.',
      },
      {
        question: '❓ Chcę pomóc lub zgłosić błąd',
        answer:
          'GitHub: https://github.com/Kiroha/byd-dashcast — Issues dla błędów, Discussions dla pytań. Zawsze dołącz eksport Dziennika (zakładka Dziennik → Udostępnij), aby przyspieszyć diagnozę.',
      },
      {
        question: '❓ Które aplikacje działają na klastrze?',
        items: [
          '✅ Nawigacja: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.',
          '✅ Multimedia: Spotify, YouTube, YouTube Music, Netflix (preferowana orientacja pozioma).',
          '✅ Komunikacja: Telegram (tryb tylko do odczytu), WhatsApp (powiadomienia).',
          '✅ System: aparat, pogoda, kalendarz.',
          '⚠️ Aplikacje używające DRM Widevine L1 (Disney+, Prime Video) mogą odmówić renderowania na VirtualDisplay — to ograniczenie Androida, nie DashCast.',
        ],
      },
      {
        question: '❓ Aktualizacje: stable czy alpha?',
        answer:
          'Kanał stable (domyślny) jest testowany w pojeździe przez co najmniej 1 tydzień przed publikacją. Kanał alpha (włącz w Ustawienia → Aktualizacje) otrzymuje nowe buildy zaraz po skompilowaniu — przydatny do testów z wyprzedzeniem, ale może wprowadzać tymczasowe regresje.',
      },
    ],
  },

  footer:
    'DashCast to projekt open source dystrybuowany na licencji GPL-3.0. Niezwiązany z BYD Auto Co., Ltd.',
};
