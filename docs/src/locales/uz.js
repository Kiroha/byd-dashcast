export default {
  "code": "uz",
  "flag": "🇺🇿",
  "name": "Oʻzbekcha",
  "title": "DashCast — Foydalanuvchi qo'llanmasi",
  "meta": "v0.7.2 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "Foydalanuvchi qo'llanmasi",
  "tocTitle": "📋 Mundarija",
  "sections": [
    "1. Umumiy ko'rinish",
    "2. Birinchi ishga tushirish — tilni tanlash",
    "3. Asosiy ekran",
    "4. Ilovani boshqaruv panelida ko'rsatish",
    "5. Ko'rsatish vaqtida — Boshqaruv paneli",
    "6. Ko'rsatishni to'xtatish",
    "7. Sozlamalar",
    "8. ⋮ menyusi — Qo'shimcha vositalar",
    "9. FAQ va muammolarni bartaraf etish"
  ],
  "overview": {
    "title": "1. Umumiy ko'rinish",
    "text": "DashCast — BYD-ning raqamli asboblar panelida infoteyment ekranidan istalgan ilovani ko'rsatishga imkon beruvchi Android ilovasi. Navigatsiya, musiqa, video — markaziy ekranda ishlaydigan hamma narsani haydovchi oldidagi klasterga yo'naltirish mumkin.",
    "bullets": [
      "✅ Compatible BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F)",
      "✅ Aucune modification système nécessaire",
      "✅ ADB local (TCP, localhost) — pas besoin d'ordinateur une fois configuré",
      "✅ Détection automatique de la déconnexion d'application",
      "✅ Mises à jour OTA (Over-The-Air) intégrées",
      "✅ Overscan sauvegardé indépendamment par application",
      "✅ Affichage en mode Grille ou Liste",
      "✅ Force-stop d'urgence (Croix Rouge) par application"
    ],
    "note": "💡 Oldindan shart: Sozlamalar → Ishlab chiquvchi parametrlari → Simsiz nosozliklarni tuzatish (yoki «Tarmoq orqali ADB») ichidan ADB TCP nosozliklarini tuzatishni yoqing. Bu bir marta amalga oshiriladi. DashCast birinchi marta ishga tushirilganda «USB nosozliklarini tuzatishga ruxsat berasizmi?» oynasi chiqadi — Ushbu kompyuterdan har doim ruxsat et deb bosing."
  },
  "firstLaunch": {
    "title": "2. Birinchi ishga tushirish — tilni tanlash",
    "text": "Birinchi ishga tushirishda 12 ta mavjud tilni 4×3 qatorda (3 ustun × 4 qator) taqdim etuvchi xush kelibsiz ekrani paydo bo'ladi. Tilni tanlash uchun til tugmasini bosing. Bu tanlov saqlanadi — ⋮ → 🌐 Til menyusi orqali tilni o'zgartirgungacha ekran qayta ko'rinmaydi.",
    "welcomeSubtitle": "Dashboard Controller",
    "welcomeHint": "Choisissez votre langue\nPlease select your language",
    "caption": "Til tanlash ekrani — faqat birinchi ishga tushirishda ko'rinadi"
  },
  "main": {
    "title": "3. Asosiy ekran",
    "text": "Asosiy ekran ikki zonadan iborat: yuqorida holat satri (to'q ko'k fon) va pastda o'rnatilgan ilovalar ro'yxati. ⋮ menyusi orqali ro'yxat va panjara (ikonkalar) o'rtasida almashtirish mumkin.",
    "status": "① Asboblar paneli: ulangan emas",
    "buttons": [
      "② Ko'rsatishni faollashtirish",
      "③ Ko'rsatishni to'xtatish",
      "④ Asl panelni tiklash",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ O'rnatilgan ilovalar",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Asosiy ekran — hech qanday ilova ko'rsatilmayapti (boshlang'ich holat)",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Holat",
        "text": "Asboblar paneliga ulanish holatini ko'rsatadi. Ilova faol bo'lganda «Asboblar paneli: [IlovaAti]» ga o'zgaradi."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Ko'rsatishni faollashtirish",
        "text": "Klaster bilan ulanish o'rnatadi va ilovani yuborishga tayyorlaydi. Birinchi shu tugmani bosing."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Ko'rsatishni to'xtatish",
        "text": "BYD asl asboblar panelini tiklmasdan joriy ko'rsatishni yopadi."
      },
      {
        "tone": "gray",
        "marker": "④",
        "label": "Asl panelni tiklash (⋮ menyusi)",
        "text": "⋮ menyusi orqali foydalaniladi. Ko'rsatishni TO'XTATADI va BYD asl asboblar panelini tiklaydi (tezlik, sensorlar, qolib qolgan masofa…). Foydalanish tugaganda «Ko'rsatishni to'xtatish» o'rniga afzalroq."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "⋮ menyusi",
        "text": "Sozlamalar, Diagnostika, Tizim hisoboti, Jurnal, tilni o'zgartirish va Panjara/Ro'yxat almashtiruviga kirish."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "Ilovalar ro'yxati",
        "text": "Klasterga yuborish uchun ilovani bosing. Uzoq bosish → mahkamlash (⭐, ro'yxat boshiga). «Avto» bayrog'i ilovani avtomatik ishga tushirish uchun belgilaydi: ko'rsatish boshlanishi bilan avtomatik yuboriladi. ✕ va ← / → faqat faol ilovada ko'rinadi."
      }
    ]
  },
  "projection": {
    "title": "4. Ilovani boshqaruv panelida ko'rsatish",
    "steps": [
      "«Ko'rsatishni faollashtirish» (yuqoridagi ko'k tugma) tugmasini bosing. Holat «Klasterni ishga tushirish…» ga o'zgaradi. Mahalliy ADB ulanishi o'rnatiladi va klaster ko'rsatish rejimiga o'tadi.",
      "Ro'yxatdagi kerakli ilovani bosing. DashCast ilovani asboblar paneli displeyiga ko'chiradi. Holat «Asboblar paneli: [Ilova nomi]» ga o'zgaradi.",
      "Boshqaruv paneli ekranning pastki qismida paydo bo'ladi. Ushbu ilova uchun saqlangan overscan qiymatlari avtomatik tarzda qo'llaniladi."
    ],
    "activeStatus": "Asboblar paneli: Maps ✓",
    "buttons": [
      "Ko'rsatishni faollashtirish",
      "📺 Ko'zgu",
      "Ko'rsatishni to'xtatish",
      "Asl panelni tiklash",
      "⋮",
      "← Asosiy ekran",
      "✕",
      "→ Klaster",
      "✕",
      "→ Klaster",
      "✕",
      "📐 Sozlash",
      "⬛⬛ Bo'lish",
      "Yashirish ▼"
    ],
    "listTitle": "O'rnatilgan ilovalar",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Klasterni boshqarish",
    "controlApp": "Maps",
    "mirrorText": "Klasterda faol ✓",
    "caption": "Asosiy ekran — Maps asboblar panelida ko'rsatilmoqda",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Yashil chiziq",
        "text": "Vizual ko'rsatkich: ilova hozir klasterda."
      },
      {
        "tone": "",
        "marker": "→",
        "label": "→ Klaster",
        "text": "Boshqa ilovani klasterga yuborish (joriyni almashtiradi)."
      },
      {
        "tone": "gray",
        "marker": "←",
        "label": "← Asosiy ekran",
        "text": "Ilovani markaziy ekranga qaytarish (klasterdan olib tashlash)."
      },
      {
        "tone": "teal",
        "marker": "📺",
        "label": "Ko'zgu",
        "text": "DashCast-da asboblar panelining real vaqtdagi nusxasini ko'rsatadi."
      },
      {
        "tone": "red",
        "marker": "❌",
        "label": "Qizil xoch (Force Stop)",
        "text": "Muzlab qolgan ilovani majburiy to'xtatadi va So'nggilardan tozalaydi."
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Panjara / Ro'yxat",
        "text": "⋮ menyusi orqali ro'yxat va panjara o'rtasida almashtirish."
      }
    ]
  },
  "control": {
    "title": "5. Ko'rsatish vaqtida — Boshqaruv paneli",
    "intro": "Klasterda ilova faol bo'lganda, asosiy ekranning pastki qismida to'rt funktsiyali qora panel paydo bo'ladi:",
    "mirror": {
      "title": "5.1 Ko'zgu (📺 Ko'zgu)",
      "text": "DashCast-da asboblar panelining nusxasini ko'rish uchun holat satridagi 📺 Ko'zgu tugmasini bosing. Siz u bilan teginish orqali o'zaro ta'sir qilishingiz mumkin — hodisalar klasterga uzatiladi.",
      "note": "Ko'zgu displeyni olish uchun SurfaceControl dan foydalanadi. Ko'zgu ko'rsatilmasa, zaxira sifatida har 2 soniyada avtomatik skrinshot olinadi."
    },
    "resize": {
      "title": "5.2 Sozlash (📐 Ilova bo'yicha Overscan)",
      "text": "📐 Sozlash tugmasi ikkita slayderni ochadi: Kenglik chekkasi va Balandlik chekkasi. Bu qiymatlar klasterda ko'rsatilayotgan tasvirning chekkalarini kesib oladi. Har bir ilova uchun alohida saqlanadi va har ishga tushirishda avtomatik qo'llaniladi.",
      "note": "💡 Seal EU uchun tavsiya etilgan qiymatlar: Kengligi 80 px, Balandligi 50 px."
    },
    "relaunch": {
      "title": "5.5 Qayta ishga tushirish (↺)",
      "text": "↺ (to'q sariq) tugmasi klasterda ko'rsatilayotgan ilovani majburiy to'xtatadi, so'ng darhol qayta ishga tushiradi. Ilova muzlab qolsa yoki klasterdagi ko'rsatish bloklanib qolsa foydali."
    },
    "split": {
      "title": "5.3 Bo'lish rejimi (⬛⬛ Bo'lish)",
      "text": "Asboblar panelini ikkita ilova o'rtasida taqsimlash uchun ⬛⬛ Bo'lish tugmasini bosing:",
      "items": [
        "To'liq ekran — bitta ilova butun klasterni egallaydi",
        "⬜⬛ Chap (50%) — asosiy ilova chap tomonda, ikkinchisi o'ng tomonda",
        "⬛⬜ O'ng (50%) — asosiy ilova o'ng tomonda"
      ],
      "extra": "Bo'lish rejimida ro'yxatdan ikkinchi ilovani tanlash mumkin. U klasterning ikkinchi yarmini egallaydi."
    },
    "hide": {
      "title": "5.4 Panelni yashirish",
      "text": "Boshqaruv panelini yig'ib, ilovalarning to'liq ro'yxatiga qaytish uchun Yashirish ▼ tugmasini bosing."
    }
  },
  "stopping": {
    "title": "6. Ko'rsatishni to'xtatish",
    "intro": "Ko'rsatishni to'xtatishning ikki yo'li:",
    "table": {
      "headers": [
        "Tugma",
        "Xulq-atvor",
        "Qachon ishlatish kerak"
      ],
      "rows": [
        [
          "Ko'rsatishni to'xtatish",
          "Ko'rsatishni to'xtatadi. Asboblar paneli bo'sh (qora) qoladi.",
          "Ko'rsatishni vaqtincha to'xtatish kerak bo'lganda."
        ],
        [
          "Asl panelni tiklash",
          "Ko'rsatishni to'xtatadi VA BYD asl asboblar panelini tiklaydi (tezlik, qolib qolgan masofa, sensorlar…).",
          "Foydalanish tugaganda — BYD odatiy asboblar panelini tiklash uchun."
        ]
      ]
    },
    "warning": "⚠️ Ushbu tugmalarni bosmasdan DashCast-dan chiqsangiz, xizmat qayta ishga tushirilguncha ko'rsatish klasterda faol bo'lib qoladi."
  },
  "settings": {
    "title": "7. Sozlamalar",
    "intro": "Sozlamalarga ⋮ → ⚙️ Sozlamalar orqali kiring. Ekranda uch bo'lim mavjud:",
    "titleLabel": "Sozlamalar",
    "clusterTypeLabel": "Klaster turi",
    "clusterOptions": [
      "8,8 dyuym (cmd=29)",
      "12,3 dyuym (cmd=30) — Seal EU",
      "10,25 dyuym (cmd=31)"
    ],
    "marginsLabel": "Displey chekkалari (global overscan)",
    "horizontalMarginLabel": "Chap / O'ng:",
    "verticalMarginLabel": "Yuqori / Quyi:",
    "applyButton": "Qo'llash",
    "resetButton": "Qayta tiklash (80 / 50)",
    "updatesLabel": "Yangilanishlar",
    "prereleaseLabel": "Oldindan chiqarilgan versiyalarni qo'shing (alfa/beta)",
    "prereleaseHint": "Rasmiy chiqarishdan oldin sinov versiyalarini oling.",
    "caption": "Sozlamalar sahifasi",
    "type": {
      "title": "7.1 Klaster turi",
      "text": "Asboblar panelingiz ekrani o'lchamini tanlang. BYD Seal EU uchun 12,3 dyuym (cmd=30) tanlang. Bu parametr faollashtirish vaqtida klasterga yuboriladigan buyruqni belgilaydi."
    },
    "margins": {
      "title": "7.2 Global displey chekkалari (overscan)",
      "text": "Ekranning ko'rinadigan maydonidagi tarkibni ideal kadrlashtirish uchun chekkalarni sozlang. Global tarzda qo'llaniladi. Ilova bo'yicha sozlash uchun boshqaruv panelindagi 📐 Sozlash tugmasidan foydalaning.",
      "items": [
        "Chap / O'ng — gorizontal chekka (har bir tomonda 0–200 px)",
        "Yuqori / Quyi — vertikal chekka (yuqorida va pastda 0–200 px)"
      ],
      "applyText": "Ilova ko'rsatilayotgan bo'lsa darhol natija uchun Qo'llash tugmasini bosing. Qiymatlar seanlar o'rtasida saqlanadi.",
      "note": "💡 Seal EU uchun tavsiya etilgan standart qiymatlar: Chap/O'ng = 80 px, Yuqori/Quyi = 50 px."
    },
    "updates": {
      "title": "7.3 Yangilanishlar",
      "text": "Rasmiy chiqarishdan oldin sinov versiyalarini olish uchun «Oldindan chiqarilgan versiyalarni qo'shing (alfa/beta)» ni yoqing. Qo'lda tekshirish: ⋮ → 🔄 Yangilanishlarni tekshirish. Yangilanishlar GitHub Releases-dan yuklanadi — Play Store shart emas."
    }
  },
  "tools": {
    "title": "8. ⋮ menyusi — Qo'shimcha vositalar",
    "intro": "Yuqori o'ng burchakdagi ⋮ tugmasi quyidagi elementlar bilan menyuni ochadi:",
    "table": {
      "headers": [
        "Variant",
        "Tavsif"
      ],
      "rows": [
        ["⚙️ Sozlamalar", "Sozlamalarni ochadi: Klaster turi, Global overscan chekkалari, Yangilanishlar (oldindan versiyalar)."],
        ["🌐 Til", "Interfeys tilini o'zgartirish uchun til tanlash ekraniga qaytadi."],
        ["🔄 Yangilanishlarni tekshirish", "GitHub-da DashCast-ning yangi versiyasi borligini tekshiradi. Sozlamalarda oldindan versiyalar yoqilgan bo'lsa, alfa/beta versiyalarini ham taklif qiladi."],
        ["⊞ Panjara rejimi / 📋 Ro'yxat rejimi", "Ilovalar ro'yxatini ro'yxat rejimi (1 ustun) va panjara (5 ustun) o'rtasida almashtiradi. Ro'yxat sarlavhasidagi ⊞ tugmasi orqali ham foydalanish mumkin."],
        ["Asl panelni tiklash", "Ko'rsatishni to'xtatadi VA BYD asl asboblar panelini tiklaydi. Faqat ko'rsatish vaqtida faol."],
        ["🔧 Diagnostika", "Ishlab chiquvchilar uchun kengaytirilgan vositalar — ADB ulanishi, VirtualDisplay yaratish, SurfaceFlinger tahlili, teskari muhandislik uchun logcat sniffer."],
        ["📋 Tizim hisoboti", "To'liq hisobot yaratadi (displeylar, BYD API, ruxsatlar, paketlar) — qo'llab-quvvatlash yoki xato hisoboti uchun foydali."],
        ["📜 Jurnal", "Real vaqtdagi jurnal — teg/daraja bo'yicha filtr, elektron pochta yoki fayl orqali eksport."]
      ]
    },
    "logs": {
      "title": "Jurnal (📜 Jurnal)",
      "header": "📋 Jurnal",
      "clearButton": "Tozalash",
      "shareButton": "Ulashish",
      "filterPlaceholder": "Filtr (teg / xabar / daraja)…",
      "lines": [
        "[INFO ] ClusterService → Cluster display connected: id=1",
        "[INFO ] launchOnDashboard OK → com.google.android.apps.maps",
        "[DEBUG] watchdog started for com.google.android.apps.maps pid=4821",
        "[WARN ] setTaskWindowingMode: SecurityException (expected)",
        "[INFO ] wm overscan applied on display 1 inset=80,50"
      ],
      "caption": "Jurnal — real vaqtdagi filtr, eksport mavjud"
    }
  },
  "faq": {
    "title": "9. FAQ va muammolarni bartaraf etish",
    "items": [
      {
        "question": "❓ «ADB nosozliklarini tuzatishga ruxsat berasizmi?» qalqib chiquvchi oyna ko'rinmaydi",
        "answer": "Infoteymentning ishlab chiquvchi sozlamalarida TCP ADB nosozliklarini tuzatish yoqilganligini tekshiring. Parametr mavjud bo'lmasa, avval ishlab chiquvchi rejimini yoqing («Tizim haqida» ichidagi qurish raqamini 7 marta bosing).",
        "items": []
      },
      {
        "question": "❓ Tanlanganidan keyin ilova klasterda ko'rinmaydi",
        "answer": "",
        "items": [
          "Ilovani tanlamadan oldin «Ko'rsatishni faollashtirish» tugmasini bosganingizni tekshiring.",
          "Ba'zi ilovalar qo'shimcha displeyda ishga tushishdan bosh tortadi (ichki cheklovlar). Xato xabarini ko'rish uchun Jurnalni ko'ring.",
          "DashCast-ni yopib, qayta oching, so'ng ketma-ketlikni takrorlang."
        ]
      },
      {
        "question": "❓ Tarkib klasterda kesilgan yoki siljitilgan",
        "answer": "Ilova bo'yicha chekkalarni aniq sozlash uchun boshqaruv panelindagi 📐 Sozlash tugmasidan foydalaning. Sozlamalardagi global qiymatlar zaxira sifatida qo'llaniladi.",
        "items": []
      },
      {
        "question": "❓ Klasterda ilova muzlab qoldi",
        "answer": "Ro'yxatdagi ilova yonidagi ❌ Qizil xoch tugmasini bosing. Bu jarayonni majburiy to'xtatadi va So'nggilarni tozalaydi. Ilova qayta ishga tushirishga tayyor.",
        "items": []
      },
      {
        "question": "❓ Ilovani yopgandan keyin ← Asosiy ekran va ✕ tugmalari ko'rinib turibdi",
        "answer": "DashCast ilovalarning yopilishini avtomatik aniqlaydi (/proc orqali kuzatish). Interfeys muzlab qolsa, majburiy qayta tiklash uchun «Ko'rsatishni to'xtatish» tugmasini bosing.",
        "items": []
      }
    ]
  },
  "footer": "DashCast v0.7.2 — BYD Seal EU · DiLink 3.0 · Android 10 · github.com/Kiroha/byd-dashcast"
};
