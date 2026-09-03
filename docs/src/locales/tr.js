export default {
  code: 'tr',
  flag: '🇹🇷',
  name: 'Türkçe',
  title: 'DashCast — Kullanım Kılavuzu',
  manualName: 'Kullanım Kılavuzu',
  meta: 'BYD Seal / Dolphin / Atto 3 · DiLink 3 ve DiLink 5 · Android 10–13',
  tocTitle: '📋 İçindekiler',

  intro: {
    title: '0. Giriş',
    lead:
      'DashCast, BYD merkez ekranınızdaki herhangi bir Android uygulamasını gösterge paneline (direksiyonun arkasındaki dijital gösterge ekranı) gösterir. Maps, Waze, Spotify veya ABRP tam önünüzde — ve Düzenler modu ile aynı anda birden fazla uygulama, her biri kendi bölgesinde. Normal bir uygulama gibi kurulur ve sistemde hiçbir şeyi değiştirmez.',
    bullets: [
      '✅ DiLink 3 (Seal EU / 6125F) ve DiLink 5 (daha yeni BYD ünitelerinde) çalışır.',
      '✅ Sistem değişikliği yok: DashCast diğer uygulamalar gibi kurulur.',
      '✅ TCP üzerinden yerel ADB — ilk yetkilendirmeden sonra bilgisayar gerekmez.',
      '✅ 13 arayüz dili, ilk açılışta seçilir, istediğiniz zaman değiştirilir.',
      '✅ Gerçek zamanlı dokunmatik ayna: kümeyi merkez ekrandan yönetin.',
      '✅ Düzenler modu: kümede yan yana birden fazla uygulama, parmakla çizilen bölgeler.',
      '✅ Otomatik başlatma: DashCast açılır açılmaz projeksiyon + uygulama (veya favori düzen).',
      '✅ Kenar boşlukları (overscan) uygulama başına kaydedilir.',
      '✅ DiLink 3 ön cam HUD\'unda adım adım dönüş okları (desteklenen firmware).',
      '✅ DiLink 3 için yerleşik Wi-Fi hotspot yardımcısı (kendi SIM\'inizi kullanın).',
      '✅ Klavyesiz hata bildirici, tanılamayı desteğe göndermek için tek dokunuş.',
      '✅ Sessizce kurulan ve uygulamayı yeniden başlatan otomatik OTA güncellemeleri.',
    ],
    note:
      '💡 Tek önkoşul: BYD Ayarlar → Geliştirici bölümünde kablosuz ADB hata ayıklamayı etkinleştirin. İlk açılışta «Hata ayıklamaya izin verilsin mi?» penceresi görünür — «Bu bilgisayardan her zaman izin ver» işaretleyin ve onaylayın. Bunu bir daha asla yapmanız gerekmez.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Karşılama ekranı — dil seçimi',
      lead:
        'İlk açılışta DashCast, mevcut 13 dilin ızgarasını gösterir. Dilinize dokunun: seçim kaydedilir ve ekran bir daha görünmez. Dili istediğiniz zaman Ayarlardan değiştirebilirsiniz.',
      mockupLabel: 'Ekran 1\'i gör (Karşılama)',
      featuresTitle: 'Ayrıntılar',
      features: [
        {
          title: '13 desteklenen dil',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Seçilen dil anında uygulanır, yeniden başlatma gerekmez.",
        },
        {
          title: 'Otomatik okuma yönü',
          text:
            'Arapça otomatik olarak sağdan sola (RTL) düzene geçer: gezinme çubuğu sağa kayar, listeler ters çevrilir.',
        },
        {
          title: 'Her zaman değiştirilebilir',
          text: 'Dili sonradan değiştirmek için: Ayarlar → Dil. Anında uygulanır.',
        },
      ],
      howTo: {
        title: 'Nasıl yapılır',
        steps: [
          'DashCast\'i başlatın (BYD uygulama çekmecesindeki mavi simge).',
          'Dil ızgarasıyla karşılama ekranı görünür.',
          'Dilinize dokunun. Arayüz anında değişir.',
          'Ana ekran açılır — hazırsınız.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Ana ekran — Uygulamalar ve Küme',
      lead:
        'DashCast\'in ana ekranı. Solda: arama, filtreler ve favorilerle tüm uygulamalarınız, ayrıca yan gezinme çubuğu. Sağda: gerçek zamanlı küme önizlemesi, Tam ekran ayna / Projeksiyonu durdur düğmeleri ve — Düzenler modunda — daraltılabilir «Küme düzeni» seçici.',
      mockupLabel: 'Ekran 2\'yi gör (Ana)',
      featuresTitle: 'Yapabileceğiniz her şey',
      features: [
        {
          title: '👆 Kısa dokunuş — yansıt',
          text:
            'Bir uygulamaya dokunarak kümeye gönderin. Projeksiyon aktif değilse otomatik başlar (~2 sn ısınma), ardından uygulama direksiyonun arkasında belirir.',
        },
        {
          title: '👆⏱️ Uzun basma — eylem menüsü',
          text:
            'Bir uygulamayı basılı tutun: ⭐ Favori, Otomatik başlatma (her DashCast açılışında bu uygulamayı yansıt), Kümeye / ana ekrana taşı, ✕ Durmaya zorla.',
        },
        {
          title: '🔍 Arama ve filtreler',
          text:
            'Arama çubuğu yazarken filtreler (ad veya paket). Kategori çipleri (Tümü / Navigasyon / Medya…) uygulamaları gruplar; ▦ düğmesi liste/ızgara arasında geçiş yapar.',
        },
        {
          title: '🚦 Gerçek zamanlı küme önizlemesi',
          text:
            'Sağ panel, gösterge panelinde olanı canlı yansıtır. Önizlemedeki dokunuşlarınız yansıtılan uygulamaya iletilir — kaydırma, yakınlaştırma, klavye, her şey çalışır.',
        },
        {
          title: '👁️ Tam ekran ayna',
          text:
            'Önizlemeyi tüm merkez ekrana genişletir: Maps\'te tam klavyeyle adres yazmak için ideal. Her şey gerçek zamanlı olarak kümeye kopyalanır.',
        },
        {
          title: '⏹ Projeksiyonu durdur',
          text:
            'Projeksiyonu temizce sonlandırır ve orijinal BYD panelini (hız, göstergeler, ADAS) Ayarlarda tanımlanan boyutla geri yükler.',
        },
        {
          title: '🗂️ Küme düzeni seçici (varsayılan olarak daraltılmış)',
          text:
            'Düzenler modunda düğmelerin altında kompakt bir «KÜME DÜZENİ» başlığı bulunur. Genişletmek için dokunun: «Düzen uygulamalarını başlat», ayrıca kaydedilen her düzen için bir kart (Serbest mod / hazır ayarlarınız / ＋ Yönet). Canlı önizleme tam yüksekliğini koruması için varsayılan olarak daraltılmıştır.',
        },
        {
          title: '📺 Yüzen düğme',
          text:
            'Bir 📺 düğmesi diğer uygulamaların üzerinde kalır: dokunma = aynayı aç, uzun basma = son yansıtılan uygulamalar arasında hızlı geçiş.',
        },
        {
          title: '🧭 Yan gezinme çubuğu',
          text:
            'Uygulamalar, Ayarlar, Sistem, Günlük, hata bildirici ve — kendi SIM\'inizle DiLink 3\'te — Hotspot yardımcısına hızlı erişim.',
        },
      ],
      howTo: {
        title: 'Bir uygulama kümeye nasıl yansıtılır',
        steps: [
          'İstediğiniz uygulamayı bulun (örn. Maps) — gerekirse arama veya filtreler.',
          'Simgesine dokunun → projeksiyon başlar, küme ~2 sn içinde uygulamaya geçer.',
          'Sağdaki önizleme kümede olanı canlı gösterir.',
          'Metin girmek için: «Tam ekran ayna» → adresinizi yazın → her şey kopyalanır.',
          'Durdurmak için: «Projeksiyonu durdur» — küme yerel BYD\'ye döner.',
        ],
      },
      tipsTitle: 'İpuçları',
      tips: [
        '💡 Otomatik başlatma: bir uygulama seçin (uzun basma → Otomatik başlatma), her DashCast açılışında otomatik yansıtılsın — projeksiyon kendiliğinden etkinleşir.',
        '💡 Favori düzen: «Küme düzeni» seçicide seçilen kart, otomatik başlatmanın etkinleştireceği karttır (Düzenler bölümüne bakın).',
        '💡 Kenar boşlukları: uygulama kümeden taşıyorsa, Ayarlar → Kenar boşlukları, yatay/dikey kaydırıcılar. Uygulama başına kaydedilir.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ayarlar',
      lead:
        'Genel seçenekler: küme boyutu, dil, kenar boşlukları, başlangıç davranışı, Düzenler modu ve güncellemeler. Yan çubuk erişilebilir kalır — konumunuzu kaybetmeden ekranlar arasında geçiş yapın.',
      mockupLabel: 'Ekran 3\'ü gör (Ayarlar)',
      featuresTitle: 'Ana bölümler',
      features: [
        {
          title: '📺 Küme tipi',
          text:
            'Gösterge panelinizin fiziksel boyutu: 8.8″, 12.3″ (Seal EU\'da önerilir — ADAS gerilmesini düzeltir) veya 10.25″. «Projeksiyonu durdur» doğru modu geri yüklemek için kullanır.',
        },
        {
          title: '↔️↕️ Kenar boşlukları (overscan)',
          text:
            'Kesik kenarları telafi eden yatay/dikey kaydırıcılar (0–200 px). Uygulama başına kaydedilir: Maps 80 px olabilirken Spotify 0\'da kalır. «Uygula» canlı projeksiyonu ayarlar.',
        },
        {
          title: '🚗 Araçla başlat',
          text:
            'Etkinse, DashCast arabayla başlar ve son yansıtılan uygulamayı (veya favori düzeni) geri yükler. Aksi halde BYD çekmecesinden başlatın.',
        },
        {
          title: '🗂️ Düzenler modu',
          text:
            'Özel bölgelerle çoklu uygulama projeksiyonunu etkinleştirir (otomatik yönetilen Proxy ADB Daemon gerektirir). Ana ekranda «Küme düzeni» seçiciyi ve Düzenler sekmesini gösterir.',
        },
        {
          title: '⭐ Otomatik favori düzen',
          text:
            'DashCast açılışında: küme projeksiyonunu, favori düzeni etkinleştirir ve her bölgeye bağlı uygulamaları başlatır. Tek dokunuş olmadan eksiksiz çoklu uygulama kurulumunuz.',
        },
        {
          title: '⚡ Başlangıçta slotları önceden oluştur',
          text:
            'Favori düzenin sanal ekranlarını açılışta hazırlar (uygulamaları başlatmadan) — düzenin etkinleştirilmesi sonra neredeyse anlıktır.',
        },
        {
          title: '📶 Kendi SIM\'imi kullan (DiLink 3)',
          text:
            'Hotspot yardımcısının gezinme çubuğunda görünüp görünmeyeceğini kontrol eder. Arabayı kendi telefon/SIM verinizle bağlıyorsanız açık bırakın. Hotspot bölümüne bakın.',
        },
        {
          title: '📦 OTA güncellemeleri',
          text:
            'DashCast her açılışta yeni sürümler için GitHub\'ı kontrol eder. Güncellemeler artık sessizce kurulur ve uygulamayı kendiliğinden yeniden başlatır (Güncellemeler bölümüne bakın). Beta kanalı için «Ön sürümleri dahil et» işaretleyin.',
        },
        {
          title: '🌐 Dil',
          text: '13 dil — değişim anlıktır.',
        },
      ],
      howTo: {
        title: 'Bir uygulamanın kenar boşlukları nasıl ayarlanır',
        steps: [
          'Ayarlanacak uygulamayı yansıtın (örn. Waze).',
          'Ayarlar → Kenar boşlukları.',
          'Sol/sağ kenarlar doğru olana kadar yatay kaydırıcıyı hareket ettirin.',
          'Dikey için aynısı, sonra «Uygula» — uygulamayı yeniden başlatmadan canlı ayar.',
          'Ayar yalnızca bu uygulama için kaydedilir.',
        ],
      },
      note:
        '⚠️ Küme tipini değiştirirseniz, geri yüklemenin doğru modu kullanması için projeksiyonu durdurup yeniden başlatın.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Düzenler — kümede birden fazla uygulama',
      lead:
        'Düzenler modu kümeyi özel bölgelere ayırır, her biri kendi uygulamasını gösterir: örneğin solda Waze, sağda Spotify. Bölgeleri parmakla çizersiniz, her bölgeye bir uygulama bağlarsınız ve düzen tek dokunuşla — veya açılışta kendiliğinden — etkinleşir.',
      mockupLabel: 'Ekran 7\'yi gör (Düzenler)',
      featuresTitle: 'Özellikler',
      features: [
        {
          title: '✏️ Bölge çizme',
          text:
            'Tuval üzerinde (kümenin bir kopyası) parmağınızı sürükleyerek bir dikdörtgen çizin. Bir pencere açılır: ad, piksel hassasiyetinde konum/boyutlar ve bağlanacak uygulama.',
        },
        {
          title: '🔗 Uygulama bağlama',
          text:
            'Her bölge bir uygulamaya bağlanabilir: düzen etkinleştiğinde uygulama kendi bölgesinde otomatik başlar. Uygulamasız bir bölge serbest kalır — sonra oraya istediğinizi yerleştirin.',
        },
        {
          title: '✋ Taşıma ve yeniden boyutlandırma',
          text:
            'Bölgeyi sürükleyerek taşıyın; köşelerdeki beyaz tutamaçlar boyutlandırır. Kenarlar küme sınırlarına ve komşu bölgelere otomatik yapışır.',
        },
        {
          title: '✏️ Mevcut bölgeyi düzenleme',
          text:
            'Bir bölgeye dokunun (tuvalde veya alttaki çipinde): yeniden adlandırın, geometrisini ayarlayın, bağlı uygulamayı değiştirin veya silin. Bir bölgeye uzun basma = hızlı silme.',
        },
        {
          title: '💾 Kaydedilen düzenler',
          text:
            'İstediğiniz kadar düzen kaydedin («Nav+Media», «Üçlü ekran»…). Yan panel bunları Etkinleştir / Devre dışı bırak / Düzenle / Sil ile listeler.',
        },
        {
          title: '⭐ Favori ve otomatik başlatma',
          text:
            '«Favori» düğmesi (veya ana ekran seçici kartına dokunma), «Otomatik favori düzen»in DashCast açılışında etkinleştireceği düzeni belirler — projeksiyon dahil.',
        },
      ],
      howTo: {
        title: 'İlk düzeninizi oluşturun',
        steps: [
          'Ayarlarda «Düzenler modu»nu etkinleştirin.',
          'Düzenler sekmesini açın (yan çubuk).',
          'İlk bölgeyi çizmek için parmağınızı tuvalde sürükleyin (örn. sol yarı).',
          'Pencerede: adlandırın, «Uygulama bağla»ya dokunun → Waze\'i seçin → Ekle.',
          'İkinci bölgeyi çizin (sağ yarı), Spotify\'ı bağlayın.',
          '«Kaydet» → düzeni adlandırın (örn. Nav+Media).',
          'Seçmek için «Favori», sonra etkinleştirin: iki uygulama da kendi bölgesinde başlar.',
        ],
      },
      tipsTitle: 'İpuçları',
      tips: [
        '💡 «Otomatik favori düzen» (Ayarlar) ile birlikte, eksiksiz çoklu uygulama kurulumunuz her DashCast açılışında kendiliğinden kurulur.',
        '💡 Her seçici kartındaki mini önizleme düzenin gerçek bölgelerini gösterir — bir bakışta tanınır.',
        '💡 Bir uygulama bölgesinde görünmeyi reddediyor mu? Bazı uygulamalar en-boy oranını dayatır; 16:9\'a daha yakın bir bölge deneyin.',
      ],
      note:
        'ℹ️ Düzenler modu Proxy ADB Daemon\'a dayanır (otomatik başlatılır). İlk soğuk başlatma: uygulamalar görünmeden önce 6–8 sn bekleyin — bu, kümenin etkinleştirme dizisidir.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. HUD navigasyon okları (DiLink 3)',
      lead:
        'Ön cam Head-Up Display\'i dönüş oklarını destekleyen DiLink 3 araçlarında DashCast, navigasyon uygulamanızdan adım adım yönlendirmeyi HUD üzerinde çizebilir — manevra oku ve ona olan mesafe, doğrudan ön camda.',
      mockupLabel: 'Ekran 2\'yi gör (Ana)',
      featuresTitle: 'Nasıl çalışır',
      features: [
        {
          title: '🧭 Maps / Waze\'den yönlendirme',
          text:
            'DashCast, navigasyon uygulamanızın zaten gönderdiği adım adım bildirimi okur (Google Maps, Waze) ve manevra + mesafeyi arabanın CAN veri yolu üzerinden HUD\'a iletir. Ek bir uygulama gerekmez.',
        },
        {
          title: '🚗 Firmware bağımlı',
          text:
            'Yalnızca daha yeni DiLink 3 HUD firmware\'leri ok çizebilir. Sizinki çizemiyorsa oklar basitçe görünmez — DashCast, HUD donanımının sahip olmadığı bir yeteneği ekleyemez.',
        },
        {
          title: '➡️ Doğru yön simgeleri',
          text:
            'Düz / sol / sağ manevralar, dönüşe kalan canlı geri sayım mesafesiyle birlikte eşleşen HUD simgesine karşılık gelir.',
        },
      ],
      howTo: {
        title: 'HUD\'da oklar nasıl elde edilir',
        steps: [
          'DashCast\'e bildirim erişiminin verildiğinden emin olun (ilk kullanımda ister).',
          'Ön cam HUD\'unu açın ve BYD HUD menüsünde bir navigasyon görüntüleme moduna ayarlayın.',
          'Google Maps veya Waze\'de bir rota başlatın.',
          'Her dönüşe yaklaştıkça manevra oku ve mesafe HUD\'da görünür.',
        ],
      },
      note:
        'ℹ️ HUD okları bir DiLink 3 özelliğidir ve HUD firmware\'inize bağlıdır. Hiçbir şey görünmüyorsa HUD\'unuz ok desteğinden daha eski olabilir — bu bir donanım sınırıdır, DashCast hatası değil.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Wi-Fi Hotspot yardımcısı (DiLink 3)',
      lead:
        'DiLink 3\'te, arabayı internete kendi SIM\'iniz/telefonunuz üzerinden bağlıyorsanız, Hotspot yardımcısı navigasyon ve akışın çalışmaya devam etmesi için bu bağlantıyı canlı tutar. Yalnızca sizin için ilgili olduğunda gezinme çubuğunda görünür.',
      mockupLabel: 'Ekran 3\'ü gör (Ayarlar)',
      featuresTitle: 'Özellikler',
      features: [
        {
          title: '📶 Canlı tutma',
          text:
            'Araba uyandığında (örn. ACC açıldıktan sonra) Wi-Fi bağlantısını yeniden etkinleştirir, böylece her sürüşte manuel olarak yeniden açmanız gerekmez.',
        },
        {
          title: '👁️ Canlı durum',
          text:
            'Hotspot\'un açık olup olmadığını ve kaç istemcinin bağlı olduğunu gösterir, böylece arabanın gerçekten çevrimiçi olduğunu doğrulayabilirsiniz.',
        },
        {
          title: '⚙️ Yalnızca yararlı olduğunda gösterilir',
          text:
            'Hotspot girişi yalnızca DiLink 3\'te ve yalnızca Ayarlarda «Kendi SIM\'imi kullan» etkinken görünür. Diğer kurulumlarda gizli kalır.',
        },
      ],
      howTo: {
        title: 'Nasıl kullanılır',
        steps: [
          'Ayarlar → «Kendi SIM\'imi kullan»ın etkin olduğundan emin olun.',
          'Gezinme çubuğundan «Hotspot»u açın.',
          'Bağlantıyı başlatın / onaylayın — durum açık olduğunu gösterir.',
          'Araba bir sonraki uyanışında kendini yeniden etkinleştirir.',
        ],
      },
      note:
        'ℹ️ Bu yardımcı, kendi verinizle bağlanan DiLink 3 araçları içindir. Arabanızın kendi yerleşik veri planı varsa buna ihtiyacınız yoktur.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Bir sorun bildir (hata bildirici)',
      lead:
        'Klavyesiz, araç içi bir hata bildirici. Üç dokunuşta neyin yanlış gittiğini seçersiniz; DashCast sınırlı bir tanılama anlık görüntüsü (günlükler + sistem durumu) yakalar ve doğrudan destek kanalına gönderir — yazmak yok, kablo yok.',
      mockupLabel: 'Ekran 6\'yı gör (Rapor)',
      featuresTitle: 'Nasıl çalışır',
      features: [
        {
          title: '1️⃣ Kategori',
          text:
            'Hangi alanın etkilendiğini seçin: ayna, bir uygulama, ses, bağlantı, donma, HUD… Altı büyük kutu, yalnızca dokunma.',
        },
        {
          title: '2️⃣ Uygulama',
          text:
            'DashCast, kümede o anda bulunan uygulamayı otomatik algılar ve sunar, ayrıca «Belirli bir uygulama yok» ve «Diğer».',
        },
        {
          title: '3️⃣ Sorun',
          text:
            'Kısa bir listeden en yakın belirtiyi seçin. İsteğe bağlı bir serbest metin kutusu, isterseniz ayrıntı eklemenize olanak tanır — ama asla zorunlu değildir.',
        },
        {
          title: '📎 Otomatik tanılama',
          text:
            'Rapor, son günlükleri ve sorun anındaki sistem/küme durumunu bir araya getirir — desteğin tam olarak ihtiyaç duyduğu şey, sizin için yakalanır.',
        },
        {
          title: '🚀 Tek dokunuşla gönder',
          text:
            'Bir destek kanalı yapılandırılmışsa rapor doğrudan yüklenir; aksi halde DashCast, Telegram, e-posta veya GitHub ile göndermeniz için Android paylaşım menüsünü açar.',
        },
        {
          title: '📺 Her yerden',
          text:
            'Yüzen 📺 düğmesi ve gezinme çubuğu bildiriciyi açar, böylece başka bir uygulama yansıtılırken bile rapor gönderebilirsiniz.',
        },
      ],
      howTo: {
        title: 'Nasıl rapor gönderilir',
        steps: [
          'Hata bildiriciyi açın (gezinme çubuğu veya yüzen düğme).',
          'Soruna uyan kategoriye dokunun.',
          'Uygulamayı onaylayın (veya «Belirli bir uygulama yok»u seçin).',
          'En yakın sorunu seçin; yararlıysa bir not ekleyin.',
          'Gönder\'e dokunun — tanılama otomatik olarak desteğe gider.',
        ],
      },
      note:
        '🔒 Göndermeden önce DashCast şasi numarasını, Wi-Fi ağ adlarını, donanım adreslerini ve konumları çıkarır. Geriye DashCast günlüğü ve Android sistem günlüğü kalır; oldukları gibi kopyalanırlar ve diğer uygulamaların yazdıklarını içerirler. Herhangi bir gönderimden önce bir kez sorulur; reddedersen araçtan hiçbir şey çıkmaz.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Sistem raporu',
      lead:
        'Salt okunur panel: sürümler, algılanan ekranlar ve DashCast hizmetlerinin canlı durumu. Bir şey anormal göründüğünde bakılacak ilk ekran.',
      mockupLabel: 'Ekran 5\'i gör (Sistem)',
      featuresTitle: 'Gösterilen bilgiler',
      features: [
        {
          title: '🖥️ Ekranlar',
          text:
            'Ana ekran (çözünürlük, yoğunluk) ve kümenin sanal ekranı, gerçek zamanlı durumuyla.',
        },
        {
          title: '⚙️ Hizmetler',
          text:
            'ClusterService (projeksiyon), MirrorDaemon (ayna), Proxy ADB Daemon (ayrıcalıklı işlemler), AdbLocalClient (ADB tüneli) — her biri yeşil/kırmızı nokta ve durduğunda yeniden başlatma düğmesiyle.',
        },
        {
          title: '📱 Sürümler',
          text:
            'Kurulu DashCast sürümü, BYD firmware, Android/API sürümü, DiLink derleme kimlikleri.',
        },
        {
          title: '🔁 Projeksiyon tekrarı',
          text:
            'Kümenin tam etkinleştirme dizisini yeniden oynatma düğmesi (gösterge paneli ara durumda takılı kaldıysa kullanışlı).',
        },
      ],
      tipsTitle: 'İpuçları',
      tips: [
        '💡 Düzenler modu için «Proxy ADB Daemon» yeşil (RUN) olmalı — değilse satırına dokunarak yeniden başlatın.',
        '💡 Bir şey anormal görünüyorsa önce bu ekranı kontrol edin, sonra hata bildiriciden bir rapor gönderin.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Günlük',
      lead:
        'DashCast\'in dahili günlüğü: her önemli eylem (projeksiyonlar, geri yüklemeler, ADB hataları, güncellemeler) sürekli izlenir. Beklenmedik davranışı anlamak için kullanışlı; ayrıca hata bildiricinin sizin için eklediği veridir.',
      mockupLabel: 'Ekran 6\'yı gör (Günlük)',
      featuresTitle: 'Özellikler',
      features: [
        {
          title: '🔍 Filtreler',
          text:
            'Düzeye (DEBUG / INFO / WARN / ERROR) veya anahtar kelimeye göre filtreleyin (örn. «ADB», «Maps», «error»).',
        },
        {
          title: '🎨 Renk kodu',
          text:
            '🟢 INFO — normal işlem. 🟠 WARN — dikkat. 🔴 ERROR — başarısızlık. ⚪ DEBUG — teknik ayrıntı.',
        },
        {
          title: '📤 Paylaş',
          text:
            'Günlüğü .txt olarak dışa aktarır ve Android paylaşım menüsünü açar. DashCast sürümünü ve BYD modelini içerir.',
        },
        {
          title: '⏰ Zaman damgaları',
          text:
            'Her satır yerel saatle (HH:mm:ss.mmm) başlar; uzun işlemler ölçülür.',
        },
      ],
      howTo: {
        title: 'Hata bildiriciyi tercih edin',
        steps: [
          'Çoğu sorun için hata bildiriciyi (bölüm 7) kullanın — günlüğü ve sistem durumunu otomatik olarak yakalar.',
          'Günlük ekranı, izi kendiniz okumak veya yalnızca ham günlüğü paylaşmak istediğinizde buradadır.',
        ],
      },
      note:
        '🔒 Günlük, DashCast\'in yaptıklarını kaydeder; paket adları ve çalıştırdığı komutların çıktısı dahil. Bu ekrandan paylaşmak onu olduğu gibi gönderir — şasi numarasını, ağ adlarını ve konumları çıkaran süzme hata raporlarında çalışır, burada değil.',
    },
  ],

  faq: {
    title: '10. SSS — Sık sorulan sorular',
    items: [
      {
        question: '❓ Bir uygulamaya dokunduğumda küme siyah kalıyor',
        answer:
          'Üç olası neden: (1) kablosuz ADB devre dışı — BYD Ayarlar → Geliştirici kontrol edin. (2) Durmuş bir hizmet — Sistem ekranı, kırmızı satırı yeniden başlatın. (3) Uygulama az önce çöktü — simgesine tekrar dokunun. Hâlâ takılı mı? Hata bildiriciden bir rapor gönderin.',
      },
      {
        question: '❓ Görüntü kümeden taşıyor / kırpılıyor',
        answer:
          'Ayarlar → Kenar boşlukları: kenarlar doğru olana kadar yatay/dikey kaydırıcıları ayarlayın. Uygulama başına kaydedilir — yalnızca bir kez yaparsınız.',
      },
      {
        question: '❓ Orijinal BYD paneline nasıl dönerim?',
        answer:
          'Ana ekranda «Projeksiyonu durdur»a dokunun: DashCast, yerel kümeyi Ayarlarda tanımlanan boyutla geri yükler. Panel takılı görünüyorsa: Sistem ekranı → «Projeksiyon tekrarı», sonra tekrar durdurun.',
      },
      {
        question: '❓ Favori düzenim açılışta başlamıyor',
        answer:
          'Ayarlardaki üç koşulu kontrol edin: «Düzenler modu» etkin, «Otomatik favori düzen» etkin ve bir düzen ⭐ favori olarak işaretli (ana ekran seçici veya Düzenler sekmesindeki Favori düğmesi). Soğuk başlatmada 6–8 sn bekleyin.',
      },
      {
        question: '❓ HUD\'umda navigasyon oku yok',
        answer:
          'HUD okları bir DiLink 3 özelliğidir ve bunları çizebilen bir HUD firmware\'i gerektirir. Bildirim erişiminin verildiğinden, HUD\'un bir navigasyon görüntüleme modunda açık olduğundan ve Maps/Waze\'de bir rotanın çalıştığından emin olun. Hiçbir şey görünmüyorsa HUD firmware\'iniz muhtemelen ok desteğinden daha eskidir — bir donanım sınırı, hata değil.',
      },
      {
        question: '❓ Hotspot yardımcısına ihtiyacım var mı?',
        answer:
          'Yalnızca DiLink 3\'te, arabayı kendi SIM\'iniz/telefon bağlantınız üzerinden çevrimiçi yapıyorsanız. Bu bağlantıyı uyanışlar boyunca canlı tutar. Arabanızın kendi veri planı varsa yok sayın — «Kendi SIM\'imi kullan» açık olmadıkça gizli kalır.',
      },
      {
        question: '❓ Güncellemeler artık nasıl kurulur?',
        answer:
          'DashCast her açılışta GitHub\'ı kontrol eder. Bir güncelleme indirildiğinde sessizce kurulur ve uygulamayı kendisi yeniden başlatır — «Kur?» istemi yok. Bunun mümkün olmadığı bir arabada normal sistem yükleyicisine geri döner. Bu özelliğe sahip bir sürüme geçtikten sonraki ilk güncelleme yine de bir kez sorabilir.',
      },
      {
        question: '❓ DashCast 12 V aküyü boşaltır mı?',
        answer:
          'Hayır — DashCast arabayla birlikte durur. Motor kapalıyken hiçbir arka plan hizmeti aktif kalmaz.',
      },
      {
        question: '❓ Hangi uygulamalar kümede çalışır?',
        items: [
          '✅ Navigasyon: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Medya: Spotify, YouTube, YouTube Music (yatay tercih edin).',
          '✅ Sistem: kamera, hava durumu, takvim.',
          '⚠️ DRM\'li uygulamalar (Netflix, Disney+, Prime Video): sanal ekranda görüntülenmeyi reddedebilir — Android sınırlaması, DashCast değil.',
        ],
      },
      {
        question: '❓ Güncellemeler: kararlı mı beta mı?',
        answer:
          'Kararlı kanal (varsayılan) yayından önce araçta test edilir. Beta kanalı (Ayarlar → Güncellemeler → «Ön sürümleri dahil et») yenilikleri derlenir derlenmez alır — erken test için kullanışlı, geçici gerileme riskiyle.',
      },
      {
        question: '❓ Katkıda bulunmak veya hata bildirmek istiyorum',
        answer:
          'En hızlı yol için uygulama içi hata bildiriciyi kullanın (tanılamayı sizin için ekler). Kod ve özellik istekleri için: GitHub https://github.com/Kiroha/byd-dashcast — hatalar için Issues, sorular için Discussions.',
      },
    ],
  },

  footer:
    'DashCast, MIT lisansı altında dağıtılan açık kaynaklı bir projedir. BYD Auto Co., Ltd. ile bağlantısı yoktur.',
};
