export default {
  code: 'tr',
  flag: '🇹🇷',
  name: 'Türkçe',
  title: 'DashCast — Kullanıcı Kılavuzu',
  manualName: 'Kullanıcı Kılavuzu',
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 İçindekiler',

  intro: {
    title: '0. Giriş',
    lead:
      "DashCast, BYD merkezi ekrandan herhangi bir Android uygulamasını dijital gösterge paneli (cluster) üzerine yansıtmanızı sağlar. Maps, Waze, Spotify veya YouTube\'u doğrudan direksiyonun arkasında görebilir, aynı zamanda yerel BYD göstergelerine (hız, göstergeler, menzil) her an erişebilirsiniz.",
    bullets: [
      "✅ BYD Seal EU ile uyumlu (DiLink 3.0, Di3.0 / 6125F yazılım).",
      "✅ Sistem değişikliği yok: DashCast normal bir uygulama gibi kurulur.",
      "✅ Yerel TCP ADB — ilk yetkilendirmeden sonra bilgisayara gerek yok.",
      "✅ 12 dil, ilk başlatmada seçilir.",
      "✅ Yerleşik OTA güncellemeleri (isteğe bağlı alfa kanal).",
      "✅ Overscan kenar boşlukları her uygulama için ayrı saklanır.",
      "✅ Cluster\'ı merkezi ekrandan kontrol edebilen dokunmatik tam ekran ayna.",
      "✅ Bölünmüş mod (cluster üzerinde yan yana iki uygulama).",
    ],
    note:
      "💡 Tek seferlik ön koşul: BYD Ayarlar → Geliştirici altında kablosuz ADB hata ayıklamayı etkinleştirin. İlk açılışta « Hata ayıklamaya izin ver? » diyaloğu görünür — « Her zaman izin ver »\'i işaretleyin ve onaylayın. Bu adımı asla tekrarlamanız gerekmez.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Hoş geldiniz ekranı — dil seçimi',
      lead:
        "İlk açılışta DashCast, mevcut 12 dilden oluşan bir ızgara gösterir. İstediğinize dokunun; seçim kaydedilir ve hoş geldiniz ekranı bir daha görünmez. Dili istediğiniz zaman Ayarlar → Dil bölümünden değiştirebilirsiniz.",
      mockupLabel: 'Ekran 1\'i aç (Hoş geldiniz)',
      featuresTitle: 'Detaylar',
      features: [
        {
          title: '12 desteklenen dil',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Seçilen dil yeniden başlatma olmadan anında uygulanır.",
        },
        {
          title: 'Otomatik okuma yönü',
          text:
            "Arapça otomatik olarak sağdan sola düzene (RTL) geçer: gezinme çubuğu sağa taşınır, listeler ters çevrilir, simgeler okunabilir kalır.",
        },
        {
          title: 'Her zaman değiştirilebilir',
          text:
            "Sonradan dili değiştirmek için: yan çubuğun üstündeki DashCast logosuna uzun basın → 🌐 Dil. Yeni dil anında uygulanır.",
        },
      ],
      howTo: {
        title: 'Nasıl yapılır',
        steps: [
          "DashCast\'i başlatın (BYD uygulama çekmecesindeki mavi simge).",
          "Hoş geldiniz ekranı 4×3\'lük dil ızgarasını gösterir.",
          "Dilinize dokunun. Arayüz hemen değişir.",
          "Ana ekran açılır — DashCast kullanıma hazır.",
        ],
      },
      note:
        "ℹ️ Bir yansıtma çalışırken dili değiştirirseniz, yansıtma kesintisiz devam eder; yalnızca DashCast arayüzü çevrilir.",
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Ana ekran — Uygulamalar ve Cluster',
      lead:
        "DashCast\'in merkezi ekranı. Solda arama, kategori filtreleri ve favorilerle birlikte tüm yüklü uygulamaların listesi. Sağda ana eylemleriyle birlikte cluster\'ın canlı önizlemesi: tam ekran önizleme, ekran görüntüsü, yeniden bağlan, yansıtmayı durdur.",
      mockupLabel: 'Ekran 2\'yi aç (Ana)',
      featuresTitle: 'Yapabileceğiniz her şey',
      features: [
        {
          title: '🔍 Arama çubuğu',
          text:
            "Listeyi anında filtrelemek için birkaç harf yazın (hem uygulama adında hem pakette arar). Sağdaki ▦ düğmesi liste/ızgara görünümleri arasında geçiş yapar.",
        },
        {
          title: '🏷️ Kategori filtreleri',
          text:
            "Renkli çipler (Tümü / Navigasyon / Multimedya / İletişim / Sistem) uygulamalarınızı otomatik gruplar. Parantez içindeki sayı kaç uygulamanın görünür olduğunu söyler.",
        },
        {
          title: '⭐ Sabitlenmiş favoriler',
          text:
            "« Favoriler » bölümü en çok kullandığınız uygulamaları üstte tutar. Eklemek/çıkarmak için: uygulamaya uzun basın → ⭐ Favorilere ekle/çıkar.",
        },
        {
          title: '👆 Kısa dokunuş — yansıt',
          text:
            "Bir uygulamaya dokunarak hemen cluster\'a gönderin. Yansıtma henüz çalışmıyorsa otomatik başlar (cluster ısınma ~2 sn).",
        },
        {
          title: '👆⏱️ Uzun basış — eylemler menüsü',
          text:
            "Herhangi bir uygulamaya basılı tutarak tam ekran menü açın: ⭐ Favori, Otomatik başlat (yansıtmayla), Cluster / ana ekrana taşı, ✕ Zorla durdur.",
        },
        {
          title: '🚦 Cluster canlı önizleme',
          text:
            "Sağ panel cluster\'da görüneni yansıtır (hız, vites, batarya, menzil). Gecikme (12 ms) bağlantının iyi olduğunu doğrular.",
        },
        {
          title: '👁️ Tam ekran önizleme',
          text:
            "« Tam ekran önizleme »\'ye dokunarak canlı önizlemeyi tüm merkezi ekrana yayın. Maps\'te tam klavyeyle adres yazmak için ideal: her giriş cluster\'a yansıtılır.",
        },
        {
          title: '📸 Ekran görüntüsü',
          text:
            "« Yakala » düğmesi mevcut cluster görünümünü PNG olarak /sdcard/Pictures/DashCast/ altına kaydeder. Bir rotayı paylaşmak veya sorun belgelemek için faydalıdır.",
        },
        {
          title: '↻ Yeniden bağlan',
          text:
            "Yansıtılan uygulama dondu ya da yanıt vermiyorsa « Yeniden bağlan » video akışını orijinal cluster\'a dokunmadan kurar.",
        },
        {
          title: '⏹ Aynayı durdur',
          text:
            "Yansıtmayı temiz şekilde sonlandırır. Kısa dokunuş = yumuşak durdurma (cluster ADB üzerinden yerel BYD\'ye döner). Uzun basış = Ayarlar\'daki cluster boyutuyla geri yüklemeyi zorlayan « Orijinal cluster\'ı geri yükle » seçeneği bulunan zenginleştirilmiş menü.",
        },
      ],
      howTo: {
        title: 'Bir uygulamayı cluster\'a nasıl yansıtırsınız',
        steps: [
          "Ana ekranda istediğiniz uygulamayı bulun (örn. Maps).",
          "Simgesine dokunun → yansıtma başlar, cluster ~2 sn içinde uygulamayı gösterir.",
          "Sağ panel cluster\'da görüneni canlı gösterir.",
          "Metin yazmak için (adres aramak): « Tam ekran önizleme »\'ye dokunun → uygulama merkezi ekrana yayılır → adresi yazın → her şey cluster\'a yansır.",
          "Durdurmak için: « Aynayı durdur »\'a dokunun (cluster yerel BYD\'ye döner).",
        ],
      },
      tipsTitle: 'İpuçları',
      tips: [
        "💡 Otomatik başlat: Bir uygulamada bu anahtarı açarak DashCast her başladığında otomatik yansıtın.",
        "💡 Bölünmüş mod: ikinci uygulamanın uzun basış menüsünden « Bölünmüş gönder »\'i seçerek cluster\'da yan yana 2 uygulama görüntüleyin.",
        "💡 Kenar boşlukları: uygulama cluster\'dan taşıyorsa Ayarlar → Kenar boşlukları açıp kaydırıcıları ayarlayın. Uygulama başına saklanır.",
        "💡 Dokunmatik tam ekran: tam ekran modunda merkezi ekrandaki parmaklarınız uygulamayı gerçekten yönetir — klavye, kaydırma, hareketler tümü çalışır.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ayarlar',
      lead:
        "Ayarlar ekranı küresel seçenekleri ve yansıtma görüntü ayarını bir araya getirir. Sol çubuk her zaman erişilebilir kalır — Uygulamalar, Ayarlar, Tanı, Sistem ve Günlük arasında konumunuzu kaybetmeden geçebilirsiniz.",
      mockupLabel: 'Ekran 3\'ü aç (Ayarlar)',
      featuresTitle: 'Mevcut bölümler',
      features: [
        {
          title: '📺 Cluster türü',
          text:
            "Gösterge panelinizin fiziksel boyutunu seçin: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, varsayılan) veya 10.25″ (cmd 31). Bu değer özellikle « Orijinal cluster\'ı geri yükle » tarafından kullanılır.",
        },
        {
          title: '🌐 Dil',
          text:
            "12 dil mevcut. Anlık değişim — DashCast yeniden başlatması gerekmez.",
        },
        {
          title: '↔️ Yatay kenar boşluğu (overscan)',
          text:
            "Kaydırıcı 0–200 px. Cluster\'ınızdaki kırpılan kenarları telafi etmek için sol/sağa siyah çubuklar ekler. Uygulama başına saklanır — Maps 80 px kullanırken Spotify 0\'da kalabilir.",
        },
        {
          title: '↕️ Dikey kenar boşluğu (overscan)',
          text:
            "Kaydırıcı 0–200 px. Üst/alt için aynı. Birleştirilen kenar boşlukları VirtualDisplay düzeyinde uygulanır; uygulama kırpılan alanları « görmez ».",
        },
        {
          title: '✅ Uygula / 🔄 Sıfırla',
          text:
            "« Uygula » yeni kenar boşluklarını aktif yansıtmaya hemen aktarır. « Sıfırla » mevcut uygulamayı 0/0\'a döndürür.",
        },
        {
          title: '📦 OTA güncellemeleri',
          text:
            "DashCast GitHub Releases\'i otomatik kontrol eder. Alfa kanalı için « Pre-release\'leri dahil et »\'i işaretleyin (daha sık ama deneysel).",
        },
        {
          title: '🚗 Araçla otomatik başlatma',
          text:
            "Etkinleştirildiğinde DashCast araçla başlar ve son yansıtılan uygulamayı geri yükler. Aksi halde elle başlatırsınız.",
        },
      ],
      howTo: {
        title: 'Bir uygulamanın kenar boşluklarını ince ayar nasıl yapılır',
        steps: [
          "Ayarlanacak uygulamayı yansıtın (örn. Waze).",
          "Ayarlar → Kenar boşlukları açın.",
          "Sol/sağ kenarlar uygun olana kadar yatay kaydırıcıyı hareket ettirin.",
          "Aynısını dikey kaydırıcıyla yapın.",
          "« Uygula »\'ya dokunun → yansıtma canlı güncellenir, uygulama yeniden başlamaz.",
          "Ayar yalnızca o uygulama için saklanır (her uygulamanın kendi kenar boşlukları olur).",
        ],
      },
      note:
        "⚠️ Cluster türünü değiştirirseniz, referans değerlerin yeniden hesaplanması için DashCast\'i yeniden başlatın.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Tanılama',
      lead:
        "Tanılama sekmesi, yansıtmanın beklendiği gibi davranmadığı durumlar için ayrılmış dahili bir paneldir. Çoğu kullanıcı bunu hiç kullanmaz — destek ve hata ayıklama içindir.",
      mockupLabel: 'Ekran 4\'ü aç (Tanı)',
      featuresTitle: 'Araçlar',
      features: [
        {
          title: 'ClusterService durumu',
          text:
            "Yansıtmayı yöneten Android servisinin çalıştığını doğrular. « bağlanmadı » durumunda bir düğme yeniden başlatır.",
        },
        {
          title: 'VirtualDisplay durumu',
          text:
            "Cluster için oluşturulan sanal ekranın ID\'sini, çözünürlüğünü ve bir Qt Surface bağlı olup olmadığını gösterir.",
        },
        {
          title: 'Yerel ADB bağlantısı',
          text:
            "localhost:5555\'e ADB tünelinin hızlı testi. Test başarısızsa genelde BYD ayarlarında kablosuz hata ayıklama kapatılmıştır.",
        },
        {
          title: 'Hedeflenmiş logcat',
          text:
            "DashCast / AutoContainer / xdja\'ya filtrelenmiş son 200 logcat satırını yakalar. « Paylaş » düğmesi raporu gönderir.",
        },
      ],
      howTo: {
        title: 'Bu sekme ne zaman kullanılır',
        steps: [
          "Bir uygulamaya dokunduktan sonra cluster siyah kalıyor → ClusterService ve VirtualDisplay durumunu kontrol edin.",
          "Uygulama « ADB kullanılamıyor » diyor → Tanı sekmesi → « ADB Test » düğmesi.",
          "Destek bir rapor istiyor → Tanı → « logcat paylaş ».",
          "Bir güncelleme yeni yüklendi ve çalışan sürümü doğrulamak istiyorsunuz.",
        ],
      },
      note:
        "ℹ️ Bu sekme tek başına bir şey değiştirmez: aksi belirtilmedikçe düğmeler salt okunur testler çalıştırır.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Sistem Bilgileri',
      lead:
        "Donanım/yazılım ortamınız hakkında salt okunur panel. DashCast sürümü, BYD yazılımı, Android sürümü ve cluster kimliğinizi burada bulursunuz.",
      mockupLabel: 'Ekran 5\'i aç (Sistem)',
      featuresTitle: 'Gösterilen bilgiler',
      features: [
        {
          title: '🚗 Araç',
          text:
            "Algılanan BYD modeli, VIN (varsa), yazılım build (örn. Di3.0 / 6125F), build tarihi.",
        },
        {
          title: '📱 Android',
          text:
            "Android sürümü (10), API seviyesi (29), güvenlik yaması, DiLink build ID.",
        },
        {
          title: '🔌 DashCast',
          text:
            "Yüklü sürüm, versionCode, kanal (stable / alpha), son OTA kontrolü, sürüm notları bağlantısı.",
        },
        {
          title: '🖥️ Cluster',
          text:
            "Algılanan tür (8.8″ / 12.3″ / 10.25″), gerçek çözünürlük, aktif VirtualDisplay ID, aktif Qt paketi (com.xdja.containerservice).",
        },
        {
          title: '📦 Takip edilen uygulamalar',
          text:
            "Algılanan uygulama sayısı, sabitlenen favoriler, otomatik başlatma açık olanlar.",
        },
      ],
      tipsTitle: 'İpuçları',
      tips: [
        "💡 Bir satıra uzun basarak değeri panoya kopyalayın (hata raporu için yararlı).",
        "💡 Alttaki « Dışa aktar » düğmesi her şeyi metin dosyasına yazar (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Günlük',
      lead:
        "DashCast\'in dahili günlüğü: önemli eylemleri (yansıtma, geri yükleme, ADB hataları, güncellemeler) izler. Beklenmedik davranışı anlamak veya destek raporu göndermek için faydalıdır.",
      mockupLabel: 'Ekran 6\'yı aç (Günlük)',
      featuresTitle: 'Özellikler',
      features: [
        {
          title: '🔍 Filtre',
          text:
            "Yalnızca ilgili satırları tutmak için anahtar kelime girin (örn. « ADB », « Maps », « error »). Filtre büyük/küçük harfe duyarsızdır.",
        },
        {
          title: '🎨 Renk kodu',
          text:
            "🟢 INFO (yeşil) — normal işleyiş. 🟠 WARN (turuncu) — dikkat. 🔴 ERROR (kırmızı) — hata. ⚪ DEBUG (gri) — teknik detay.",
        },
        {
          title: '🗑 Temizle',
          text:
            "Günlüğü boşaltır. Sistem logcat izi etkilenmez — yalnızca bellekteki DashCast geçmişi silinir.",
        },
        {
          title: '📤 Paylaş',
          text:
            "Mevcut günlüğü .txt olarak dışa aktarır ve Android paylaşım sayfasını açar (e-posta, Telegram, dosya). DashCast sürümü ve BYD modelini otomatik içerir.",
        },
        {
          title: '⏰ Zaman damgaları',
          text:
            "Her satırın başında yerel saat (HH:mm:ss.mmm) bulunur. Uzun süren işlemler (Maps başlatma, cluster geri yükleme) ölçülür ve gösterilir.",
        },
      ],
      howTo: {
        title: 'Hata raporu nasıl gönderilir',
        steps: [
          "Sorunu yeniden oluşturun (örn. uygulama başlatıldıktan sonra siyah kalıyor).",
          "Günlük\'ü açın.",
          "« Paylaş »\'a dokunun.",
          "Kanalı seçin (Telegram, e-posta, GitHub Issues).",
          "Eklenen .txt tam izlemeyi bağlamla birlikte içerir (sürüm, model, yazılım).",
        ],
      },
      note:
        "🔒 Hiçbir kişisel veri (kişiler, GPS konumu, uygulama içeriği) kaydedilmez — yalnızca DashCast eylemleri ve teknik dönüş kodları.",
    },
  ],

  faq: {
    title: '7. SSS — Sık Sorulan Sorular',
    items: [
      {
        question: '❓ Bir uygulamaya dokunduğumda cluster siyah kalıyor',
        answer:
          "Üç olası neden: (1) kablosuz ADB devre dışı — BYD Ayarlar → Geliştirici\'yi kontrol edin. (2) ClusterService çalışmıyor — Tanı sekmesi, « Yeniden başlat » düğmesi. (3) Uygulama yeni çöktü — sağ panelde « Yeniden bağlan »\'a dokunun.",
      },
      {
        question: '❓ Görüntü taşıyor / cluster\'da kırpılıyor',
        answer:
          "Ayarlar → Kenar boşlukları açın ve kenarlar düzgün olana kadar yatay/dikey kaydırıcıları ayarlayın. Uygulama başına saklanır — bir kez yapılır.",
      },
      {
        question: '❓ Orijinal BYD gösterge paneline nasıl dönerim?',
        answer:
          "« Aynayı durdur » üzerine kısa dokunmak %95 oranında yeterlidir. Cluster takılırsa aynı düğmeye uzun basın → menü → « Orijinal cluster\'ı geri yükle »: DashCast cluster türünüze uygun sendInfo dizisini zorlar.",
      },
      {
        question: '❓ DashCast 12 V aküyü tüketir mi?',
        answer:
          "Hayır — DashCast araç kapanırken otomatik olarak durur (Android.intent.action.SCREEN_OFF + BMS bağlantı kesme yayınları). Motor kapandıktan sonra arka planda hizmet kalmaz.",
      },
      {
        question: '❓ Katkıda bulunmak veya hata bildirmek istiyorum',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — hatalar için Issues, sorular için Discussions. Tanıyı hızlandırmak için her zaman bir Günlük dışa aktarımı ekleyin (Günlük → Paylaş).",
      },
      {
        question: '❓ Cluster\'da hangi uygulamalar çalışır?',
        items: [
          "✅ Navigasyon: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Multimedya: Spotify, YouTube, YouTube Music, Netflix (yatayı tercih edin).",
          "✅ İletişim: Telegram (yalnızca okuma), WhatsApp (bildirimler).",
          "✅ Sistem: kamera, hava durumu, takvim.",
          "⚠️ Widevine L1 DRM kullanan uygulamalar (Disney+, Prime Video) VirtualDisplay üzerinde işlemeyi reddedebilir — Android sınırlaması, DashCast değil.",
        ],
      },
      {
        question: '❓ Güncellemeler: stable mı alpha mı?',
        answer:
          "Stable kanal (varsayılan) yayınlanmadan en az 1 hafta araçta test edilir. Alpha (Ayarlar → Güncellemeler\'de etkinleştirilir) derlenir derlenmez yeni build\'leri alır — önceden test için iyi, ancak geçici regresyonlar getirebilir.",
      },
    ],
  },

  footer:
    'DashCast, GPL-3.0 lisansı altında dağıtılan açık kaynaklı bir projedir. BYD Auto Co., Ltd. ile bağlantılı değildir.',
};
