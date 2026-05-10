export default {
  "code": "tr",
  "flag": "🇹🇷",
  "name": "Türkçe",
  "title": "DashCast — Kullanım Kılavuzu",
  "meta": "v0.5.1 · BYD Seal EU · DiLink 3.0 · Android 10",
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
    "text": "İlk çalıştırmada dilinizi seçin.",
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
        "tone": "green",
        "marker": "④",
        "label": "Geri Yükle",
        "text": "Orijinal BYD gösterge panelini geri yükler."
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
        "text": "Yüklü uygulamalar. ❌ ile tamamen durdurun."
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
    "intro": "Ayarları yapın",
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
      "rows": []
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
