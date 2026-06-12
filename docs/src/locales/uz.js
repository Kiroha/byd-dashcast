export default {
  code: 'uz',
  flag: '🇺🇿',
  name: "O'zbekcha",
  title: "DashCast — Foydalanuvchi qo'llanmasi",
  manualName: "Foydalanuvchi qo'llanmasi",
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Mundarija',

  intro: {
    title: '0. Kirish',
    lead:
      "DashCast BYD markaziy ekranidagi istalgan Android ilovasini asboblar paneliga (raqamli klasterga) chiqaradi. Maps, Waze, Spotify yoki ABRP to'g'ridan-to'g'ri rul ortida — «Tartiblar» rejimi bilan esa bir vaqtda bir nechta ilova, har biri o'z zonasida. Bularning barchasi tizimni o'zgartirmasdan.",
    bullets: [
      '✅ BYD Seal EU bilan mos (DiLink 3.0, proshivka Di3.0 / 6125F).',
      "✅ Tizim o'zgartirilmaydi: DashCast oddiy ilova kabi o'rnatiladi.",
      '✅ TCP orqali mahalliy ADB — birinchi avtorizatsiyadan keyin kompyuter kerak emas.',
      '✅ 13 ta interfeys tili, birinchi ishga tushirishda tanlanadi.',
      '✅ Real vaqtdagi sensorli oyna: klasterni markaziy ekrandan boshqaring.',
      "✅ «Tartiblar» rejimi: klasterda yonma-yon bir nechta ilova, zonalar barmoq bilan chiziladi.",
      '✅ Avtoishga tushirish: DashCast ochilishi bilan proyeksiya + ilova (yoki sevimli tartib).',
      '✅ Chetlar (overscan) har bir ilova uchun saqlanadi.',
      "✅ O'rnatilgan OTA yangilanishlari (ixtiyoriy beta-kanal).",
    ],
    note:
      "💡 Yagona talab: BYD Sozlamalar → Dasturchi bo'limida simsiz ADB nosozliklarni tuzatishni yoqish. Birinchi ishga tushirishda «Tuzatishga ruxsat berilsinmi?» dialogi paydo bo'ladi — «Har doim ruxsat berish» belgilang va tasdiqlang. Buni hech qachon takrorlash kerak bo'lmaydi.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Salomlashish ekrani — til tanlash',
      lead:
        "Eng birinchi ishga tushirishda DashCast 13 ta mavjud til panjarasini ko'rsatadi. O'zingiznikiga teging: tanlov saqlanadi va ekran boshqa ko'rinmaydi. Tilni istalgan vaqtda Sozlamalardan o'zgartirish mumkin.",
      mockupLabel: "1-ekranni ko'rish (Salomlashish)",
      featuresTitle: 'Tafsilotlar',
      features: [
        {
          title: "13 ta qo'llab-quvvatlanadigan til",
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Tanlangan til darhol qo'llanadi, qayta ishga tushirish kerak emas.",
        },
        {
          title: "Avtomatik o'qish yo'nalishi",
          text:
            "Arab tili avtomatik ravishda o'ngdan chapga (RTL) joylashuvga o'tadi: navigatsiya paneli o'ngga o'tadi, ro'yxatlar aks ettiriladi.",
        },
        {
          title: "Istalgan vaqtda o'zgartirish mumkin",
          text: "Tilni keyinroq o'zgartirish uchun: Sozlamalar → Til. Darhol qo'llanadi.",
        },
      ],
      howTo: {
        title: 'Qanday qilish kerak',
        steps: [
          "DashCast'ni ishga tushiring (BYD ilovalar ro'yxatidagi ko'k belgi).",
          "Tillar panjarasi bilan salomlashish ekrani ko'rinadi.",
          "Tilingizga teging. Interfeys darhol o'zgaradi.",
          'Bosh ekran ochiladi — tayyorsiz.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Bosh ekran — Ilovalar va Klaster',
      lead:
        "DashCast markaziy ekrani. Chapda: qidiruv, filtrlar va sevimlilar bilan barcha ilovalaringiz. O'ngda: klasterning real vaqtdagi ko'rinishi, «To'liq ekranli oyna» / «Proyeksiyani to'xtatish» tugmalari va sevimli ko'p ilovali joylashuvni tanlash uchun tartiblar karuseli.",
      mockupLabel: "2-ekranni ko'rish (Bosh)",
      featuresTitle: 'Qila oladigan hamma narsangiz',
      features: [
        {
          title: '👆 Qisqa teginish — proyeksiyalash',
          text:
            "Ilovani klasterga yuborish uchun unga teging. Proyeksiya faol bo'lmasa, avtomatik boshlanadi (~2 s isinish), so'ng ilova rul ortida paydo bo'ladi.",
        },
        {
          title: '👆⏱️ Uzoq bosish — amallar menyusi',
          text:
            "Ilovani ushlab turing: ⭐ Sevimli, Avtoishga tushirish (har DashCast ochilishida bu ilovani proyeksiyalash), Klasterga / bosh ekranga ko'chirish, ✕ Majburiy to'xtatish.",
        },
        {
          title: '🔍 Qidiruv va filtrlar',
          text:
            "Qidiruv paneli yozish paytida filtrlaydi (nom yoki paket). Toifa chiplari (Hammasi / Navigatsiya / Media…) ilovalarni guruhlaydi; ▦ tugmasi ro'yxat/panjara o'rtasida almashtiradi.",
        },
        {
          title: "🚦 Real vaqtdagi klaster ko'rinishi",
          text:
            "O'ng panel klasterni jonli aks ettiradi. Ko'rinishga teginishlaringiz proyeksiyalangan ilovaga uzatiladi — aylantirish, kattalashtirish, klaviatura, hammasi ishlaydi.",
        },
        {
          title: "👁️ To'liq ekranli oyna",
          text:
            "Ko'rinishni butun markaziy ekranga kengaytiradi: Maps'da to'liq klaviatura bilan manzil yozish uchun ideal. Hammasi real vaqtda klasterga ko'chiriladi.",
        },
        {
          title: "⏹ Proyeksiyani to'xtatish",
          text:
            "Proyeksiyani toza yakunlaydi va asl BYD panelini (tezlik, asboblar, ADAS) Sozlamalarda belgilangan o'lcham bilan tiklaydi.",
        },
        {
          title: '🗂️ Tartiblar karuseli',
          text:
            "Tugmalar ostida har bir karta tartib zonalarining mini-ko'rinishini ko'rsatadi. Kartaga tegib, uni sevimli tartib qiling (yulduz + ko'k hoshiya). «Erkin rejim» tartiblarni o'chiradi; «＋ Boshqarish» muharrirni ochadi.",
        },
        {
          title: '📺 Suzuvchi tugma',
          text:
            "📺 tugmasi boshqa ilovalar ustida qoladi: teginish = oynani ochish, uzoq bosish = oxirgi proyeksiyalangan ilovalar o'rtasida tez almashish.",
        },
      ],
      howTo: {
        title: 'Ilovani klasterga qanday proyeksiyalash',
        steps: [
          'Kerakli ilovani toping (mas. Maps) — kerak bo‘lsa qidiruv yoki filtrlar.',
          "Belgisiga teging → proyeksiya boshlanadi, klaster ~2 s ichida ilovaga o'tadi.",
          "O'ngdagi ko'rinish klasterdagini jonli ko'rsatadi.",
          "Matn kiritish uchun: «To'liq ekranli oyna» → manzilingizni yozing → hammasi ko'chiriladi.",
          "To'xtatish uchun: «Proyeksiyani to'xtatish» — klaster tug'ma BYD'ga qaytadi.",
        ],
      },
      tipsTitle: 'Maslahatlar',
      tips: [
        "💡 Avtoishga tushirish: ilovani tanlang (uzoq bosish → Avtoishga tushirish), har DashCast ochilishida avtomatik proyeksiyalansin — proyeksiya o'zi faollashadi.",
        "💡 Sevimli tartib: karuselda tanlangan karta — avtoishga tushirish faollashtiradigan karta («Tartiblar» bo'limiga qarang).",
        '💡 Chetlar: ilova klasterdan chiqsa, Sozlamalar → Chetlar, gorizontal/vertikal slayderlar. Har bir ilova uchun saqlanadi.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Sozlamalar',
      lead:
        "Global parametrlar: klaster o'lchami, til, chetlar, ishga tushirish xatti-harakati, «Tartiblar» rejimi va yangilanishlar. Yon panel ochiq qoladi — pozitsiyangizni yo'qotmasdan ekranlar o'rtasida almashing.",
      mockupLabel: "3-ekranni ko'rish (Sozlamalar)",
      featuresTitle: "Asosiy bo'limlar",
      features: [
        {
          title: '📺 Klaster turi',
          text:
            "Asboblar panelining jismoniy o'lchami: 8.8″, 12.3″ (Seal EU'da tavsiya etiladi — ADAS cho'zilishini tuzatadi) yoki 10.25″. «Proyeksiyani to'xtatish» to'g'ri rejimni tiklash uchun foydalanadi.",
        },
        {
          title: '↔️↕️ Chetlar (overscan)',
          text:
            "Kesilgan chetlarni qoplash uchun gorizontal/vertikal slayderlar (0–200 px). Har bir ilova uchun saqlanadi: Maps'da 80 px bo'lishi mumkin, Spotify esa 0 da qoladi. «Qo'llash» jonli proyeksiyani sozlaydi.",
        },
        {
          title: '🚗 Avtomobil bilan ishga tushirish',
          text:
            "Yoqilgan bo'lsa, DashCast mashina bilan ishga tushadi va oxirgi proyeksiyalangan ilovani tiklaydi. Aks holda uni BYD ro'yxatidan ishga tushiring.",
        },
        {
          title: '🗂️ «Tartiblar» rejimi',
          text:
            "Maxsus zonalar bilan ko'p ilovali proyeksiyani yoqadi (avtomatik boshqariladigan Proxy ADB Daemon talab qilinadi). Bosh ekranda karuselni va «Tartiblar» yorlig'ini ko'rsatadi.",
        },
        {
          title: '⭐ Avtomatik sevimli tartib',
          text:
            "DashCast ishga tushganda: klaster proyeksiyasini, sevimli tartibni faollashtiradi va har bir zonaga bog'langan ilovalarni ochadi. Birorta teginishsiz to'liq ko'p ilovali konfiguratsiyangiz.",
        },
        {
          title: '⚡ Ishga tushirishda slotlarni oldindan yaratish',
          text:
            "Ochilganda sevimli tartibning virtual ekranlarini tayyorlaydi (ilovalarni ochmasdan) — tartibni faollashtirish keyin deyarli oniy bo'ladi.",
        },
        {
          title: '📦 OTA yangilanishlari',
          text:
            "DashCast har ishga tushirishda GitHub'ni tekshiradi. Beta-kanal uchun «Oldindan versiyalarni qo'shish» belgilang (yangiliklar ertaroq, barqarorlik pastroq).",
        },
        {
          title: '🌐 Til',
          text: "13 til — almashish oniy.",
        },
      ],
      howTo: {
        title: 'Ilova chetlarini qanday sozlash',
        steps: [
          'Sozlanadigan ilovani proyeksiyalang (mas. Waze).',
          'Sozlamalar → Chetlar.',
          "Chap/o'ng chetlar to'g'ri bo'lguncha gorizontal slayderni harakatlantiring.",
          "Vertikal uchun ham xuddi shunday, keyin «Qo'llash» — ilovani qayta ishga tushirmasdan jonli sozlash.",
          'Sozlama faqat shu ilova uchun saqlanadi.',
        ],
      },
      note:
        "⚠️ Klaster turini o'zgartirsangiz, tiklash to'g'ri rejimdan foydalanishi uchun proyeksiyani to'xtatib, qayta ishga tushiring.",
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Tartiblar — klasterda bir nechta ilova',
      lead:
        "«Tartiblar» rejimi klasterni maxsus zonalarga bo'ladi, har biri o'z ilovasini ko'rsatadi: masalan, chapda Waze, o'ngda Spotify. Zonalarni barmoq bilan chizasiz, har bir zonaga ilova bog'laysiz va tartib bitta teginish bilan — yoki ishga tushirishda o'zi — faollashadi.",
      mockupLabel: "7-ekranni ko'rish (Tartiblar)",
      featuresTitle: 'Imkoniyatlar',
      features: [
        {
          title: '✏️ Zona chizish',
          text:
            "Kanvasda (1920×720 klaster nusxasi) to'rtburchak chizish uchun barmog'ingizni torting. Dialog ochiladi: nom, pikselgacha aniq pozitsiya/o'lchamlar va bog'lanadigan ilova.",
        },
        {
          title: "🔗 Ilova bog'lash",
          text:
            "Har bir zonani ilovaga bog'lash mumkin: tartib faollashganda ilova o'z zonasida avtomatik ochiladi. Ilovasiz zona bo'sh qoladi.",
        },
        {
          title: "✋ Ko'chirish va o'lchamini o'zgartirish",
          text:
            "Zonani ko'chirish uchun torting; burchaklardagi oq tutqichlar o'lchamni o'zgartiradi. Chetlar klaster chegaralariga va qo'shni zonalarga avtomatik yopishadi.",
        },
        {
          title: "✏️ Mavjud zonani o'zgartirish",
          text:
            "Zonaga teging (kanvasda yoki pastdagi chipida): nomini o'zgartiring, geometriyasini sozlang, bog'langan ilovani o'zgartiring yoki o'chiring. Uzoq bosish = tez o'chirish.",
        },
        {
          title: '💾 Saqlangan tartiblar',
          text:
            "Xohlagancha tartib saqlang («Nav+Media», «Uchlik ekran»…). Yon panel ularni Faollashtirish / O'chirish / O'zgartirish / O'chirib tashlash bilan ro'yxatlaydi.",
        },
        {
          title: '⭐ Sevimli va avtoishga tushirish',
          text:
            "«Sevimli» tugmasi (yoki bosh ekran karuseli kartasiga teginish) «Avtomatik sevimli tartib» DashCast ishga tushganda faollashtiradigan tartibni belgilaydi — proyeksiya bilan birga.",
        },
      ],
      howTo: {
        title: 'Birinchi tartibingizni yarating',
        steps: [
          'Sozlamalarda «Tartiblar rejimi»ni yoqing.',
          "«Tartiblar» yorlig'ini oching (yon panel).",
          "Birinchi zonani chizish uchun barmog'ingizni kanvasda torting (mas. chap yarmi).",
          "Dialogda: nomlang, «Ilova bog'lash»ga teging → Waze tanlang → Qo'shish.",
          "Ikkinchi zonani chizing (o'ng yarmi), Spotify bog'lang.",
          '«Saqlash» → tartibni nomlang (mas. Nav+Media).',
          "Tanlash uchun «Sevimli», keyin faollashtiring: ikkala ilova ham o'z zonasida ochiladi.",
        ],
      },
      tipsTitle: 'Maslahatlar',
      tips: [
        "💡 «Avtomatik sevimli tartib» (Sozlamalar) bilan birga to'liq ko'p ilovali konfiguratsiyangiz har DashCast ishga tushishida o'zi quriladi.",
        "💡 Har bir karusel kartasining mini-ko'rinishi tartibning haqiqiy zonalarini ko'rsatadi — bir qarashda taniladi.",
        "💡 Ilova o'z zonasida ko'rinishdan bosh tortadimi? Ba'zi ilovalar nisbatni talab qiladi; 16:9 ga yaqinroq zonani sinab ko'ring.",
      ],
      note:
        "ℹ️ «Tartiblar» rejimi Proxy ADB Daemon'ga tayanadi (avtomatik ishga tushadi). Birinchi sovuq ishga tushirish: ilovalar ko'ringuncha 6–8 s kuting — bu klasterning faollashtirish ketma-ketligi.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnostika',
      lead:
        "Proyeksiya kutilganidek ishlamaydigan holatlar uchun ichki panel. Ko'pchilik foydalanuvchilarga u hech qachon kerak bo'lmaydi — qo'llab-quvvatlash va nosozliklarni tuzatish uchun mavjud.",
      mockupLabel: "4-ekranni ko'rish (Diagnostika)",
      featuresTitle: 'Mavjud vositalar',
      features: [
        {
          title: 'Ulanish testlari',
          text:
            'Mahalliy ADB tunnelini (localhost:5555), ClusterService holatini va klasterning virtual ekrani mavjudligini tekshiradi.',
        },
        {
          title: 'Platforma zondlari',
          text:
            'DiLink aniqlash (2/3/4/5), displeylar inventarizatsiyasi, BYD avtomobil API instansiyalash (tezlik, energiya) va BYDAUTO ruxsatlari holati.',
        },
        {
          title: 'Ulashiladigan hisobot',
          text:
            "Qo'llab-quvvatlash uchun matn sifatida eksport qilinadigan to'liq hisobot yaratadi (tizim, displeylar, xizmatlar, ruxsatlar, demon ko'rsatkichlari).",
        },
      ],
      howTo: {
        title: "Bu yorliqni qachon ishlatish kerak",
        steps: [
          'Ilovaga tekkandan keyin klaster qora qoladi → ClusterService va virtual ekranni tekshiring.',
          '«ADB mavjud emas» xabari → «ADB testi» tugmasi.',
          "Qo'llab-quvvatlash hisobot so'raydi → yarating va ulashing.",
        ],
      },
      note: "ℹ️ Tugmalar — aniq ko'rsatilmasa, faqat o'qish uchun testlar.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Tizim hisoboti',
      lead:
        "Faqat o'qish uchun panel: versiyalar, aniqlangan displeylar va DashCast xizmatlarining jonli holati. Biror narsa noto'g'ri ko'ringanda tekshiriladigan birinchi ekran.",
      mockupLabel: "5-ekranni ko'rish (Tizim)",
      featuresTitle: "Ko'rsatiladigan ma'lumotlar",
      features: [
        {
          title: '🖥️ Displeylar',
          text:
            'Bosh ekran (ruxsat, zichlik) va klasterning virtual displeyi (1920×720) real vaqtdagi holati bilan.',
        },
        {
          title: '⚙️ Xizmatlar',
          text:
            "ClusterService (proyeksiya), MirrorDaemon (oyna), Proxy ADB Daemon (imtiyozli operatsiyalar), AdbLocalClient (ADB tunneli) — har biri yashil/qizil nuqta va qayta ishga tushirish tugmasi bilan.",
        },
        {
          title: '📱 Versiyalar',
          text:
            "O'rnatilgan DashCast versiyasi, BYD proshivkasi, Android/API versiyasi, DiLink qurish identifikatorlari.",
        },
        {
          title: '🔁 Proyeksiyani takrorlash',
          text:
            "Klasterning to'liq faollashtirish ketma-ketligini qayta o'ynatish tugmasi (panel oraliq holatda qotib qolsa foydali).",
        },
      ],
      tipsTitle: 'Maslahatlar',
      tips: [
        "💡 «Tartiblar» rejimi uchun «Proxy ADB Daemon» yashil (RUN) bo'lishi kerak — aks holda qayta ishga tushirish uchun qatoriga teging.",
        "💡 To'liq hisobotni shu ekrandan eksport qilib, xato haqidagi xabarga qo'shish mumkin.",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Jurnal',
      lead:
        "DashCast ichki jurnali: barcha muhim amallar (proyeksiyalar, tiklashlar, ADB xatolari, yangilanishlar) uzluksiz qayd etiladi. Kutilmagan xatti-harakatni tushunish yoki qo'llab-quvvatlashga hisobot berish uchun foydali.",
      mockupLabel: "6-ekranni ko'rish (Jurnal)",
      featuresTitle: 'Imkoniyatlar',
      features: [
        {
          title: '🔍 Filtrlar',
          text:
            "Daraja bo'yicha (DEBUG / INFO / WARN / ERROR) yoki kalit so'z bo'yicha filtrlang (mas. «ADB», «Maps», «error»).",
        },
        {
          title: '🎨 Rang kodi',
          text:
            "🟢 INFO — normal operatsiya. 🟠 WARN — diqqat. 🔴 ERROR — muvaffaqiyatsizlik. ⚪ DEBUG — texnik tafsilot.",
        },
        {
          title: '📤 Ulashish',
          text:
            "Jurnalni .txt sifatida eksport qiladi va Android ulashish menyusini ochadi. DashCast versiyasi va BYD modelini o'z ichiga oladi.",
        },
        {
          title: '⏰ Vaqt belgilari',
          text:
            "Har bir qator mahalliy vaqt bilan boshlanadi (HH:mm:ss.mmm); uzoq operatsiyalar o'lchanadi.",
        },
      ],
      howTo: {
        title: 'Xato haqida xabar yuborish',
        steps: [
          'Muammoni takrorlang.',
          'Jurnal → «Ulashish»ni oching.',
          'Kanalingizni tanlang (Telegram, e-mail, GitHub Issues).',
          "Biriktirilgan fayl izni va kontekstni o'z ichiga oladi (versiya, model, proshivka).",
        ],
      },
      note:
        "🔒 Hech qanday shaxsiy ma'lumot (kontaktlar, GPS pozitsiyasi, ilova mazmuni) qayd etilmaydi — faqat DashCast amallari va texnik qaytarish kodlari.",
    },
  ],

  faq: {
    title: "8. FAQ — Ko'p so'raladigan savollar",
    items: [
      {
        question: '❓ Ilovaga tekkanimda klaster qora qoladi',
        answer:
          "Uchta mumkin sabab: (1) simsiz ADB o'chirilgan — BYD Sozlamalar → Dasturchi tekshiring. (2) To'xtagan xizmat — «Tizim» yorlig'i, qizil qatorni qayta ishga tushiring. (3) Ilova hozirgina qulagan — belgisiga qayta teging.",
      },
      {
        question: '❓ Tasvir klasterdan chiqadi / kesilgan',
        answer:
          "Sozlamalar → Chetlar: chetlar to'g'ri bo'lguncha gorizontal/vertikal slayderlarni sozlang. Ilova uchun saqlanadi — faqat bir marta qilasiz.",
      },
      {
        question: '❓ Asl BYD panelini qanday qaytaraman?',
        answer:
          "Bosh ekranda «Proyeksiyani to'xtatish»ga teging: DashCast tug'ma klasterni Sozlamalardagi o'lcham bilan tiklaydi. Panel qotib qolgan ko'rinsa: «Tizim» ekrani → «Proyeksiyani takrorlash», keyin yana to'xtating.",
      },
      {
        question: '❓ Sevimli tartibim ishga tushganda ochilmaydi',
        answer:
          "Sozlamalardagi uch shartni tekshiring: «Tartiblar rejimi» yoqilgan, «Avtomatik sevimli tartib» yoqilgan va tartib ⭐ sevimli deb belgilangan (bosh ekran karuseli yoki «Tartiblar» yorlig'idagi «Sevimli» tugmasi). Sovuq ishga tushirishda 6–8 s kuting.",
      },
      {
        question: "❓ DashCast 12 V akkumulyatorni o'tirg'izadimi?",
        answer:
          "Yo'q — DashCast avtomobil bilan birga to'xtaydi. Dvigatel o'chiq paytda hech qanday fon xizmati faol qolmaydi.",
      },
      {
        question: '❓ Klasterda qaysi ilovalar ishlaydi?',
        items: [
          '✅ Navigatsiya: Google Maps, Waze, OsmAnd, ABRP, Magic Earth.',
          "✅ Media: Spotify, YouTube, YouTube Music (gorizontal afzal).",
          '✅ Tizim: kamera, ob-havo, kalendar.',
          "⚠️ DRM ilovalari (Netflix, Disney+, Prime Video): virtual ekranda ko'rsatishdan bosh tortishi mumkin — Android cheklovi, DashCast emas.",
        ],
      },
      {
        question: '❓ Yangilanishlar: barqaror yoki beta?',
        answer:
          "Barqaror kanal (standart) nashrdan oldin avtomobilda sinaladi. Beta-kanal (Sozlamalar → Yangilanishlar → «Oldindan versiyalarni qo'shish») yangiliklarni kompilyatsiya qilinishi bilanoq oladi — erta sinash uchun foydali, vaqtinchalik regressiyalar xavfi bilan.",
      },
      {
        question: '❓ Hissa qo\'shmoqchiman yoki xato haqida xabar bermoqchiman',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — xatolar uchun Issues, savollar uchun Discussions. Diagnostikani tezlashtirish uchun Jurnal eksportini qo'shing.",
      },
    ],
  },

  footer:
    'DashCast — MIT litsenziyasi ostida tarqatiladigan ochiq kodli loyiha. BYD Auto Co., Ltd. bilan aloqasi yo\'q.',
};
