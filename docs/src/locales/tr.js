export default {
  "code": "tr",
  "flag": "🇹🇷",
  "name": "Türkçe",
  "title": "DashCast — Kullanım Kılavuzu",
  "meta": "v0.7.2 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "Kullanım Kılavuzu",
  "tocTitle": "📋 İçindekiler",
  "sections": [
    "1. Genel Bakış",
    "2. İlk Çalıştırma — Dil Seçimi",
    "3. Ana Ekran",
    "4. Gösterge Paneline Uygulama Yansıtmak",
    "5. Yansıtma Sırasında — Kontrol Paneli",
    "6. Yansıtmayı Durdurmak",
    "7. Ayarlar",
    "8. ⋮ Menüsü — Ek Araçlar",
    "9. SSS & Sorun Giderme"
  ],
  "overview": {
    "title": "1. Genel Bakış",
    "text": "DashCast, bilgi-eğlence ekranındaki herhangi bir uygulamayı BYD'nizin dijital gösterge paneline yansıtmanıza olanak tanıyan bir Android uygulamasıdır.",
    "bullets": [
      "✅ BYD Seal EU ile uyumlu",
      "✅ Sistem modifikasyonu gerektirmez",
      "✅ Yerel ADB",
      "✅ Otomatik uygulama bağlantı kopma tespiti",
      "✅ Dahili OTA güncellemeleri",
      "✅ Uygulama başına Overscan",
      "✅ Izgara veya Liste görünümü",
      "✅ Acil durdurma (Kırmızı Çarpı)"
    ],
    "note": "💡 Önkoşul: TCP ADB hata ayıklamasını etkinleştirin."
  },
  "firstLaunch": {
    "title": "2. İlk Çalıştırma — Dil Seçimi",
    "text": "İlk çalıştırmada, 12 mevcut dili 4×3 ızgara (3 sütun × 4 satır) ile sunan hoş geldiniz ekranı belirir. Dilini seçmek için dil düğmesine dokunun. Bu tercih kaydedilir — ⋮ → 🌐 Dil menüsü üzerinden dil değiştirilmedikçe ekran tekrar görünmez.",
    "welcomeSubtitle": "Dashboard Controller",
    "welcomeHint": "Dilinizi seçin",
    "caption": "Dil seçimi ekranı"
  },
  "main": {
    "title": "3. Ana Ekran",
    "text": "Ana ekran: Üstte durum çubuğu ve altta yüklü uygulamalar listesi.",
    "status": "① Dashboard: bağlı değil",
    "buttons": [
      "② Yansıtmayı Etkinleştir",
      "③ Yansıtmayı Durdur",
      "④ Orijinal Dashboard'u Geri Yükle",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ Yüklü uygulamalar",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Ana ekran",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Durum",
        "text": "Dashboard bağlantı durumunu gösterir."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Yansıtmayı Etkinleştir",
        "text": "Bağlantıyı kurar."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Yansıtmayı Durdur",
        "text": "Yansıtmayı durdurur."
      },
      {
        "tone": "gray",
        "marker": "④",
        "label": "Orijinal Göstergeler (⋮ menüsü)",
        "text": "⋮ menüsünden erişilir. Yansımayı DURDURUR ve yerel BYD gösterge panelini geri yükler (hız, göstergeler, menzil…). Kullanım sonunda 'Yansımayı Durdur' yerine tercih edilir."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "⋮ Menü",
        "text": "Ayarlar, Teşhis, vb."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "Uygulama listesi",
        "text": "Uygulamaı kümeye yansıtmak için üzerine dokunun. Uzun basın → uygulamaı sabitle (⭐, listenin başına taşır). 'Otomatik' onay kutusu bir uygulamaı otomatik başlatma için işaretler: yansıtma başladığında kümeye gönderilir. ✕ düğmesi ve ← / → okları yalnızca şu anda aktif uygulamada görünür (kümede veya ana ekranda)."
      }
    ]
  },
  "projection": {
    "title": "4. Gösterge Paneline Uygulama Yansıtmak",
    "steps": [
      "Yansıtmayı Etkinleştir'e dokunun.",
      "Uygulamaya dokunun.",
      "Kontrol paneli görünür."
    ],
    "activeStatus": "Dashboard: Maps ✓",
    "buttons": [
      "Yansıtmayı Etkinleştir",
      "📺 Ayna",
      "Yansıtmayı Durdur",
      "Orijinal Dashboard'u Geri Yükle",
      "⋮",
      "← Ana",
      "✕",
      "→ Cluster",
      "✕",
      "→ Cluster",
      "✕",
      "📐 Ayarla",
      "⬛⬛ Böl",
      "Gizle ▼"
    ],
    "listTitle": "Yüklü uygulamalar",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Cluster kontrolü",
    "controlApp": "Maps",
    "mirrorText": "Görüntü cluster üzerinde aktif ✓",
    "caption": "Ana ekran",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Yeşil",
        "text": "Aktif uygulama"
      },
      {
        "tone": "",
        "marker": "→",
        "label": "→ Cluster",
        "text": "Cluster'a gönder"
      },
      {
        "tone": "gray",
        "marker": "←",
        "label": "← Ana",
        "text": "Ana ekrana döndür"
      },
      {
        "tone": "teal",
        "marker": "📺",
        "label": "Ayna",
        "text": "Canlı önizleme"
      },
      {
        "tone": "red",
        "marker": "❌",
        "label": "Kırmızı Çarpı",
        "text": "Zorla durdur"
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Izgara / Liste",
        "text": "Görünümü değiştir"
      }
    ]
  },
  "control": {
    "title": "5. Kontrol Paneli",
    "intro": "Paneli kullanın.",
    "mirror": {
      "title": "5.1 Ayna",
      "text": "Canlı kopyayı görün.",
      "note": ""
    },
    "resize": {
      "title": "5.2 Ayarla",
      "text": "Kenar boşluklarını kırpın.",
      "note": ""
    },
    "relaunch": {
      "title": "5.5 Yeniden Başlat (↺)",
      "text": "↺ (turuncu) düğmesi, kümede o anda yansıtılan uygulamayı zorla durdurur ve ardından hemen yeniden başlatır. Uygulama donmuşsa veya kümede ekran bloke olduysa kullanışlıdır."
    },
    "split": {
      "title": "5.3 Böl",
      "text": "Ekranı iki uygulama arasında paylaştırın.",
      "items": [],
      "extra": ""
    },
    "hide": {
      "title": "5.4 Gizle",
      "text": "Paneli gizleyin."
    }
  },
  "stopping": {
    "title": "6. Yansıtmayı Durdurmak",
    "intro": "Durdurmak için düğmeleri kullanın.",
    "table": {
      "headers": [
        "Düğme",
        "Davranış",
        "Ne zaman"
      ],
      "rows": []
    },
    "warning": "Uyarı"
  },
  "settings": {
    "title": "7. Ayarlar",
    "intro": "Ayarlara ⋮ → ⚙️ Ayarlar üzerinden erişin. Ekranda üç bölüm bulunur:",
    "titleLabel": "Ayarlar",
    "clusterTypeLabel": "Küme türü",
    "clusterOptions": [
      "8.8 inç",
      "12.3 inç (cmd=30) — Seal EU",
      "10.25 inç"
    ],
    "marginsLabel": "Görüntü kenar boşlukları",
    "horizontalMarginLabel": "Sol / Sağ:",
    "verticalMarginLabel": "Üst / Alt:",
    "applyButton": "Uygula",
    "resetButton": "Sıfırla",
    "updatesLabel": "Güncellemeler",
    "prereleaseLabel": "Ön yayın sürümlerini dahil et (alfa/beta)",
    "prereleaseHint": "Resmi sürümden önce test sürümlerini alın.",
    "caption": "Ayarlar",
    "type": {
      "title": "Küme türü",
      "text": ""
    },
    "margins": {
      "title": "Kenar boşlukları",
      "text": "",
      "items": [],
      "applyText": "",
      "note": ""
    },
    "updates": {
      "title": "7.3 Güncellemeler",
      "text": "'Ön yayın sürümlerini dahil et (alfa/beta)' seçeneğini etkinleştirerek resmi sürümden önce test derlemelerini alın. Manuel kontrol için: ⋮ → 🔄 Güncellemeleri Kontrol Et. Güncellemeler doğrudan GitHub Releases'den indirilir — Play Store gerekmez."
    }
  },
  "tools": {
    "title": "8. Ek Araçlar",
    "intro": "",
    "table": {
      "headers": [
        "Seçenek",
        "Açıklama"
      ],
      "rows": [
        ["⚙️ Ayarlar", "Ayarları açar: Küme türü, Global taşma kenar boşlukları, Güncellemeler (ön yayın)."],
        ["🌐 Dil", "Arayüz dilini değiştirmek için dil seçim ekranına döner."],
        ["🔄 Güncellemeleri Kontrol Et", "GitHub'da yeni bir DashCast sürümünün mevcut olup olmadığını kontrol eder. Ayarlarda ön yayın etkinleştirilirse alfa/beta sürümleri de önerilir."],
        ["⊞ Izgara modu / 📋 Liste modu", "Uygulama listesini liste modu (1 sütun) ve ızgara modu (5 sütun) arasında değiştirir. Liste başlığındaki ⊞ düğmesiyle de erişilebilir."],
        ["Orijinal Gösterge Panelini Geri Yükle", "Yansımayı durdurur VE yerel BYD gösterge panelini geri yükler. Yalnızca bir yansıma sırasında etkin."],
        ["🔧 Tanılama", "Gelişmiş geliştirici araçları — ADB bağlantısı, VirtualDisplay oluşturma, SurfaceFlinger analizi, tersine mühendislik için logcat sniffer."],
        ["📋 Sistem Raporu", "Tam rapor oluşturur (ekranlar, BYD API'leri, izinler, paketler) — destek veya hata raporu için kullanışlı."],
        ["📜 Günlükler", "Gerçek zamanlı günlük görüntüleyici — etiket/seviyeye göre filtrele, e-posta veya dosya ile paylaş."]
      ]
    },
    "logs": {
      "title": "Günlükler",
      "header": "Günlükler",
      "clearButton": "Temizle",
      "shareButton": "Paylaş",
      "filterPlaceholder": "Filtrele...",
      "lines": [],
      "caption": ""
    }
  },
  "faq": {
    "title": "9. SSS",
    "items": []
  },
  "footer": "DashCast v0.5.1"
};
