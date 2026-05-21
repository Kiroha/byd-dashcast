export default {
  code: 'es',
  flag: '🇪🇸',
  name: 'Español',
  title: 'DashCast — Manual de Usuario',
  manualName: 'Manual de Usuario',
  meta: 'v0.9.92-alpha · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Contenido',

  intro: {
    title: '0. Introducción',
    lead:
      "DashCast permite mostrar cualquier aplicación Android desde la pantalla central BYD en el cuadro de instrumentos digital. Así puedes tener Maps, Waze, Spotify o YouTube directamente detrás del volante, sin perder el acceso a las indicaciones nativas BYD (velocidad, indicadores, autonomía).",
    bullets: [
      "✅ Compatible con BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).",
      "✅ Sin modificación del sistema: DashCast se instala como una app normal.",
      "✅ ADB local TCP — no se necesita ordenador después de la primera autorización.",
      "✅ 12 idiomas, elegidos al primer arranque.",
      "✅ Actualizaciones OTA integradas (canal alfa opcional).",
      "✅ Márgenes de overscan guardados por aplicación.",
      "✅ Espejo en pantalla completa táctil para controlar el cluster desde la central.",
      "✅ Modo split (dos apps lado a lado en el cluster).",
    ],
    note:
      "💡 Requisito único: activar la depuración ADB inalámbrica en Ajustes BYD → Desarrollador. Al primer lanzamiento aparece un diálogo « ¿Permitir depuración? » — marca « Permitir siempre » y confirma. Nunca tendrás que repetirlo.",
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Pantalla de bienvenida — selección de idioma',
      lead:
        "En el primer arranque, DashCast muestra una cuadrícula con los 12 idiomas disponibles. Toca el que prefieras; se guarda y la pantalla de bienvenida no volverá a aparecer. Puedes cambiar el idioma en cualquier momento desde Ajustes → Idioma.",
      mockupLabel: 'Abrir pantalla 1 (Bienvenida)',
      featuresTitle: 'Detalles',
      features: [
        {
          title: '12 idiomas soportados',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. El idioma seleccionado se aplica al instante, sin reiniciar.",
        },
        {
          title: 'Dirección de lectura automática',
          text:
            "El árabe pasa automáticamente al diseño de derecha a izquierda (RTL): la barra de navegación se mueve a la derecha, las listas se invierten, los iconos siguen siendo legibles.",
        },
        {
          title: 'Cambiable en cualquier momento',
          text:
            "Para cambiar el idioma más tarde: pulsación larga sobre el logo DashCast en la barra lateral → 🌐 Idioma. El nuevo idioma se aplica al vuelo.",
        },
      ],
      howTo: {
        title: 'Cómo hacerlo',
        steps: [
          "Inicia DashCast (icono azul en el cajón de aplicaciones BYD).",
          "La pantalla de bienvenida muestra la cuadrícula 4×3 de idiomas.",
          "Toca tu idioma. La interfaz cambia inmediatamente.",
          "Se abre la pantalla principal — DashCast está listo.",
        ],
      },
      note:
        "ℹ️ Si cambias de idioma con una proyección activa, esta sigue sin interrupción; solo la interfaz DashCast se traduce.",
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Pantalla principal — Apps y Cluster',
      lead:
        "Pantalla central de DashCast. A la izquierda, la lista de todas las apps instaladas con búsqueda, filtros por categoría y favoritos. A la derecha, la previsualización en vivo del cluster con las acciones principales: vista completa, captura, reconectar, detener proyección.",
      mockupLabel: 'Abrir pantalla 2 (Principal)',
      featuresTitle: 'Todo lo que puedes hacer',
      features: [
        {
          title: '🔍 Barra de búsqueda',
          text:
            "Escribe unas letras para filtrar la lista al vuelo (busca tanto en el nombre como en el paquete). El botón ▦ a la derecha alterna entre lista y cuadrícula.",
        },
        {
          title: '🏷️ Filtros por categoría',
          text:
            "Las pastillas de colores (Todas / Navegación / Multimedia / Comunicación / Sistema) agrupan las apps automáticamente. El número entre paréntesis indica cuántas son visibles.",
        },
        {
          title: '⭐ Favoritos fijados',
          text:
            "La sección « Favoritos » mantiene tus apps más usadas en la parte superior. Para añadir/quitar: pulsación larga sobre la app → ⭐ Añadir/Quitar de favoritos.",
        },
        {
          title: '👆 Toque corto — proyectar',
          text:
            "Toca una app para enviarla inmediatamente al cluster. Si la proyección no estaba activa, arranca automáticamente (calentamiento del cluster ~2 s).",
        },
        {
          title: '👆⏱️ Pulsación larga — menú de acciones',
          text:
            "Mantén pulsada cualquier app para abrir un menú a pantalla completa: ⭐ Favorito, Auto-arranque (con la proyección), Mover a cluster / pantalla principal, ✕ Forzar parada.",
        },
        {
          title: '🚦 Vista en vivo del cluster',
          text:
            "El panel derecho refleja lo que se muestra en el cluster (velocidad, marcha, batería, autonomía). La latencia (12 ms) confirma una conexión correcta.",
        },
        {
          title: '👁️ Previsualización a pantalla completa',
          text:
            "Toca « Vista completa » para expandir la previsualización a toda la pantalla central. Útil para escribir una dirección en Maps con el teclado completo: todo se replica al cluster.",
        },
        {
          title: '📸 Captura',
          text:
            "El botón « Captura » guarda la vista actual del cluster como PNG en /sdcard/Pictures/DashCast/. Útil para compartir una ruta o documentar una incidencia.",
        },
        {
          title: '↻ Reconectar',
          text:
            "Si la app proyectada se ha quedado bloqueada, « Reconectar » restablece el flujo de vídeo sin tocar el cluster original.",
        },
        {
          title: '⏹ Detener espejo',
          text:
            "Termina limpiamente la proyección. Toque corto = parada suave (cluster vuelve al BYD nativo vía ADB). Pulsación larga = menú enriquecido con « Restaurar cluster original », que fuerza la restauración usando el tamaño del cluster definido en Ajustes.",
        },
      ],
      howTo: {
        title: 'Cómo proyectar una app en el cluster',
        steps: [
          "En la pantalla principal, localiza la app deseada (p. ej. Maps).",
          "Toca su icono → la proyección arranca, el cluster muestra la app en ~2 s.",
          "El panel derecho muestra en vivo lo que aparece en el cluster.",
          "Para escribir (buscar una dirección): toca « Vista completa » → la app se expande en la central → escribe → todo se replica al cluster.",
          "Para detener: toca « Detener espejo » (el cluster vuelve al BYD nativo).",
        ],
      },
      tipsTitle: 'Consejos',
      tips: [
        "💡 Auto-arranque: activa este interruptor en una app para proyectarla automáticamente cada vez que arranque DashCast.",
        "💡 Modo split: desde el menú largo de una segunda app, elige « Enviar como split » para tener 2 apps en paralelo en el cluster.",
        "💡 Márgenes: si la app desborda el cluster, abre Ajustes → Márgenes y ajusta los deslizadores. Por aplicación.",
        "💡 Pantalla completa táctil: en modo vista completa, tus dedos sobre la pantalla central controlan la app — teclado, scroll, gestos, todo funciona.",
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ajustes',
      lead:
        "La pantalla de Ajustes reúne las opciones globales y el ajuste de imagen de la proyección. La barra lateral izquierda permanece disponible: puedes pasar entre Apps, Ajustes, Diag, Sistema y Diario sin perder la posición.",
      mockupLabel: 'Abrir pantalla 3 (Ajustes)',
      featuresTitle: 'Secciones disponibles',
      features: [
        {
          title: '📺 Tipo de cluster',
          text:
            "Selecciona el tamaño físico del cuadro de instrumentos: 8.8″ (sendInfo cmd 29), 12.3″ Seal EU (cmd 30, por defecto) o 10.25″ (cmd 31). Este valor lo usa especialmente « Restaurar cluster original ».",
        },
        {
          title: '🌐 Idioma',
          text:
            "12 idiomas disponibles. Cambio instantáneo, sin reiniciar DashCast.",
        },
        {
          title: '↔️ Margen horizontal (overscan)',
          text:
            "Deslizador 0–200 px. Añade barras negras a izquierda/derecha para compensar bordes recortados en tu cluster. Valor por aplicación: Maps puede usar 80 px y Spotify 0.",
        },
        {
          title: '↕️ Margen vertical (overscan)',
          text:
            "Deslizador 0–200 px. Lo mismo arriba/abajo. Los márgenes combinados se aplican a nivel del VirtualDisplay para que la app no « vea » las zonas recortadas.",
        },
        {
          title: '✅ Aplicar / 🔄 Reiniciar',
          text:
            "« Aplicar » empuja los nuevos márgenes a la proyección activa al instante. « Reiniciar » devuelve la app actual a 0/0.",
        },
        {
          title: '📦 Actualizaciones OTA',
          text:
            "DashCast comprueba GitHub Releases automáticamente. Activa « Incluir pre-releases » para recibir el canal alfa (más frecuente pero experimental).",
        },
        {
          title: '🚗 Arranque automático con el vehículo',
          text:
            "Si está activo, DashCast arranca con el coche y restaura la última app proyectada. Si no, lo lanzas manualmente.",
        },
      ],
      howTo: {
        title: 'Cómo afinar los márgenes de una app',
        steps: [
          "Proyecta la app a ajustar (p. ej. Waze).",
          "Abre Ajustes → Márgenes.",
          "Mueve el deslizador horizontal hasta que los bordes izquierdo/derecho queden bien.",
          "Lo mismo con el vertical.",
          "Toca « Aplicar » → la proyección se actualiza en directo, sin reiniciar la app.",
          "El ajuste solo se guarda para esa app (cada app tiene sus propios márgenes).",
        ],
      },
      note:
        "⚠️ Si cambias el tipo de cluster, reinicia DashCast para que se recalculen los valores de referencia.",
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '4. Diagnóstico',
      lead:
        "La pestaña Diag es un panel interno reservado para casos en los que la proyección se comporta mal. La mayoría de usuarios nunca lo necesitará — está pensado para soporte y depuración.",
      mockupLabel: 'Abrir pantalla 4 (Diag)',
      featuresTitle: 'Herramientas',
      features: [
        {
          title: 'Estado de ClusterService',
          text:
            "Verifica que el servicio Android responsable de la proyección está activo. Si está « no enlazado », un botón lo reinicia.",
        },
        {
          title: 'Estado del VirtualDisplay',
          text:
            "Muestra el ID del display virtual creado para el cluster, su resolución y si hay un Surface Qt asociado.",
        },
        {
          title: 'Conexión ADB local',
          text:
            "Test rápido del túnel ADB hacia localhost:5555. Si falla, normalmente el ADB inalámbrico se ha desactivado en los Ajustes BYD.",
        },
        {
          title: 'logcat orientado',
          text:
            "Captura las últimas 200 líneas de logcat filtradas por DashCast / AutoContainer / xdja. El botón « Compartir » envía el informe.",
        },
      ],
      howTo: {
        title: 'Cuándo usar esta pestaña',
        steps: [
          "Cluster en negro tras tocar una app → comprueba ClusterService y VirtualDisplay.",
          "La app indica « ADB no disponible » → Diag → botón « Probar ADB ».",
          "Soporte pide un informe → Diag → « Compartir logcat ».",
          "Acabas de instalar una actualización y quieres confirmar la versión activa.",
        ],
      },
      note:
        "ℹ️ Esta pestaña no modifica nada por sí sola: los botones realizan comprobaciones de solo lectura salvo indicación contraria.",
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '5. Información del sistema',
      lead:
        "Panel de solo lectura sobre tu entorno hardware/software. Aquí encuentras la versión de DashCast, el firmware BYD, la versión de Android y el identificador del cluster.",
      mockupLabel: 'Abrir pantalla 5 (Sistema)',
      featuresTitle: 'Información mostrada',
      features: [
        {
          title: '🚗 Vehículo',
          text:
            "Modelo BYD detectado, VIN (si disponible), build del firmware (p. ej. Di3.0 / 6125F), fecha del build.",
        },
        {
          title: '📱 Android',
          text:
            "Versión Android (10), nivel de API (29), parche de seguridad, ID de build DiLink.",
        },
        {
          title: '🔌 DashCast',
          text:
            "Versión instalada, versionCode, canal (estable / alfa), última verificación OTA, enlace a las notas de la versión.",
        },
        {
          title: '🖥️ Cluster',
          text:
            "Tipo detectado (8.8″ / 12.3″ / 10.25″), resolución real, ID del VirtualDisplay activo, paquete Qt activo (com.xdja.containerservice).",
        },
        {
          title: '📦 Apps registradas',
          text:
            "Número de apps detectadas, favoritas fijadas, apps con auto-arranque.",
        },
      ],
      tipsTitle: 'Consejos',
      tips: [
        "💡 Pulsación larga en una fila para copiar el valor al portapapeles (útil para un informe de bug).",
        "💡 El botón « Exportar » abajo guarda todo en un archivo de texto (/sdcard/DashCast/sysinfo.txt).",
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '6. Diario',
      lead:
        "Diario interno de DashCast: registra cada acción importante (proyecciones, restauraciones, errores ADB, actualizaciones). Útil para entender un comportamiento inesperado o enviar un informe a soporte.",
      mockupLabel: 'Abrir pantalla 6 (Diario)',
      featuresTitle: 'Funciones',
      features: [
        {
          title: '🔍 Filtro',
          text:
            "Escribe una palabra clave para conservar solo las líneas relevantes (p. ej. « ADB », « Maps », « error »). El filtro no distingue mayúsculas/minúsculas.",
        },
        {
          title: '🎨 Código de color',
          text:
            "🟢 INFO (verde) — operación normal. 🟠 WARN (naranja) — atención. 🔴 ERROR (rojo) — fallo. ⚪ DEBUG (gris) — detalle técnico.",
        },
        {
          title: '🗑 Vaciar',
          text:
            "Vacía el diario. La traza logcat del sistema no se ve afectada — solo se borra el historial DashCast en memoria.",
        },
        {
          title: '📤 Compartir',
          text:
            "Exporta el diario actual como .txt y abre el menú de compartir Android (correo, Telegram, archivo). Incluye automáticamente la versión DashCast y el modelo BYD.",
        },
        {
          title: '⏰ Marcas de tiempo',
          text:
            "Cada línea va con la hora local (HH:mm:ss.mmm). Las operaciones largas (lanzamiento Maps, restauración) se miden y se muestran.",
        },
      ],
      howTo: {
        title: 'Cómo enviar un informe de bug',
        steps: [
          "Reproduce la incidencia (p. ej. la app queda en negro al lanzarla).",
          "Abre Diario.",
          "Toca « Compartir ».",
          "Elige el canal (Telegram, correo, GitHub Issues).",
          "El .txt adjunto contiene la traza completa con el contexto (versión, modelo, firmware).",
        ],
      },
      note:
        "🔒 No se registra ningún dato personal (contactos, GPS, contenido de las apps) — solo acciones DashCast y códigos técnicos.",
    },
  ],

  faq: {
    title: '7. FAQ — Preguntas frecuentes',
    items: [
      {
        question: '❓ El cluster queda en negro al tocar una app',
        answer:
          "Tres causas posibles: (1) ADB inalámbrico desactivado — comprueba Ajustes BYD → Desarrollador. (2) ClusterService inactivo — pestaña Diag, botón « Reiniciar ». (3) La app acaba de fallar — toca « Reconectar » en el panel derecho.",
      },
      {
        question: '❓ La imagen desborda / se recorta en el cluster',
        answer:
          "Abre Ajustes → Márgenes y ajusta los deslizadores horizontal/vertical hasta que los bordes queden bien. Por aplicación, una sola vez.",
      },
      {
        question: '❓ ¿Cómo vuelvo al panel BYD original?',
        answer:
          "Un toque corto en « Detener espejo » basta el 95 % de las veces. Si el cluster se queda atascado, pulsación larga sobre el mismo botón → menú → « Restaurar cluster original »: DashCast fuerza la secuencia sendInfo del tipo de cluster.",
      },
      {
        question: '❓ ¿DashCast descarga la batería de 12 V?',
        answer:
          "No — DashCast se detiene automáticamente al apagar el coche (Android.intent.action.SCREEN_OFF + broadcasts de desconexión BMS). Ningún servicio queda activo después de apagar el motor.",
      },
      {
        question: '❓ Quiero contribuir o reportar un bug',
        answer:
          "GitHub: https://github.com/Kiroha/byd-dashcast — Issues para bugs, Discussions para preguntas. Adjunta siempre una exportación del Diario (Diario → Compartir) para acelerar el diagnóstico.",
      },
      {
        question: '❓ ¿Qué apps funcionan en el cluster?',
        items: [
          "✅ Navegación: Google Maps, Waze, Yandex Navi, OsmAnd, Magic Earth.",
          "✅ Multimedia: Spotify, YouTube, YouTube Music, Netflix (preferir horizontal).",
          "✅ Comunicación: Telegram (modo lectura), WhatsApp (notificaciones).",
          "✅ Sistema: cámara, tiempo, calendario.",
          "⚠️ Apps con DRM Widevine L1 (Disney+, Prime Video) pueden negarse a renderizar en VirtualDisplay — limitación Android, no de DashCast.",
        ],
      },
      {
        question: '❓ Actualizaciones: estable o alfa',
        answer:
          "El canal estable (por defecto) se prueba en vehículo durante al menos 1 semana. El alfa (Ajustes → Actualizaciones) recibe builds en cuanto se compilan — útil para probar antes, pero puede traer regresiones temporales.",
      },
    ],
  },

  footer:
    'DashCast es un proyecto de código abierto bajo licencia GPL-3.0. Sin afiliación con BYD Auto Co., Ltd.',
};
