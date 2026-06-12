export default {
  code: 'tr',
  flag: '🇹🇷',
  name: 'Türkçe',
  title: 'DashCast — Kullanım Kılavuzu',
  manualName: 'Kullanım Kılavuzu',
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 İçindekiler',

  intro: {
    title: '0. Giriş',
    lead:
      'DashCast, BYD merkez ekranınızdaki herhangi bir Android uygulamasını gösterge paneline (dijital kümeye) yansıtır. Maps, Waze, Spotify veya ABRP doğrudan direksiyonun arkasında — Düzenler modu ile aynı anda birden fazla uygulama, her biri kendi bölgesinde. Hepsi sistemi değiştirmeden.',
    bullets: [
      '✅ BYD Seal EU ile uyumlu (DiLink 3.0, firmware Di3.0 / 6125F).',
      '✅ Sistem değişikliği yok: DashCast normal bir uygulama gibi kurulur.',
      '✅ TCP üzerinden yerel ADB — ilk yetkilendirmeden sonra bilgisayar gerekmez.',
      '✅ 13 arayüz dili, ilk açılışta seçilir.',
      '✅ Gerçek zamanlı dokunmatik ayna: kümeyi merkez ekrandan kontrol edin.',
      '✅ Düzenler modu: kümede yan yana birden fazla uygulama, parmakla çizilen bölgeler.',
      '✅ Otomatik başlatma: DashCast açılır açılmaz projeksiyon + uygulama (veya favori düzen).',
      '✅ Kenar boşlukları (overscan) uygulama başına kaydedilir.',
      '✅ Yerleşik OTA güncellemeleri (isteğe bağlı beta kanalı).',
    ],
    note:
      '💡 Tek önkoşul: BYD Ayarlar → Geliştirici bölümünde kablosuz ADB hata ayıklamayı etkinleştirin. İlk açılışta «Hata ayıklamaya izin verilsin mi?» penceresi görünür — «Her zaman izin ver» işaretleyin ve onaylayın. Bunu bir daha asla yapmanız gerekmez.',
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
        'DashCast\'in merkez ekranı. Solda: arama, filtreler ve favorilerle tüm uygulamalarınız. Sağda: kümenin gerçek zamanlı önizlemesi, Tam ekran ayna / Projeksiyonu durdur düğmeleri ve favori çoklu uygulama düzeninizi seçmek için düzen karuseli.',
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
            'Sağ panel kümeyi canlı yansıtır. Önizlemedeki dokunuşlarınız yansıtılan uygulamaya iletilir — kaydırma, yakınlaştırma, klavye, her şey çalışır.',
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
          title: '🗂️ Düzen karuseli',
          text:
            'Düğmelerin altında her kart, bir düzenin bölgelerinin mini önizlemesini gösterir. Bir karta dokunarak favori düzen yapın (yıldız + mavi kenarlık). «Serbest mod» düzenleri devre dışı bırakır; «＋ Yönet» düzenleyiciyi açar.',
        },
        {
          title: '📺 Yüzen düğme',
          text:
            'Bir 📺 düğmesi diğer uygulamaların üzerinde kalır: dokunma = aynayı aç, uzun basma = son yansıtılan uygulamalar arasında hızlı geçiş.',
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
        '💡 Favori düzen: karuselde seçilen kart, otomatik başlatmanın etkinleştireceği karttır (Düzenler bölümüne bakın).',
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
            'Etkinse, DashCast arabayla başlar ve son yansıtılan uygulamayı geri yükler. Aksi halde BYD çekmecesinden başlatın.',
        },
        {
          title: '🗂️ Düzenler modu',
          text:
            'Özel bölgelerle çoklu uygulama projeksiyonunu etkinleştirir (otomatik yönetilen Proxy ADB Daemon gerektirir). Ana ekranda karuseli ve Düzenler sekmesini gösterir.',
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
          title: '📦 OTA güncellemeleri',
          text:
            'DashCast her açılışta GitHub\'ı kontrol eder. Beta kanalı için «Ön sürümleri dahil et» işaretleyin (yenilikler daha erken, daha az kararlılık).',
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
            'Tuval üzerinde (1920×720 kümenin kopyası) parmağınızı sürükleyerek bir dikdörtgen çizin. Bir pencere açılır: ad, piksel hassasiyetinde konum/boyutlar ve bağlanacak uygulama.',
        },
        {
          title: '🔗 Uygulama bağlama',
          text:
            'Her bölge bir uygulamaya bağlanabilir: düzen etkinleştiğinde uygulama kendi bölgesinde otomatik başlar. Uygulamasız bölge serbest kalır.',
        },
        {
          title: '✋ Taşıma ve yeniden boyutlandırma',
          text:
            'Bölgeyi sürükleyerek taşıyın; köşelerdeki beyaz tutamaçlar boyutlandırır. Kenarlar küme sınırlarına ve komşu bölgelere otomatik yapışır.',
        },
        {
          title: '✏️ Mevcut bölgeyi düzenleme',
          text:
            'Bir bölgeye dokunun (tuvalde veya alttaki çipinde): yeniden adlandırın, geometrisini ayarlayın, bağlı uygulamayı değiştirin veya silin. Uzun basma = hızlı silme.',
        },
        {
          title: '💾 Kaydedilen düzenler',
          text:
            'İstediğiniz kadar düzen kaydedin («Nav+Media», «Üçlü ekran»…). Yan panel bunları Etkinleştir / Devre dışı bırak / Düzenle / Sil ile listeler.',
        },
        {
          title: '⭐ Favori ve otomatik başlatma',
          text:
            '«Favori» düğmesi (veya ana ekran karuselindeki karta dokunma), «Otomatik favori düzen»in DashCast açılışında etkinleştireceği düzeni belirler — projeksiyon dahil.',
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
        '💡 Her karusel kartının mini önizlemesi düzenin gerçek bölgelerini gösterir — bir bakışta tanınır.',
        '💡 Bir uygulama bölgesinde görünmeyi reddediyor mu? Bazı uygulamalar en-boy oranını dayatır; 16:9\'a daha yakın bir bölge deneyin.',
      ],
      note:
        'ℹ️ Düzenler modu Proxy ADB Daemon\'a dayanır (otomatik başlatılır). İlk soğuk başlatma: uygulamalar görünmeden önce 6–8 sn bekleyin — bu, kümenin etkinleştirme dizisidir.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Tanılama',
      lead:
        'Projeksiyonun beklendiği gibi çalışmadığı durumlar için dahili panel. Çoğu kullanıcının asla ihtiyacı olmayacak — destek ve hata ayıklama için var.',
      mockupLabel: 'Ekran 4\'ü gör (Tanılama)',
      featuresTitle: 'Mevcut araçlar',
      features: [
        {
          title: 'Bağlantı testleri',
          text:
            'Yerel ADB tünelini (localhost:5555), ClusterService durumunu ve kümenin sanal ekranının varlığını kontrol eder.',
        },
        {
          title: 'Platform sondaları',
          text:
            'DiLink algılama (2/3/4/5), ekran envanteri, BYD araç API örnekleme (hız, enerji) ve BYDAUTO izin durumu.',
        },
        {
          title: 'Paylaşılabilir rapor',
          text:
            'Destek için metin olarak dışa aktarılabilir eksiksiz bir rapor üretir (sistem, ekranlar, hizmetler, izinler, daemon metrikleri).',
        },
      ],
      howTo: {
        title: 'Bu sekme ne zaman kullanılır',
        steps: [
          'Uygulamaya dokunduktan sonra küme siyah kalıyor → ClusterService ve sanal ekranı kontrol edin.',
          'Uygulama «ADB kullanılamıyor» bildiriyor → «ADB testi» düğmesi.',
          'Destek rapor istiyor → oluşturun ve paylaşın.',
        ],
      },
      note: 'ℹ️ Düğmeler, açıkça belirtilmedikçe salt okunur testlerdir.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Sistem raporu',
      lead:
        'Salt okunur panel: sürümler, algılanan ekranlar ve DashCast hizmetlerinin canlı durumu. Bir şey anormal göründüğünde bakılacak ilk ekran.',
      mockupLabel: 'Ekran 5\'i gör (Sistem)',
      featuresTitle: 'Gösterilen bilgiler',
      features: [
        {
          title: '🖥️ Ekranlar',
          text:
            'Ana ekran (çözünürlük, yoğunluk) ve kümenin sanal ekranı (1920×720), gerçek zamanlı durumla.',
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
        '💡 Tam rapor, bir hata bildirimini desteklemek için bu ekrandan dışa aktarılabilir.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Günlük',
      lead:
        'DashCast\'in dahili günlüğü: tüm önemli eylemler (projeksiyonlar, geri yüklemeler, ADB hataları, güncellemeler) sürekli izlenir. Beklenmedik davranışı anlamak veya desteğe rapor sağlamak için kullanışlı.',
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
        title: 'Hata raporu gönderme',
        steps: [
          'Sorunu yeniden oluşturun.',
          'Günlük → «Paylaş»ı açın.',
          'Kanalınızı seçin (Telegram, e-posta, GitHub Issues).',
          'Ekli dosya izi ve bağlamı içerir (sürüm, model, firmware).',
        ],
      },
      note:
        '🔒 Hiçbir kişisel veri (kişiler, GPS konumu, uygulama içeriği) kaydedilmez — yalnızca DashCast eylemleri ve teknik dönüş kodları.',
    },
  ],

  faq: {
    title: '8. SSS — Sık sorulan sorular',
    items: [
      {
        question: '❓ Bir uygulamaya dokunduğumda küme siyah kalıyor',
        answer:
          'Üç olası neden: (1) kablosuz ADB devre dışı — BYD Ayarlar → Geliştirici kontrol edin. (2) Durmuş bir hizmet — Sistem sekmesi, kırmızı satırı yeniden başlatın. (3) Uygulama az önce çöktü — simgesine tekrar dokunun.',
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
          'Ayarlardaki üç koşulu kontrol edin: «Düzenler modu» etkin, «Otomatik favori düzen» etkin ve bir düzen ⭐ favori olarak işaretli (ana ekran karuseli veya Düzenler sekmesindeki Favori düğmesi). Soğuk başlatmada 6–8 sn bekleyin.',
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
          'GitHub: https://github.com/Kiroha/byd-dashcast — hatalar için Issues, sorular için Discussions. Tanıyı hızlandırmak için bir Günlük dışa aktarımı ekleyin.',
      },
    ],
  },

  footer:
    'DashCast, MIT lisansı altında dağıtılan açık kaynaklı bir projedir. BYD Auto Co., Ltd. ile bağlantısı yoktur.',
};
