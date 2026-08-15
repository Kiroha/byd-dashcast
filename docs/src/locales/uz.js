export default {
  code: 'uz',
  flag: '🇺🇿',
  name: "O'zbekcha",
  title: "DashCast — Foydalanuvchi qo'llanmasi",
  manualName: "Foydalanuvchi qo'llanmasi",
  meta: 'v1.7.0 · BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Mundarija',

  intro: {
    title: '0. Kirish',
    lead:
      "DashCast BYD markaziy ekranidagi istalgan Android ilovasini asboblar paneliga (rul ortidagi raqamli klaster displeyiga) chiqaradi. Maps, Waze, Spotify yoki ABRP to'g'ridan-to'g'ri ko'z oldingizda — «Tartiblar» rejimi bilan esa bir vaqtda bir nechta ilova, har biri o'z zonasida. U oddiy ilova kabi o'rnatiladi va tizimda hech narsani o'zgartirmaydi.",
    bullets: [
      '✅ DiLink 3 (Seal EU / 6125F) va DiLink 5 (yangiroq BYD bosh qurilmalari) bilan ishlaydi.',
      "✅ Tizim o'zgartirilmaydi: DashCast boshqa har qanday ilova kabi o'rnatiladi.",
      '✅ TCP orqali mahalliy ADB — birinchi avtorizatsiyadan keyin kompyuter kerak emas.',
      "✅ 13 ta interfeys tili, birinchi ishga tushirishda tanlanadi, istalgan vaqtda o'zgartiriladi.",
      '✅ Real vaqtdagi sensorli oyna: klasterni markaziy ekrandan boshqaring.',
      "✅ «Tartiblar» rejimi: klasterda yonma-yon bir nechta ilova, zonalar barmoq bilan chiziladi.",
      '✅ Avtoishga tushirish: DashCast ochilishi bilan proyeksiya + ilova (yoki sevimli tartib).',
      '✅ Chetlar (overscan) har bir ilova uchun saqlanadi.',
      "✅ DiLink 3 old oyna HUD'ida burilishlar bo'yicha o'qlar (qo'llab-quvvatlanadigan proshivkada).",
      "✅ DiLink 3 uchun o'rnatilgan Wi-Fi hotspot yordamchisi (o'z SIM'ingizdan foydalaning).",
      "✅ Klaviatorasiz xato haqida xabar beruvchi, diagnostikani qo'llab-quvvatlashga bir teginishda yuboradi.",
      "✅ Avtomatik OTA yangilanishlari — jimgina o'rnatiladi va ilovani qayta ishga tushiradi.",
    ],
    note:
      "💡 Yagona talab: BYD Sozlamalar → Dasturchi bo'limida simsiz ADB nosozliklarni tuzatishni yoqing. Birinchi ishga tushirishda «Tuzatishga ruxsat berilsinmi?» dialogi paydo bo'ladi — «Ushbu kompyuterdan har doim ruxsat berish» belgilang va tasdiqlang. Buni hech qachon takrorlash kerak bo'lmaydi.",
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
        "DashCast bosh ekrani. Chapda: qidiruv, filtrlar va sevimlilar bilan barcha ilovalaringiz, hamda yon navigatsiya paneli. O'ngda: klasterning real vaqtdagi ko'rinishi, «To'liq ekranli oyna» / «Proyeksiyani to'xtatish» tugmalari va — «Tartiblar» rejimida — yig'iladigan «Klaster tartibi» tanlagichi.",
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
            "O'ng panel klasterda nima borligini jonli aks ettiradi. Ko'rinishga teginishlaringiz proyeksiyalangan ilovaga uzatiladi — aylantirish, kattalashtirish, klaviatura, hammasi ishlaydi.",
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
          title: "🗂️ Klaster tartibi tanlagichi (standart holatda yig'ilgan)",
          text:
            "«Tartiblar» rejimida tugmalar ostida ixcham «KLASTER TARTIBI» sarlavhasi turadi. Uni yoyish uchun teging: «Tartib ilovalarini ishga tushirish», hamda har bir saqlangan tartib uchun karta (Erkin rejim / sizning shablonlaringiz / ＋ Boshqarish). Jonli ko'rinish to'liq balandligini saqlashi uchun u standart holatda yig'ilgan.",
        },
        {
          title: '📺 Suzuvchi tugma',
          text:
            "📺 tugmasi boshqa ilovalar ustida qoladi: teginish = oynani ochish, uzoq bosish = oxirgi proyeksiyalangan ilovalar o'rtasida tez almashish.",
        },
        {
          title: '🧭 Yon navigatsiya paneli',
          text:
            "Ilovalar, Sozlamalar, Tizim, Jurnal, xato haqida xabar beruvchi va — o'z SIM'ingiz bilan DiLink 3'da — Hotspot yordamchisiga tez kirish.",
        },
      ],
      howTo: {
        title: 'Ilovani klasterga qanday proyeksiyalash',
        steps: [
          "Kerakli ilovani toping (mas. Maps) — kerak bo'lsa qidiruv yoki filtrlar.",
          "Belgisiga teging → proyeksiya boshlanadi, klaster ~2 s ichida ilovaga o'tadi.",
          "O'ngdagi ko'rinish klasterdagini jonli ko'rsatadi.",
          "Matn kiritish uchun: «To'liq ekranli oyna» → manzilingizni yozing → hammasi ko'chiriladi.",
          "To'xtatish uchun: «Proyeksiyani to'xtatish» — klaster tug'ma BYD'ga qaytadi.",
        ],
      },
      tipsTitle: 'Maslahatlar',
      tips: [
        "💡 Avtoishga tushirish: ilovani tanlang (uzoq bosish → Avtoishga tushirish), har DashCast ochilishida avtomatik proyeksiyalansin — proyeksiya o'zi faollashadi.",
        "💡 Sevimli tartib: «Klaster tartibi» tanlagichida tanlangan karta — avtoishga tushirish faollashtiradigan karta («Tartiblar» bo'limiga qarang).",
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
            "Yoqilgan bo'lsa, DashCast mashina bilan ishga tushadi va oxirgi proyeksiyalangan ilovani (yoki sevimli tartibni) tiklaydi. Aks holda uni BYD ro'yxatidan ishga tushiring.",
        },
        {
          title: '🗂️ «Tartiblar» rejimi',
          text:
            "Maxsus zonalar bilan ko'p ilovali proyeksiyani yoqadi (avtomatik boshqariladigan Proxy ADB Daemon talab qilinadi). Bosh ekranda «Klaster tartibi» tanlagichini va «Tartiblar» yorlig'ini ko'rsatadi.",
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
          title: "📶 O'z SIM'imdan foydalanish (DiLink 3)",
          text:
            "Hotspot yordamchisi navigatsiya panelida ko'rinishini boshqaradi. Avtomobilni o'z telefoningiz/SIM ma'lumotlaringiz orqali ulasangiz, uni yoqilgan qoldiring. «Hotspot» bo'limiga qarang.",
        },
        {
          title: '📦 OTA yangilanishlari',
          text:
            "DashCast har ishga tushirishda GitHub'da yangi versiyalarni tekshiradi. Yangilanishlar endi jimgina o'rnatiladi va ilovani o'zi qayta ishga tushiradi («Yangilanishlar» bo'limiga qarang). Beta-kanal uchun «Oldindan versiyalarni qo'shish» belgilang.",
        },
        {
          title: '🌐 Til',
          text: '13 til — almashish oniy.',
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
            "Kanvasda (klaster nusxasi) to'rtburchak chizish uchun barmog'ingizni torting. Dialog ochiladi: nom, pikselgacha aniq pozitsiya/o'lchamlar va bog'lanadigan ilova.",
        },
        {
          title: "🔗 Ilova bog'lash",
          text:
            "Har bir zonani ilovaga bog'lash mumkin: tartib faollashganda ilova o'z zonasida avtomatik ochiladi. Ilovasiz zona bo'sh qoladi — keyinroq u yerga istalgan narsani joylashtiring.",
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
            "«Sevimli» tugmasi (yoki bosh ekran tanlagich kartasiga teginish) «Avtomatik sevimli tartib» DashCast ishga tushganda faollashtiradigan tartibni belgilaydi — proyeksiya bilan birga.",
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
        "💡 Har bir tanlagich kartasining mini-ko'rinishi tartibning haqiqiy zonalarini ko'rsatadi — bir qarashda taniladi.",
        "💡 Ilova o'z zonasida ko'rinishdan bosh tortadimi? Ba'zi ilovalar nisbatni talab qiladi; 16:9 ga yaqinroq zonani sinab ko'ring.",
      ],
      note:
        "ℹ️ «Tartiblar» rejimi Proxy ADB Daemon'ga tayanadi (avtomatik ishga tushadi). Birinchi sovuq ishga tushirish: ilovalar ko'ringuncha 6–8 s kuting — bu klasterning faollashtirish ketma-ketligi.",
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: "5. HUD navigatsiya o'qlari (DiLink 3)",
      lead:
        "Old oyna Head-Up Display'i burilish o'qlarini qo'llab-quvvatlaydigan DiLink 3 avtomobillarida DashCast navigatsiya ilovangizdan HUD'ga burilishlar bo'yicha yo'l ko'rsatmasini chizishi mumkin — manevr o'qi va unga qadar masofa to'g'ridan-to'g'ri old oynada.",
      mockupLabel: "2-ekranni ko'rish (Bosh)",
      featuresTitle: 'Qanday ishlaydi',
      features: [
        {
          title: "🧭 Maps / Waze'dan yo'l ko'rsatma",
          text:
            "DashCast navigatsiya ilovangiz allaqachon chiqaradigan burilishlar bildirishnomasini (Google Maps, Waze) o'qiydi va manevr + masofani avtomobilning CAN shinasi orqali HUD'ga uzatadi. Qo'shimcha ilova kerak emas.",
        },
        {
          title: "🚗 Proshivkaga bog'liq",
          text:
            "Faqat yangiroq DiLink 3 HUD proshivkalari o'qlarni chiza oladi. Agar sizniki chiza olmasa, o'qlar shunchaki ko'rinmaydi — DashCast HUD apparati ega bo'lmagan imkoniyatni qo'sha olmaydi.",
        },
        {
          title: "➡️ To'g'ri yo'nalish belgilari",
          text:
            "To'g'ri / chap / o'ng manevrlar mos HUD belgisiga moslashtiriladi, burilishga qadar masofaning jonli teskari hisobi bilan.",
        },
      ],
      howTo: {
        title: "HUD'da o'qlarni qanday olish kerak",
        steps: [
          "DashCast'ga bildirishnomalarga kirish berilganiga ishonch hosil qiling (u birinchi foydalanishda so'raydi).",
          "Old oyna HUD'ini yoqing va BYD HUD menyusida uni navigatsiya ko'rsatish rejimiga sozlang.",
          "Google Maps yoki Waze'da marshrutni boshlang.",
          "Har bir burilishga yaqinlashganingizda manevr o'qi va masofa HUD'da paydo bo'ladi.",
        ],
      },
      note:
        "ℹ️ HUD o'qlari DiLink 3 xususiyati va HUD proshivkangizga bog'liq. Agar hech narsa ko'rinmasa, sizning HUD'ingiz o'qlarni qo'llab-quvvatlashdan oldin chiqqan bo'lishi mumkin — bu apparat cheklovi, DashCast xatosi emas.",
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Wi-Fi Hotspot yordamchisi (DiLink 3)',
      lead:
        "DiLink 3'da, agar avtomobilni internetga o'z SIM'ingiz/telefoningiz orqali ulasangiz, Hotspot yordamchisi shu ulanishni faol saqlaydi, shunda navigatsiya va striming ishlashda davom etadi. U navigatsiya panelida faqat sizga tegishli bo'lganda ko'rinadi.",
      mockupLabel: "3-ekranni ko'rish (Sozlamalar)",
      featuresTitle: 'Imkoniyatlar',
      features: [
        {
          title: '📶 Faol saqlash',
          text:
            "Avtomobil uyg'onganda (mas. ACC yoqilgandan keyin) Wi-Fi ulanishni qayta yoqadi, shunda uni har safar qo'lda yoqishingiz shart emas.",
        },
        {
          title: '👁️ Jonli holat',
          text:
            "Hotspot yoqilganini va nechta mijoz ulanganini ko'rsatadi, shunda avtomobil haqiqatan ham onlayn ekanini tasdiqlashingiz mumkin.",
        },
        {
          title: "⚙️ Faqat foydali bo'lganda ko'rinadi",
          text:
            "Hotspot bandi faqat DiLink 3'da va faqat Sozlamalarda «O'z SIM'imdan foydalanish» yoqilganda ko'rinadi. Boshqa konfiguratsiyalarda u yashirin qoladi.",
        },
      ],
      howTo: {
        title: 'Undan qanday foydalanish kerak',
        steps: [
          "Sozlamalar → «O'z SIM'imdan foydalanish» yoqilganiga ishonch hosil qiling.",
          "Navigatsiya panelidan «Hotspot»ni oching.",
          "Ulanishni boshlang / tasdiqlang — holat uning yoqilganini ko'rsatadi.",
          "Avtomobil keyingi safar uyg'onganda u o'zini qayta yoqadi.",
        ],
      },
      note:
        "ℹ️ Bu yordamchi o'z ma'lumotlaringiz orqali ulangan DiLink 3 avtomobillari uchun. Agar avtomobilingizda o'zining o'rnatilgan ma'lumotlar tarifi bo'lsa, u sizga kerak emas.",
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Muammo haqida xabar berish (xato haqida xabar beruvchi)',
      lead:
        "Klaviatorasiz, avtomobil ichidagi xato haqida xabar beruvchi. Uch teginishda nima noto'g'ri ketganini tanlaysiz; DashCast chegaralangan diagnostik suratni (jurnallar + tizim holati) oladi va uni to'g'ridan-to'g'ri qo'llab-quvvatlash kanaliga yuboradi — yozishsiz, kabellarsiz.",
      mockupLabel: "6-ekranni ko'rish (Xabar)",
      featuresTitle: 'Qanday ishlaydi',
      features: [
        {
          title: '1️⃣ Toifa',
          text:
            "Qaysi soha zararlanganini tanlang: oyna, ilova, ovoz, ulanish, muzlash, HUD… Oltita katta plitka, faqat teginish.",
        },
        {
          title: '2️⃣ Ilova',
          text:
            "DashCast hozir klasterdagi ilovani avtomatik aniqlaydi va uni taklif qiladi, shuningdek «Aniq ilova yo'q» va «Boshqa».",
        },
        {
          title: '3️⃣ Muammo',
          text:
            "Qisqa ro'yxatdan eng yaqin alomatni tanlang. Ixtiyoriy erkin matn maydoni xohlasangiz tafsilot qo'shishga imkon beradi — lekin u hech qachon talab qilinmaydi.",
        },
        {
          title: '📎 Avtomatik diagnostika',
          text:
            "Hisobot so'nggi jurnallarni va muammo paytidagi tizim/klaster holatini birlashtiradi — aynan qo'llab-quvvatlashga kerak bo'lgan narsa, siz uchun olib qo'yilgan.",
        },
        {
          title: '🚀 Bir teginishda yuborish',
          text:
            "Agar qo'llab-quvvatlash kanali sozlangan bo'lsa, hisobot to'g'ridan-to'g'ri yuklanadi; aks holda DashCast Android ulashish menyusini ochadi, shunda uni Telegram, e-mail yoki GitHub orqali yuborishingiz mumkin.",
        },
        {
          title: '📺 Istalgan joydan',
          text:
            "Suzuvchi 📺 tugmasi va navigatsiya paneli ikkalasi ham xabar beruvchini ochadi, shunda boshqa ilova proyeksiyalangan paytda ham hisobot yuborishingiz mumkin.",
        },
      ],
      howTo: {
        title: 'Hisobotni qanday yuborish kerak',
        steps: [
          'Xato haqida xabar beruvchini oching (navigatsiya paneli yoki suzuvchi tugma).',
          'Muammoga mos toifaga teging.',
          "Ilovani tasdiqlang (yoki «Aniq ilova yo'q» tanlang).",
          "Eng yaqin muammoni tanlang; foydali bo'lsa izoh qo'shing.",
          "«Yuborish»ga teging — diagnostika avtomatik ravishda qo'llab-quvvatlashga jo'naydi.",
        ],
      },
      note:
        '🔒 Yuborishdan oldin DashCast VIN raqamini, Wi-Fi tarmoq nomlarini, apparat manzillarini va joylashuvlarni olib tashlaydi. DashCast jurnali va Android tizim jurnali boricha qoladi — ularda boshqa ilovalar yozganlari boʻladi. Har qanday yuborishdan oldin bir marta soʻraladi, rad etsangiz avtomobildan hech narsa chiqmaydi.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Tizim hisoboti',
      lead:
        "Faqat o'qish uchun panel: versiyalar, aniqlangan displeylar va DashCast xizmatlarining jonli holati. Biror narsa noto'g'ri ko'ringanda tekshiriladigan birinchi ekran.",
      mockupLabel: "5-ekranni ko'rish (Tizim)",
      featuresTitle: "Ko'rsatiladigan ma'lumotlar",
      features: [
        {
          title: '🖥️ Displeylar',
          text:
            "Bosh ekran (ruxsat, zichlik) va klasterning virtual displeyi real vaqtdagi holati bilan.",
        },
        {
          title: '⚙️ Xizmatlar',
          text:
            "ClusterService (proyeksiya), MirrorDaemon (oyna), Proxy ADB Daemon (imtiyozli operatsiyalar), AdbLocalClient (ADB tunneli) — har biri yashil/qizil nuqta va to'xtaganda qayta ishga tushirish tugmasi bilan.",
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
        "💡 Biror narsa noto'g'ri ko'rinsa, avval shu ekranni tekshiring, keyin xato haqida xabar beruvchidan hisobot yuboring.",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Jurnal',
      lead:
        "DashCast ichki jurnali: barcha muhim amallar (proyeksiyalar, tiklashlar, ADB xatolari, yangilanishlar) uzluksiz qayd etiladi. Kutilmagan xatti-harakatni tushunish uchun foydali; shuningdek bu xato haqida xabar beruvchi siz uchun biriktiradigan ma'lumotdir.",
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
        title: "Xato haqida xabar beruvchini afzal ko'ring",
        steps: [
          "Ko'pchilik muammolar uchun xato haqida xabar beruvchidan foydalaning (7-bo'lim) — u jurnalni va tizim holatini avtomatik oladi.",
          "Jurnal ekrani izni o'zingiz o'qishni yoki faqat xom jurnalni ulashishni xohlaganingizda shu yerda.",
        ],
      },
      note:
        '🔒 Jurnal DashCast nima qilayotganini yozadi, jumladan paket nomlari va bajarilgan buyruqlar chiqishini. Bu ekrandan ulashsangiz, u boricha yuboriladi — VIN raqamini, tarmoq nomlarini va joylashuvlarni olib tashlaydigan filtrlash xato hisobotlari uchun ishlaydi, bu yerda emas.',
    },
  ],

  faq: {
    title: "10. FAQ — Ko'p so'raladigan savollar",
    items: [
      {
        question: '❓ Ilovaga tekkanimda klaster qora qoladi',
        answer:
          "Uchta mumkin sabab: (1) simsiz ADB o'chirilgan — BYD Sozlamalar → Dasturchi tekshiring. (2) To'xtagan xizmat — «Tizim» ekrani, qizil qatorni qayta ishga tushiring. (3) Ilova hozirgina qulagan — belgisiga qayta teging. Hali ham qotib turibdimi? Xato haqida xabar beruvchidan hisobot yuboring.",
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
          "Sozlamalardagi uch shartni tekshiring: «Tartiblar rejimi» yoqilgan, «Avtomatik sevimli tartib» yoqilgan va tartib ⭐ sevimli deb belgilangan (bosh ekran tanlagichi yoki «Tartiblar» yorlig'idagi «Sevimli» tugmasi). Sovuq ishga tushirishda 6–8 s kuting.",
      },
      {
        question: "❓ HUD'imda navigatsiya o'qlari yo'q",
        answer:
          "HUD o'qlari DiLink 3 xususiyati va ularni chiza oladigan HUD proshivkasini talab qiladi. Bildirishnomalarga kirish berilganiga, HUD navigatsiya ko'rsatish rejimida yoqilganiga va Maps/Waze'da marshrut ishlab turganiga ishonch hosil qiling. Agar hech narsa ko'rinmasa, HUD proshivkangiz o'qlarni qo'llab-quvvatlashdan oldin chiqqan bo'lishi mumkin — bu apparat cheklovi, xato emas.",
      },
      {
        question: '❓ Menga Hotspot yordamchisi kerakmi?',
        answer:
          "Faqat DiLink 3'da, agar avtomobilni o'z SIM'ingiz/telefon ulanishingiz orqali onlayn qilsangiz. U shu ulanishni uyg'onishlar davomida faol saqlaydi. Agar avtomobilingizda o'zining ma'lumotlar tarifi bo'lsa, e'tibor bermang — «O'z SIM'imdan foydalanish» yoqilmagan bo'lsa, u yashirin qoladi.",
      },
      {
        question: "❓ Yangilanishlar endi qanday o'rnatiladi?",
        answer:
          "DashCast har ishga tushirishda GitHub'ni tekshiradi. Yangilanish yuklab olinganda u jimgina o'rnatiladi va ilovani o'zi qayta ishga tushiradi — «O'rnatilsinmi?» so'rovi yo'q. Bu mumkin bo'lmagan avtomobilda u odatdagi tizim o'rnatuvchisiga qaytadi. Bu xususiyatga ega versiyaga o'tganingizdan keyingi eng birinchi yangilanish hali ham bir marta so'rashi mumkin.",
      },
      {
        question: "❓ DashCast 12 V akkumulyatorni o'tirg'izadimi?",
        answer:
          "Yo'q — DashCast avtomobil bilan birga to'xtaydi. Dvigatel o'chiq paytda hech qanday fon xizmati faol qolmaydi.",
      },
      {
        question: '❓ Klasterda qaysi ilovalar ishlaydi?',
        items: [
          '✅ Navigatsiya: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
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
        question: "❓ Hissa qo'shmoqchiman yoki xato haqida xabar bermoqchiman",
        answer:
          "Eng tez yo'l uchun ilova ichidagi xato haqida xabar beruvchidan foydalaning (u siz uchun diagnostikani biriktiradi). Kod va xususiyat so'rovlari uchun: GitHub https://github.com/Kiroha/byd-dashcast — xatolar uchun Issues, savollar uchun Discussions.",
      },
    ],
  },

  footer:
    "DashCast — MIT litsenziyasi ostida tarqatiladigan ochiq kodli loyiha. BYD Auto Co., Ltd. bilan aloqasi yo'q.",
};
