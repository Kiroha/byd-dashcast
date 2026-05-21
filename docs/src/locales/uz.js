export default {
  code: 'uz',
  flag: '🇺🇿',
  name: "O'zbekcha",
  title: "DashCast — Foydalanuvchi qo'llanmasi",
  manualName: "Foydalanuvchi qo'llanmasi",
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Mundarija',

  intro: {
    title: '0. Kirish',
    lead:
      "DashCast BYD markaziy ekranidagi har qanday Android ilovani raqamli asbob panelida (cluster) ko'rsatishga imkon beradi. Maps, Waze, Spotify yoki YouTube\'ni to'g'ridan-to'g'ri rul orqasida ko'rishingiz mumkin, shu bilan birga BYD\'ning original ko'rsatkichlari (tezlik, indikatorlar, masofa) doimo qulay bo'lib qoladi.",
    bullets: [
      "✅ BYD Seal EU bilan mos (DiLink 3.0, Di3.0 / 6125F dasturiy ta'minot).",
      "✅ Tizimni o'zgartirmasdan: DashCast oddiy ilova kabi o'rnatiladi.",
      "✅ Mahalliy TCP ADB — birinchi avtorizatsiyadan keyin kompyuter kerak emas.",
      "✅ 12 til, birinchi ishga tushirishda tanlanadi.",
      "✅ O'rnatilgan OTA yangilanishlar (ixtiyoriy alpha kanali).",
      "✅ Overscan chegaralari har bir ilova uchun alohida saqlanadi.",
      "✅ Markaziy ekrandan klasterni boshqarish uchun sensorli to'liq ekran oynasi.",
      "✅ Bo'lingan rejim (klasterda yonma-yon ikkita ilova).",
    ],
    note:
      "💡 Bir martalik shart: BYD Sozlamalar → Dasturchi bo'limida simsiz ADB nosozliklarini tuzatishni yoqing. Birinchi ishga tushirishda « Nosozliklarni tuzatishga ruxsat berilsinmi? » dialogi paydo bo'ladi — « Doimo ruxsat berish » belgilang va tasdiqlang. Bu qadamni hech qachon takrorlashingiz shart emas.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Salomlashish ekrani — til tanlash',
      lead:
        "Birinchi marta ishga tushirishda DashCast 12 mavjud til bilan to'rni ko'rsatadi. Kerakli tilni bosing; tanlovingiz saqlanadi va salomlashish ekrani qaytib ko'rinmaydi. Tilni istalgan vaqtda Sozlamalar → Til orqali o'zgartirishingiz mumkin.",
      mockupLabel: '1-ekranni ochish (Salomlashish)',
      featuresTitle: 'Tafsilotlar',
      features: [
        {
          title: '12 ta qo\'llab-quvvatlanadigan til',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. Tanlangan til qayta ishga tushirishsiz darhol qo'llaniladi.",
        },
        {
          title: 'Avtomatik o\'qish yo\'nalishi',
          text:
            "Arab tili avtomatik tarzda o'ngdan chapga (RTL) tartibiga o'tadi: navigatsiya paneli o'ngga ko'chadi, ro'yxatlar teskari aylanadi, piktogrammalar o'qib bo'ladigan bo'lib qoladi.",
        },
        {
          title: 'Istalgan vaqtda o\'zgartirish mumkin',
          text:
            "Keyinchalik tilni o'zgartirish uchun: yon panelning yuqorisidagi DashCast logotipini uzoq bosing → 🌐 Til. Yangi til darhol qo'llaniladi.",
        },
      ],
      howTo: {
        title: 'Buni qanday qilish kerak',
        steps: [
          "DashCast\'ni ishga tushiring (BYD ilovalar tortmasidagi ko'k belgi).",
          "Salomlashish ekrani 4×3 til to'rini ko'rsatadi.",
          "Tilingizni bosing. Interfeys darhol o'zgaradi.",
          "Asosiy ekran ochiladi — DashCast ishlatishga tayyor.",
        ],
      },
      note:
        "ℹ️ Agar siz proyeksiya davom etayotganda tilni o'zgartirsangiz, proyeksiya uzilmasdan davom etadi; faqat DashCast interfeysi tarjima qilinadi.",
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Asosiy ekran — Ilovalar va Klaster',
      lead:
        "DashCast\'ning markaziy ekrani. Chap tarafda — qidiruv, kategoriya filtrlari va sevimlilar bilan barcha o'rnatilgan ilovalarning ro'yxati. O'ng tarafda — asosiy harakatlar bilan klasterning jonli ko'rinishi: to'liq ekran ko'rinishi, skrinshot, qayta ulanish, proyeksiyani to'xtatish.",
      mockupLabel: '2-ekranni ochish (Asosiy)',
      featuresTitle: 'Nima qila olasiz',
      features: [
        {
          title: '🔍 Qidiruv paneli',
          text:
            "Ro\'yxatni darhol filtrlash uchun bir necha harf yozing (ilova nomida ham, paketda ham qidiradi). O\'ng tomondagi ▦ tugmasi ro\'yxat va tarmoq ko\'rinishini almashtiradi.",
        },
        {
          title: '🏷️ Kategoriya filtrlari',
          text:
            "Rangli chiplar (Hammasi / Navigatsiya / Multimedia / Aloqa / Tizim) ilovalaringizni avtomatik guruhlaydi. Qavs ichidagi raqam — qancha ilova ko'rinishini bildiradi.",
        },
        {
          title: '⭐ Mahkamlangan sevimlilar',
          text:
            "« Sevimlilar » bo'limi eng ko'p ishlatiladigan ilovalarni yuqorida saqlaydi. Qo'shish/olib tashlash uchun: ilovani uzoq bosing → ⭐ Sevimlilarga qo'shish/olib tashlash.",
        },
        {
          title: '👆 Qisqa bosish — proyeksiya',
          text:
            "Ilovani bosing va u darhol klasterga yuboriladi. Agar proyeksiya ishlamayotgan bo'lsa, u avtomatik boshlanadi (~2 s).",
        },
        {
          title: '👆⏱️ Uzoq bosish — harakatlar menyusi',
          text:
            "Har qanday ilovani ushlab turing va to'liq ekran menyu ochiladi: ⭐ Sevimli, Avto-ishga tushirish (proyeksiya bilan), Klasterga / asosiy ekranga ko'chirish, ✕ Majburiy to'xtatish.",
        },
        {
          title: '🚦 Klaster jonli ko\'rinishi',
          text:
            "O\'ng panel klasterda nima ko\'rinayotganini aks ettiradi (tezlik, peredacha, batareya, masofa). Kechikish (12 ms) ulanish to\'g\'ri ekanligini tasdiqlaydi.",
        },
        {
          title: '👁️ To\'liq ekran ko\'rinish',
          text:
            "« To'liq ekran ko'rinish »ni bosing va jonli ko'rinish butun markaziy displeyga kengayadi. Maps\'da to'liq klaviatura bilan manzil yozish uchun qulay — har bir kirish klasterga aks ettiriladi.",
        },
        {
          title: '📸 Skrinshot',
          text:
            "« Olish » tugmasi joriy klaster ko'rinishini PNG sifatida /sdcard/Pictures/DashCast/ ga saqlaydi. Yo'lni ulashish yoki muammoni hujjatlashtirish uchun foydali.",
        },
        {
          title: '↻ Qayta ulanish',
          text:
            "Agar proyeksiya qilingan ilova qotib qolgan bo'lsa, « Qayta ulanish » asl klasterga tegmasdan video oqimini tiklaydi.",
        },
        {
          title: '⏹ Oynani to\'xtatish',
          text:
            "Proyeksiyani toza tugatadi. Qisqa bosish = yumshoq to'xtatish (klaster ADB orqali asl BYD\'ga qaytadi). Uzoq bosish = Sozlamalardagi klaster o'lchamini tiklashga majbur qiladigan « Asl klasterni tiklash » bilan kengaytirilgan menyu.",
        },
      ],
      howTo: {
        title: 'Ilovani klasterga qanday proyeksiya qilish',
        steps: [
          "Asosiy ekranda kerakli ilovani toping (masalan, Maps).",
          "Belgisini bosing → proyeksiya boshlanadi, klaster ~2 s ichida ilovani ko'rsatadi.",
          "O\'ng panel klasterda nima ko\'rinayotganini real vaqtda ko\'rsatadi.",
          "Matn yozish uchun (manzil qidirish): « To\'liq ekran ko\'rinish »ni bosing → ilova markaziy ekranga kengayadi → manzilni yozing → hammasi klasterga aks ettiriladi.",
          "To'xtatish uchun: « Oynani to'xtatish »ni bosing (klaster asl BYD\'ga qaytadi).",
        ],
      },
      tipsTitle: 'Maslahatlar',
      tips: [
        "💡 Avto-ishga tushirish: ilovada ushbu kalitni yoqing va u DashCast har safar ishga tushganda avtomatik proyeksiya qilinadi.",
        "💡 Bo'lingan rejim: ikkinchi ilovaning uzoq bosish menyusidan « Bo'lingan sifatida yuborish »ni tanlang va klasterda 2 ta ilova yonma-yon ko'rinadi.",
        "💡 Chegaralar: agar ilova klasterdan tashqariga chiqsa, Sozlamalar → Chegaralar bo'limini oching va slayderlarni sozlang. Har bir ilova uchun saqlanadi.",
        "💡 Sensorli to'liq ekran: to'liq ekran ko'rinishi rejimida markaziy ekrandagi barmoqlaringiz ilovani haqiqatan boshqaradi — klaviatura, aylantirish, imo-ishoralar, hammasi ishlaydi.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Sozlamalar',
      lead:
        "Sozlamalar ekrani global parametrlar va proyeksiyaning tasvir sozlashini birlashtiradi. Chap panel mavjud bo'lib qoladi — Ilovalar, Sozlamalar, Diagnostika, Tizim va Jurnal o'rtasida joyingizni yo'qotmasdan o'tib turishingiz mumkin.",
      mockupLabel: '3-ekranni ochish (Sozlamalar)',
      featuresTitle: 'Mavjud bo\'limlar',
      features: [
        {
          title: '📺 Klaster turi',
          text:
            "Asbob panelining jismoniy o'lchamini tanlang: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, sukut bo'yicha) yoki 10.25″ (cmd 31). Ushbu qiymat, xususan, « Asl klasterni tiklash » tomonidan ishlatiladi.",
        },
        {
          title: '🌐 Til',
          text:
            "12 til mavjud. Almashtirish darhol — DashCast\'ni qayta ishga tushirish shart emas.",
        },
        {
          title: '↔️ Gorizontal chegara (overscan)',
          text:
            "Slayder 0–200 px. Klasteringizdagi kesilgan chetlarni qoplash uchun chap/o'ngga qora chiziqlar qo'shadi. Har bir ilova uchun saqlanadi — Maps 80 px ishlatishi mumkin, Spotify esa 0\'da qoladi.",
        },
        {
          title: '↕️ Vertikal chegara (overscan)',
          text:
            "Slayder 0–200 px. Yuqori/pastki uchun ham xuddi shunday. Birlashgan chegaralar VirtualDisplay darajasida qo'llaniladi, shunda ilova kesilgan zonalarni « ko'rmaydi ».",
        },
        {
          title: '✅ Qo\'llash / 🔄 Tiklash',
          text:
            "« Qo\'llash » yangi chegaralarni darhol ishlayotgan proyeksiyaga uzatadi. « Tiklash » joriy ilovani 0/0\'ga qaytaradi.",
        },
        {
          title: '📦 OTA yangilanishlar',
          text:
            "DashCast GitHub Releases\'ni avtomatik tekshiradi. Alpha kanali uchun « Pre-release\'larni qo'shish »ni belgilang (tez-tez, lekin eksperimental yangilanishlar).",
        },
        {
          title: '🚗 Avtomobil bilan avtomatik ishga tushirish',
          text:
            "Yoqilganda, DashCast mashina bilan birga ishga tushadi va oxirgi proyeksiya qilingan ilovani tiklaydi. Aks holda uni qo'lda ishga tushirasiz.",
        },
      ],
      howTo: {
        title: 'Ilovaning chegaralarini qanday sozlash',
        steps: [
          "Sozlanadigan ilovani proyeksiya qiling (masalan, Waze).",
          "Sozlamalar → Chegaralar oching.",
          "Chap/o\'ng chetlari to\'g\'ri bo'lguncha gorizontal slayderni harakatlantiring.",
          "Vertikal slayder bilan ham xuddi shunday.",
          "« Qo\'llash »ni bosing → proyeksiya jonli yangilanadi, ilova qayta ishga tushmasdan.",
          "Sozlama faqat o'sha ilova uchun saqlanadi (har bir ilovaning o'z chegaralari bor).",
        ],
      },
      note:
        "⚠️ Agar siz klaster turini o'zgartirsangiz, mos yozuvlar qiymatlari qayta hisoblanishi uchun DashCast\'ni qayta ishga tushiring.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnostika',
      lead:
        "Diagnostika yorlig'i — proyeksiya kutilmagan tarzda ishlayotgan holatlar uchun mo'ljallangan ichki panel. Ko'pchilik foydalanuvchilarga hech qachon kerak bo'lmaydi — u qo'llab-quvvatlash va nosozliklarni tuzatish uchun mo'ljallangan.",
      mockupLabel: '4-ekranni ochish (Diagnostika)',
      featuresTitle: 'Vositalar',
      features: [
        {
          title: 'ClusterService holati',
          text:
            "Proyeksiyani boshqaruvchi Android xizmati ishlab turganini tekshiradi. « bog'lanmagan » holatda tugma uni qayta ishga tushiradi.",
        },
        {
          title: 'VirtualDisplay holati',
          text:
            "Klaster uchun yaratilgan virtual displeyning ID\'sini, uning aniqligini va Qt Surface biriktirilganligini ko'rsatadi.",
        },
        {
          title: 'Mahalliy ADB ulanishi',
          text:
            "localhost:5555 ga ADB tunnelining tezkor sinovi. Agar sinov muvaffaqiyatsiz bo'lsa, odatda BYD sozlamalarida simsiz ADB nosozliklarini tuzatish o'chirilgan.",
        },
        {
          title: 'Maqsadli logcat',
          text:
            "DashCast / AutoContainer / xdja bo'yicha filtrlangan oxirgi 200 logcat satrini oladi. « Ulashish » tugmasi hisobotni yuboradi.",
        },
      ],
      howTo: {
        title: 'Bu yorliqdan qachon foydalanish',
        steps: [
          "Ilovani bosgandan keyin klaster qora qoladi → ClusterService va VirtualDisplay holatini tekshiring.",
          "Ilova « ADB mavjud emas » deydi → Diagnostika yorlig'i → « ADB sinovi » tugmasi.",
          "Qo\'llab-quvvatlash hisobot so\'raydi → Diagnostika → « logcat ulashish ».",
          "Yangilanish endigina o'rnatildi va ishlayotgan versiyani tasdiqlamoqchisiz.",
        ],
      },
      note:
        "ℹ️ Bu yorliq o'z-o'zidan hech narsani o'zgartirmaydi: tugmalar boshqacha ko'rsatilmagan bo'lsa, faqat o'qish testlarini bajaradi.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Tizim ma\'lumotlari',
      lead:
        "Apparat/dasturiy ta'minot muhitingiz haqida faqat o'qish uchun panel. Bu yerda DashCast versiyasi, BYD dasturiy ta'minoti, Android versiyasi va klaster identifikatorini topasiz.",
      mockupLabel: '5-ekranni ochish (Tizim)',
      featuresTitle: 'Ko\'rsatilgan ma\'lumotlar',
      features: [
        {
          title: '🚗 Avtomobil',
          text:
            "Aniqlangan BYD modeli, VIN (mavjud bo'lsa), dasturiy ta'minot bildiruvi (masalan, Di3.0 / 6125F), bildiruv sanasi.",
        },
        {
          title: '📱 Android',
          text:
            "Android versiyasi (10), API darajasi (29), xavfsizlik patch\'i, DiLink build ID.",
        },
        {
          title: '🔌 DashCast',
          text:
            "O'rnatilgan versiya, versionCode, kanal (stable / alpha), oxirgi OTA tekshiruvi, release notes uchun havola.",
        },
        {
          title: '🖥️ Klaster',
          text:
            "Aniqlangan tur (8.8″ / 12.3″ / 10.25″), haqiqiy aniqlik, joriy VirtualDisplay ID, faol Qt paketi (com.xdja.containerservice).",
        },
        {
          title: '📦 Kuzatilgan ilovalar',
          text:
            "Aniqlangan ilovalar soni, mahkamlangan sevimlilar, avto-ishga tushirish yoqilganlar.",
        },
      ],
      tipsTitle: 'Maslahatlar',
      tips: [
        "💡 Qiymatni vaqtinchalik xotiraga nusxalash uchun qatorni uzoq bosing (xato hisoboti uchun foydali).",
        "💡 Pastdagi « Eksport » tugmasi hammasini matn fayliga saqlaydi (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Jurnal',
      lead:
        "DashCast\'ning ichki jurnali: har bir muhim harakatni (proyeksiyalar, tiklashlar, ADB xatolari, yangilanishlar) kuzatadi. Kutilmagan xatti-harakatlarni tushunish yoki qo'llab-quvvatlashga hisobot yuborish uchun foydali.",
      mockupLabel: '6-ekranni ochish (Jurnal)',
      featuresTitle: 'Imkoniyatlar',
      features: [
        {
          title: '🔍 Filtr',
          text:
            "Faqat tegishli qatorlarni saqlash uchun kalit so'zni kiriting (masalan, « ADB », « Maps », « error »). Filtr katta-kichik harflarga sezgir emas.",
        },
        {
          title: '🎨 Rang kodi',
          text:
            "🟢 INFO (yashil) — normal ish. 🟠 WARN (to'q sariq) — diqqat. 🔴 ERROR (qizil) — xato. ⚪ DEBUG (kulrang) — texnik tafsilot.",
        },
        {
          title: '🗑 Tozalash',
          text:
            "Jurnalni bo'shatadi. Tizim logcat tracingiga ta'sir qilmaydi — faqat xotiradagi DashCast tarixi tozalanadi.",
        },
        {
          title: '📤 Ulashish',
          text:
            "Joriy jurnalni .txt sifatida eksport qiladi va Android ulashish varag'ini ochadi (e-mail, Telegram, fayl). DashCast versiyasi va BYD modelini avtomatik kiritadi.",
        },
        {
          title: '⏰ Vaqt tamg\'alari',
          text:
            "Har bir qator mahalliy vaqt bilan boshlanadi (HH:mm:ss.mmm). Uzoq operatsiyalar (Maps ishga tushishi, klasterni tiklash) o'lchanadi va ko'rsatiladi.",
        },
      ],
      howTo: {
        title: 'Xato hisobotini qanday yuborish',
        steps: [
          "Muammoni qayta yarating (masalan, ilova ishga tushirilgandan keyin qora bo'lib qoladi).",
          "Jurnalni oching.",
          "« Ulashish »ni bosing.",
          "Kanalni tanlang (Telegram, e-mail, GitHub Issues).",
          "Ilova qilingan .txt to'liq trasalashni kontekst bilan birga o'z ichiga oladi (versiya, model, dasturiy ta'minot).",
        ],
      },
      note:
        "🔒 Hech qanday shaxsiy ma'lumot (kontaktlar, GPS joylashuv, ilova tarkibi) qayd etilmaydi — faqat DashCast harakatlari va texnik qaytish kodlari.",
    },
  ],

  faq: {
    title: '7. FAQ — Tez-tez beriladigan savollar',
    items: [
      {
        question: '❓ Ilovani bosganimda klaster qora qoladi',
        answer:
          "Uch mumkin sabab: (1) simsiz ADB o'chirilgan — BYD Sozlamalar → Dasturchi tekshiring. (2) ClusterService ishlamayapti — Diagnostika yorlig'i, « Qayta ishga tushirish » tugmasi. (3) Ilova endigina ishdan chiqdi — o'ng panelda « Qayta ulanish »ni bosing.",
      },
      {
        question: '❓ Tasvir klasterda chiqib ketadi / kesiladi',
        answer:
          "Sozlamalar → Chegaralar oching va chetlari to'g'ri bo'lguncha gorizontal/vertikal slayderlarni sozlang. Har bir ilova uchun saqlanadi — bir martagina amalga oshiriladi.",
      },
      {
        question: '❓ Asl BYD asbob paneliga qanday qaytaman?',
        answer:
          "« Oynani to\'xtatish »ni qisqa bosish 95 % holatlarda yetarli. Agar klaster qotib qolsa, xuddi shu tugmani uzoq bosing → menyu → « Asl klasterni tiklash »: DashCast klaster turingizga mos sendInfo ketma-ketligini majbur qiladi.",
      },
      {
        question: '❓ DashCast 12 V akkumulyatorni quritadimi?',
        answer:
          "Yo'q — DashCast avtomobil o'chirilganda avtomatik to'xtaydi (Android.intent.action.SCREEN_OFF + BMS uzilish broadcast\'lari). Dvigatel o'chirilgandan keyin hech qanday fon xizmati qolmaydi.",
      },
      {
        question: '❓ Hissa qo\'shmoqchiman yoki xato haqida xabar bermoqchiman',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — xatolar uchun Issues, savollar uchun Discussions. Diagnostikani tezlashtirish uchun har doim Jurnal eksportini ilova qiling (Jurnal → Ulashish).",
      },
      {
        question: '❓ Klasterda qanday ilovalar ishlaydi?',
        items: [
          "✅ Navigatsiya: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Multimedia: Spotify, YouTube, YouTube Music, Netflix (gorizontalni afzal ko'ring).",
          "✅ Aloqa: Telegram (faqat o'qish rejimi), WhatsApp (bildirishnomalar).",
          "✅ Tizim: kamera, ob-havo, taqvim.",
          "⚠️ Widevine L1 DRM dan foydalanadigan ilovalar (Disney+, Prime Video) VirtualDisplay\'da renderlashdan bosh tortishi mumkin — bu Android cheklovi, DashCast emas.",
        ],
      },
      {
        question: '❓ Yangilanishlar: stable yoki alpha?',
        answer:
          "Stable kanali (sukut bo'yicha) chop etishdan oldin avtomobilda kamida 1 hafta sinovdan o'tkaziladi. Alpha kanali (Sozlamalar → Yangilanishlar) build\'larni kompilyatsiyadan keyin darhol oladi — oldindan sinov uchun foydali, lekin vaqtinchalik regressiyalarni keltirishi mumkin.",
      },
    ],
  },

  footer:
    "DashCast — GPL-3.0 litsenziyasi ostida tarqatiladigan ochiq manbali loyiha. BYD Auto Co., Ltd. bilan bog'liq emas.",
};
