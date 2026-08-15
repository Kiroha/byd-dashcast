export default {
  code: 'es',
  flag: '🇪🇸',
  name: 'Español',
  title: 'DashCast — Manual de usuario',
  manualName: 'Manual de usuario',
  meta: 'v1.7.0 · BYD Seal / Dolphin / Atto 3 · DiLink 3 & DiLink 5 · Android 10–13',
  tocTitle: '📋 Índice',

  intro: {
    title: '0. Introducción',
    lead:
      'DashCast muestra cualquier aplicación Android de la pantalla central de tu BYD en el cuadro de instrumentos (la pantalla digital de indicadores detrás del volante). Maps, Waze, Spotify o ABRP justo delante de ti — y con el modo Layouts, varias apps a la vez, cada una en su zona. Se instala como una app normal y no cambia nada en el sistema.',
    bullets: [
      '✅ Compatible con DiLink 3 (Seal EU / 6125F) y DiLink 5 (unidades BYD más recientes).',
      '✅ Sin modificación del sistema: DashCast se instala como cualquier otra app.',
      '✅ ADB local por TCP — sin ordenador tras la primera autorización.',
      '✅ 13 idiomas de interfaz, elegidos en el primer arranque, modificables en cualquier momento.',
      '✅ Espejo táctil en tiempo real: controla el cluster desde la pantalla central.',
      '✅ Modo Layouts: varias apps lado a lado en el cluster, zonas dibujadas con el dedo.',
      '✅ Arranque automático: proyección + app (o layout favorito) en cuanto se abre DashCast.',
      '✅ Márgenes (overscan) memorizados por aplicación.',
      '✅ Flechas de navegación giro a giro en el HUD del parabrisas DiLink 3 (firmware compatible).',
      '✅ Asistente de punto de acceso Wi-Fi integrado para DiLink 3 (usa tu propia SIM).',
      '✅ Reporte de errores sin teclado, un toque para enviar diagnósticos al soporte.',
      '✅ Actualizaciones OTA automáticas que se instalan en silencio y relanzan la app.',
    ],
    note:
      '💡 Único requisito: activar la depuración ADB inalámbrica en Ajustes BYD → Opciones de desarrollador. En el primer arranque aparece un diálogo «¿Permitir la depuración?» — marca «Permitir siempre desde este ordenador» y confirma. Nunca tendrás que volver a hacerlo.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Pantalla de bienvenida — elección del idioma',
      lead:
        'En el primer arranque, DashCast muestra una cuadrícula con los 13 idiomas disponibles. Toca el tuyo: la elección se memoriza y la pantalla no vuelve a aparecer. Puedes cambiar el idioma en cualquier momento en Ajustes.',
      mockupLabel: 'Ver pantalla 1 (Bienvenida)',
      featuresTitle: 'Detalles',
      features: [
        {
          title: '13 idiomas soportados',
          text:
            "Français, English, Deutsch, Italiano, Türkçe, Español, Polski, Русский, Українська, العربية, O'zbekcha, Қазақша, Беларуская. El idioma elegido se aplica al instante, sin reiniciar.",
        },
        {
          title: 'Sentido de lectura automático',
          text:
            'El árabe pasa automáticamente a diseño derecha-a-izquierda (RTL): la barra de navegación se mueve a la derecha y las listas se invierten.',
        },
        {
          title: 'Modificable en cualquier momento',
          text: 'Para cambiar el idioma más tarde: Ajustes → Idioma. Se aplica al vuelo.',
        },
      ],
      howTo: {
        title: 'Cómo hacerlo',
        steps: [
          'Lanza DashCast (icono azul en el cajón de apps BYD).',
          'Aparece la pantalla de bienvenida con la cuadrícula de idiomas.',
          'Toca tu idioma. La interfaz cambia inmediatamente.',
          'Se abre la pantalla principal — listo.',
        ],
      },
    },

    {
      id: 'main',
      screen: 'screen-2',
      title: '2. Pantalla principal — Apps & Cluster',
      lead:
        'La pantalla de inicio de DashCast. A la izquierda: todas tus aplicaciones con búsqueda, filtros y favoritos, además de la barra de navegación lateral. A la derecha: la vista previa del cluster en tiempo real, los botones Espejo a pantalla completa / Detener proyección, y — en modo Layouts — el selector plegable «Layout del cluster».',
      mockupLabel: 'Ver pantalla 2 (Principal)',
      featuresTitle: 'Todo lo que puedes hacer',
      features: [
        {
          title: '👆 Toque corto — proyectar',
          text:
            'Toca una app para enviarla al cluster. Si la proyección no está activa, arranca automáticamente (~2 s de calentamiento) y la app aparece tras el volante.',
        },
        {
          title: '👆⏱️ Pulsación larga — menú de acciones',
          text:
            'Mantén pulsada una app: ⭐ Favorito, Auto-launch (proyectar esta app en cada arranque de DashCast), Mover al cluster / pantalla principal, ✕ Forzar detención.',
        },
        {
          title: '🔍 Búsqueda y filtros',
          text:
            'La barra de búsqueda filtra al escribir (nombre o paquete). Los chips por categoría (Todas / Navegación / Multimedia…) agrupan tus apps; el botón ▦ alterna lista/cuadrícula.',
        },
        {
          title: '🚦 Vista previa del cluster en tiempo real',
          text:
            'El panel derecho refleja en directo el cluster. Tus toques en la vista previa se transmiten a la app proyectada — scroll, zoom, teclado, todo funciona.',
        },
        {
          title: '👁️ Espejo a pantalla completa',
          text:
            'Amplía la vista previa a toda la pantalla central: ideal para escribir una dirección en Maps con el teclado completo. Todo se replica en el cluster en tiempo real.',
        },
        {
          title: '⏹ Detener proyección',
          text:
            'Termina la proyección limpiamente y restaura el cuadro BYD original (velocidad, indicadores, ADAS) con el tamaño definido en Ajustes.',
        },
        {
          title: '🗂️ Selector de layout del cluster (plegado por defecto)',
          text:
            'En modo Layouts, un encabezado compacto «LAYOUT DEL CLUSTER» aparece bajo los botones. Tócalo para desplegarlo: «Lanzar las apps del layout», más una tarjeta por cada layout guardado (Modo libre / tus preajustes / ＋ Gestionar). Está plegado por defecto para que la vista previa en directo conserve toda su altura.',
        },
        {
          title: '📺 Botón flotante',
          text:
            'Un botón 📺 persiste sobre las demás apps: toque = abrir el espejo, pulsación larga = cambio rápido entre las últimas apps proyectadas.',
        },
        {
          title: '🧭 Barra de navegación lateral',
          text:
            'Acceso rápido a Apps, Ajustes, Sistema, Registro, el reporte de errores y — en DiLink 3 con tu propia SIM — el asistente de Hotspot.',
        },
      ],
      howTo: {
        title: 'Cómo proyectar una app en el cluster',
        steps: [
          'Encuentra la app deseada (ej. Maps) — búsqueda o filtros si hace falta.',
          'Toca su icono → la proyección arranca y el cluster cambia a la app en ~2 s.',
          'La vista previa derecha muestra en directo lo que hay en el cluster.',
          'Para escribir texto: «Espejo a pantalla completa» → escribe tu dirección → todo se replica.',
          'Para detener: «Detener proyección» — el cluster vuelve a BYD nativo.',
        ],
      },
      tipsTitle: 'Consejos',
      tips: [
        '💡 Auto-launch: elige una app (pulsación larga → Auto-launch) para que se proyecte automáticamente en cada arranque de DashCast — la proyección se activa sola.',
        '💡 Layout favorito: la tarjeta seleccionada en el selector «Layout del cluster» es la que activará el arranque automático (ver la sección Layouts).',
        '💡 Márgenes: si la app desborda el cluster, Ajustes → Márgenes, sliders horizontal/vertical. Memorizado por app.',
      ],
    },

    {
      id: 'settings',
      screen: 'screen-3',
      title: '3. Ajustes',
      lead:
        'Las opciones globales: tamaño del cluster, idioma, márgenes, comportamiento al arrancar, modo Layouts y actualizaciones. La barra lateral sigue disponible — cambia de pantalla sin perder tu posición.',
      mockupLabel: 'Ver pantalla 3 (Ajustes)',
      featuresTitle: 'Secciones principales',
      features: [
        {
          title: '📺 Tipo de cluster',
          text:
            'Tamaño físico de tu cuadro: 8.8″, 12.3″ (recomendado en Seal EU — corrige el estiramiento ADAS) o 10.25″. Usado por «Detener proyección» para restaurar el modo correcto.',
        },
        {
          title: '↔️↕️ Márgenes (overscan)',
          text:
            'Sliders horizontal/vertical (0–200 px) para compensar bordes recortados. Memorizado por aplicación: Maps puede tener 80 px mientras Spotify queda a 0. «Aplicar» ajusta la proyección en caliente.',
        },
        {
          title: '🚗 Arranque con el vehículo',
          text:
            'Si está activado, DashCast arranca con el coche y restaura la última app proyectada (o el layout favorito). Si no, lánzalo desde el cajón BYD.',
        },
        {
          title: '🗂️ Modo Layouts',
          text:
            'Activa la proyección multi-aplicación con zonas personalizadas (requiere el Proxy ADB Daemon, gestionado automáticamente). Hace aparecer el selector «Layout del cluster» en la pantalla principal y la pestaña Layouts.',
        },
        {
          title: '⭐ Layout favorito automático',
          text:
            'Al arrancar DashCast: activa la proyección del cluster, el layout favorito, y lanza las apps vinculadas a cada zona. Tu configuración multi-app completa, sin un solo toque.',
        },
        {
          title: '⚡ Pre-crear los slots al arrancar',
          text:
            'Prepara las pantallas virtuales del layout favorito al abrir DashCast (sin lanzar las apps) — la activación del layout es luego casi instantánea.',
        },
        {
          title: '📶 Usar mi propia SIM (DiLink 3)',
          text:
            'Controla si el asistente de Hotspot aparece en la barra de navegación. Déjalo activado si compartes internet al coche con los datos de tu propio teléfono/SIM. Ver la sección Hotspot.',
        },
        {
          title: '📦 Actualizaciones OTA',
          text:
            'DashCast comprueba GitHub en busca de nuevas versiones en cada arranque. Las actualizaciones ahora se instalan en silencio y relanzan la app por sí mismas (ver la sección Actualizaciones). Marca «Incluir pre-versiones» para el canal beta.',
        },
        {
          title: '🌐 Idioma',
          text: '13 idiomas — el cambio es instantáneo.',
        },
      ],
      howTo: {
        title: 'Cómo ajustar los márgenes de una app',
        steps: [
          'Proyecta la app a ajustar (ej. Waze).',
          'Ajustes → Márgenes.',
          'Mueve el slider horizontal hasta que los bordes izquierdo/derecho sean correctos.',
          'Lo mismo en vertical, luego «Aplicar» — ajuste en caliente, sin reiniciar la app.',
          'El ajuste se guarda solo para esta app.',
        ],
      },
      note:
        '⚠️ Si cambias el tipo de cluster, detén y relanza la proyección para que la restauración use el modo correcto.',
    },

    {
      id: 'layouts',
      screen: 'screen-7',
      title: '4. Layouts — varias apps en el cluster',
      lead:
        'El modo Layouts divide el cluster en zonas personalizadas, cada una con su propia aplicación: Waze a la izquierda, Spotify a la derecha, por ejemplo. Dibujas las zonas con el dedo, vinculas una app a cada zona, y el layout se activa con un toque — o solo al arrancar.',
      mockupLabel: 'Ver pantalla 7 (Layouts)',
      featuresTitle: 'Funciones',
      features: [
        {
          title: '✏️ Dibujar una zona',
          text:
            'En el lienzo (una réplica del cluster), desliza el dedo para trazar un rectángulo. Se abre un diálogo: nombre, posición/dimensiones al píxel, y la app a vincular.',
        },
        {
          title: '🔗 Vincular una aplicación',
          text:
            'Cada zona puede vincularse a una app: al activar el layout, la app se lanza automáticamente en su zona. Una zona sin app queda libre — coloca cualquier cosa ahí más tarde.',
        },
        {
          title: '✋ Mover y redimensionar',
          text:
            'Arrastra una zona para moverla; las asas blancas de las esquinas redimensionan. Los bordes se imantan automáticamente a los límites del cluster y a las zonas vecinas.',
        },
        {
          title: '✏️ Modificar una zona existente',
          text:
            'Toca una zona (en el lienzo o su chip abajo): renómbrala, ajusta su geometría, cambia la app vinculada o elimínala. Pulsación larga en una zona = eliminación rápida.',
        },
        {
          title: '💾 Layouts guardados',
          text:
            'Guarda tantos layouts como quieras («Nav+Media», «Triple pantalla»…). El panel lateral los lista con Activar / Desactivar / Modificar / Eliminar.',
        },
        {
          title: '⭐ Favorito y arranque automático',
          text:
            'El botón «Favorito» (o un toque en la tarjeta del selector de la pantalla principal) designa el layout que «Layout favorito automático» activará al arrancar DashCast — proyección incluida.',
        },
      ],
      howTo: {
        title: 'Crea tu primer layout',
        steps: [
          'Activa «Modo Layouts» en Ajustes.',
          'Abre la pestaña Layouts (barra lateral).',
          'Desliza el dedo por el lienzo para dibujar la primera zona (ej. mitad izquierda).',
          'En el diálogo: nómbrala, toca «Vincular una aplicación» → elige Waze → Añadir.',
          'Dibuja la segunda zona (mitad derecha), vincula Spotify.',
          '«Guardar» → nombra el layout (ej. Nav+Media).',
          '«Favorito» para seleccionarlo, luego actívalo: ambas apps se lanzan, cada una en su zona.',
        ],
      },
      tipsTitle: 'Consejos',
      tips: [
        '💡 Combinado con «Layout favorito automático» (Ajustes), tu configuración multi-app completa se monta sola en cada arranque de DashCast.',
        '💡 La mini-vista de cada tarjeta del selector muestra las zonas reales del layout — reconocible de un vistazo.',
        '💡 ¿Una app se niega a mostrarse en su zona? Algunas apps imponen su formato; prueba una zona más cercana al 16:9.',
      ],
      note:
        'ℹ️ El modo Layouts se apoya en el Proxy ADB Daemon (arrancado automáticamente). Primer arranque en frío: cuenta 6–8 s antes de que aparezcan las apps — es la secuencia de activación del cluster.',
    },

    {
      id: 'hud',
      screen: 'screen-2',
      title: '5. Flechas de navegación en el HUD (DiLink 3)',
      lead:
        'En los coches DiLink 3 cuyo Head-Up Display del parabrisas admite flechas de giro, DashCast puede dibujar la guía giro a giro en el HUD a partir de tu app de navegación — la flecha de la maniobra y la distancia hasta ella, directamente en el parabrisas.',
      mockupLabel: 'Ver pantalla 2 (Principal)',
      featuresTitle: 'Cómo funciona',
      features: [
        {
          title: '🧭 Guía desde Maps / Waze',
          text:
            'DashCast lee la notificación giro a giro que tu app de navegación ya publica (Google Maps, Waze) y reenvía la maniobra + la distancia al HUD a través del bus CAN del coche. No hace falta ninguna app adicional.',
        },
        {
          title: '🚗 Depende del firmware',
          text:
            'Solo los firmwares de HUD DiLink 3 más recientes pueden dibujar flechas. Si el tuyo no puede, las flechas simplemente no aparecerán — DashCast no puede añadir una capacidad que el hardware del HUD no tiene.',
        },
        {
          title: '➡️ Glifos de dirección correctos',
          text:
            'Las maniobras recto / izquierda / derecha se corresponden con el glifo del HUD adecuado, con la distancia en cuenta atrás en directo hasta el giro.',
        },
      ],
      howTo: {
        title: 'Cómo obtener flechas en el HUD',
        steps: [
          'Asegúrate de conceder a DashCast el acceso a las notificaciones (lo pide en el primer uso).',
          'Enciende el HUD del parabrisas y ponlo en un modo de visualización de navegación en el menú HUD de BYD.',
          'Inicia una ruta en Google Maps o Waze.',
          'La flecha de la maniobra y la distancia aparecen en el HUD a medida que te acercas a cada giro.',
        ],
      },
      note:
        'ℹ️ Las flechas del HUD son una función de DiLink 3 y dependen del firmware de tu HUD. Si no aparece nada, puede que tu HUD sea anterior a la compatibilidad con flechas — es un límite de hardware, no un fallo de DashCast.',
    },

    {
      id: 'hotspot',
      screen: 'screen-3',
      title: '6. Asistente de punto de acceso Wi-Fi (DiLink 3)',
      lead:
        'En DiLink 3, si conectas el coche a internet a través de tu propia SIM/teléfono, el asistente de Hotspot mantiene viva esa conexión compartida para que la navegación y el streaming sigan funcionando. Aparece en la barra de navegación solo cuando es relevante para ti.',
      mockupLabel: 'Ver pantalla 3 (Ajustes)',
      featuresTitle: 'Funciones',
      features: [
        {
          title: '📶 Mantener activo',
          text:
            'Rearma la conexión compartida Wi-Fi cuando el coche se despierta (por ejemplo, tras encender el ACC) para que no tengas que reactivarla manualmente en cada trayecto.',
        },
        {
          title: '👁️ Estado en directo',
          text:
            'Muestra si el punto de acceso está activo y cuántos clientes están conectados, para que puedas confirmar que el coche está realmente en línea.',
        },
        {
          title: '⚙️ Se muestra solo cuando es útil',
          text:
            'La entrada Hotspot aparece solo en DiLink 3 y solo mientras «Usar mi propia SIM» esté activado en Ajustes. En otras configuraciones permanece oculta.',
        },
      ],
      howTo: {
        title: 'Cómo usarlo',
        steps: [
          'Ajustes → asegúrate de que «Usar mi propia SIM» esté activado.',
          'Abre «Hotspot» desde la barra de navegación.',
          'Inicia / confirma la conexión compartida — el estado muestra que está activa.',
          'Se rearma sola la próxima vez que el coche se despierte.',
        ],
      },
      note:
        'ℹ️ Este asistente es para coches DiLink 3 con conexión compartida a través de tus propios datos. Si tu coche tiene su propio plan de datos integrado, no lo necesitas.',
    },

    {
      id: 'bugreport',
      screen: 'screen-6',
      title: '7. Reportar un problema (reporte de errores)',
      lead:
        'Un reporte de errores sin teclado, dentro del coche. En tres toques eliges qué falló; DashCast captura una instantánea de diagnóstico acotada (registros + estado del sistema) y la envía directamente al canal de soporte — sin escribir, sin cables.',
      mockupLabel: 'Ver pantalla 6 (Reporte)',
      featuresTitle: 'Cómo funciona',
      features: [
        {
          title: '1️⃣ Categoría',
          text:
            'Elige qué área está afectada: espejo, una app, sonido, conexión, bloqueo, HUD… Seis mosaicos grandes, solo tocar.',
        },
        {
          title: '2️⃣ App',
          text:
            'DashCast detecta automáticamente la app que hay en el cluster y la ofrece, además de «Ninguna app específica» y «Otra».',
        },
        {
          title: '3️⃣ Problema',
          text:
            'Elige el síntoma más parecido de una lista corta. Un campo de texto libre opcional te permite añadir detalles si quieres — pero nunca es obligatorio.',
        },
        {
          title: '📎 Diagnóstico automático',
          text:
            'El reporte agrupa los registros recientes y el estado del sistema/cluster en el momento del problema — exactamente lo que el soporte necesita, capturado por ti.',
        },
        {
          title: '🚀 Envío con un toque',
          text:
            'Si hay un canal de soporte configurado, el reporte se sube directamente; si no, DashCast abre el menú de compartir de Android para que puedas enviarlo por Telegram, correo electrónico o GitHub.',
        },
        {
          title: '📺 Desde cualquier sitio',
          text:
            'Tanto el botón flotante 📺 como la barra de navegación abren el reporte, así puedes enviar un informe incluso mientras otra app está proyectada.',
        },
      ],
      howTo: {
        title: 'Cómo enviar un reporte',
        steps: [
          'Abre el reporte de errores (barra de navegación o el botón flotante).',
          'Toca la categoría que corresponde al problema.',
          'Confirma la app (o elige «Ninguna app específica»).',
          'Elige el problema más parecido; añade una nota si es útil.',
          'Toca Enviar — el diagnóstico va al soporte automáticamente.',
        ],
      },
      note:
        '🔒 Antes de enviarlo, DashCast elimina el número de bastidor, los nombres de redes wifi, las direcciones físicas y las posiciones. Quedan el registro de DashCast y el del sistema Android, copiados tal cual: contienen lo que hayan escrito otras aplicaciones. Se te pregunta una vez antes de enviar nada, y si te niegas nada sale del coche.',
    },

    {
      id: 'system',
      screen: 'screen-5',
      title: '8. Informe del sistema',
      lead:
        'Panel de solo lectura: versiones, pantallas detectadas y estado en directo de los servicios DashCast. La primera pantalla a consultar cuando algo parece anómalo.',
      mockupLabel: 'Ver pantalla 5 (Sistema)',
      featuresTitle: 'Información mostrada',
      features: [
        {
          title: '🖥️ Pantallas',
          text:
            'Pantalla principal (resolución, densidad) y la pantalla virtual del cluster con su estado en tiempo real.',
        },
        {
          title: '⚙️ Servicios',
          text:
            'ClusterService (proyección), MirrorDaemon (espejo), Proxy ADB Daemon (operaciones privilegiadas), AdbLocalClient (túnel ADB) — cada uno con punto verde/rojo y botón de reinicio si está detenido.',
        },
        {
          title: '📱 Versiones',
          text:
            'Versión DashCast instalada, firmware BYD, versión Android/API, identificadores de build DiLink.',
        },
        {
          title: '🔁 Replay de proyección',
          text:
            'Botón para repetir la secuencia completa de activación del cluster (útil si el cuadro quedó en un estado intermedio).',
        },
      ],
      tipsTitle: 'Consejos',
      tips: [
        '💡 «Proxy ADB Daemon» debe estar en verde (RUN) para el modo Layouts — si no, toca su línea para relanzarlo.',
        '💡 Si algo parece anómalo, comprueba esta pantalla primero y luego envía un reporte desde el reporte de errores.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '9. Registro',
      lead:
        'El registro interno de DashCast: todas las acciones importantes (proyecciones, restauraciones, errores ADB, actualizaciones) quedan trazadas de forma continua. Útil para entender un comportamiento inesperado; es también lo que el reporte de errores adjunta por ti.',
      mockupLabel: 'Ver pantalla 6 (Registro)',
      featuresTitle: 'Funciones',
      features: [
        {
          title: '🔍 Filtros',
          text:
            'Filtra por nivel (DEBUG / INFO / WARN / ERROR) o por palabra clave (ej. «ADB», «Maps», «error»).',
        },
        {
          title: '🎨 Código de colores',
          text:
            '🟢 INFO — operación normal. 🟠 WARN — atención. 🔴 ERROR — fallo. ⚪ DEBUG — detalle técnico.',
        },
        {
          title: '📤 Compartir',
          text:
            'Exporta el registro en .txt y abre el menú de compartir de Android. Incluye la versión DashCast y el modelo BYD.',
        },
        {
          title: '⏰ Marcas de tiempo',
          text:
            'Cada línea lleva la hora local (HH:mm:ss.mmm); las operaciones largas se miden.',
        },
      ],
      howTo: {
        title: 'Prefiere el reporte de errores',
        steps: [
          'Para la mayoría de problemas, usa el reporte de errores (sección 7) — captura el registro más el estado del sistema automáticamente.',
          'La pantalla Registro está aquí para cuando quieras leer la traza tú mismo o compartir solo el registro sin procesar.',
        ],
      },
      note:
        '🔒 El registro anota lo que hace DashCast, incluidos nombres de paquetes y la salida de los comandos que ejecuta. Compartirlo desde esta pantalla lo envía tal cual: el filtrado que elimina el número de bastidor, los nombres de redes y las posiciones se aplica a los informes de error, no aquí.',
    },
  ],

  faq: {
    title: '10. FAQ — Preguntas frecuentes',
    items: [
      {
        question: '❓ El cluster queda negro cuando toco una app',
        answer:
          'Tres causas posibles: (1) ADB inalámbrico desactivado — comprueba Ajustes BYD → Opciones de desarrollador. (2) Un servicio detenido — pantalla Sistema, reinicia la línea roja. (3) La app acaba de fallar — vuelve a tocar su icono. ¿Sigue bloqueado? Envía un reporte desde el reporte de errores.',
      },
      {
        question: '❓ La imagen desborda / está recortada en el cluster',
        answer:
          'Ajustes → Márgenes: ajusta los sliders horizontal/vertical hasta que los bordes sean correctos. Memorizado por app — solo lo harás una vez.',
      },
      {
        question: '❓ ¿Cómo vuelvo al cuadro BYD original?',
        answer:
          'Toca «Detener proyección» en la pantalla principal: DashCast restaura el cluster nativo con el tamaño definido en Ajustes. Si el cuadro parece bloqueado, pantalla Sistema → «Replay de proyección», luego detén de nuevo.',
      },
      {
        question: '❓ Mi layout favorito no se lanza al arrancar',
        answer:
          'Comprueba las tres condiciones en Ajustes: «Modo Layouts» activado, «Layout favorito automático» activado, y un layout marcado ⭐ favorito (selector de la pantalla principal o el botón Favorito de la pestaña Layouts). En arranque en frío, cuenta 6–8 s.',
      },
      {
        question: '❓ No aparecen flechas de navegación en mi HUD',
        answer:
          'Las flechas del HUD son una función de DiLink 3 y necesitan un firmware de HUD capaz de dibujarlas. Asegúrate de que el acceso a las notificaciones esté concedido, de que el HUD esté encendido en un modo de visualización de navegación, y de que haya una ruta en marcha en Maps/Waze. Si no aparece nada, es probable que el firmware de tu HUD sea anterior a la compatibilidad con flechas — un límite de hardware, no un fallo.',
      },
      {
        question: '❓ ¿Necesito el asistente de Hotspot?',
        answer:
          'Solo en DiLink 3 si conectas el coche a internet mediante la conexión compartida de tu propia SIM/teléfono. Mantiene viva esa conexión a lo largo de los despertares del coche. Si tu coche tiene su propio plan de datos, ignóralo — permanece oculto salvo que «Usar mi propia SIM» esté activado.',
      },
      {
        question: '❓ ¿Cómo se instalan ahora las actualizaciones?',
        answer:
          'DashCast comprueba GitHub en cada arranque. Cuando se descarga una actualización, se instala en silencio y relanza la app por sí misma — sin el aviso «¿Instalar?». En un coche donde eso no sea posible, recurre al instalador normal del sistema. La primera actualización después de pasar a una versión con esta función puede que aún pregunte una vez.',
      },
      {
        question: '❓ ¿DashCast agota la batería de 12 V?',
        answer:
          'No — DashCast se detiene con el coche. Ningún servicio de fondo queda activo con el motor apagado.',
      },
      {
        question: '❓ ¿Qué apps funcionan en el cluster?',
        items: [
          '✅ Navegación: Google Maps, Waze, Yandex Navi, OsmAnd, ABRP, Magic Earth.',
          '✅ Multimedia: Spotify, YouTube, YouTube Music (preferir horizontal).',
          '✅ Sistema: cámara, tiempo, agenda.',
          '⚠️ Apps con DRM (Netflix, Disney+, Prime Video): pueden negarse a mostrarse en una pantalla virtual — limitación de Android, no de DashCast.',
        ],
      },
      {
        question: '❓ Actualizaciones: ¿estable o beta?',
        answer:
          'El canal estable (por defecto) se prueba en vehículo antes de publicarse. El canal beta (Ajustes → Actualizaciones → «Incluir pre-versiones») recibe las novedades en cuanto se compilan — útil para probar antes, con riesgo de regresiones temporales.',
      },
      {
        question: '❓ Quiero contribuir o reportar un bug',
        answer:
          'Usa el reporte de errores integrado en la app para el camino más rápido (adjunta el diagnóstico por ti). Para código y solicitudes de funciones: GitHub https://github.com/Kiroha/byd-dashcast — Issues para bugs, Discussions para preguntas.',
      },
    ],
  },

  footer:
    'DashCast es un proyecto open-source distribuido bajo licencia MIT. Sin afiliación con BYD Auto Co., Ltd.',
};
