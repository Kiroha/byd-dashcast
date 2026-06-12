export default {
  code: 'es',
  flag: '🇪🇸',
  name: 'Español',
  title: 'DashCast — Manual de usuario',
  manualName: 'Manual de usuario',
  meta: 'v1.4.x · BYD Seal EU · DiLink 3.0 · Android 10',
  tocTitle: '📋 Índice',

  intro: {
    title: '0. Introducción',
    lead:
      'DashCast muestra cualquier aplicación Android de la pantalla central de tu BYD en el cuadro de instrumentos (cluster digital). Maps, Waze, Spotify o ABRP justo detrás del volante — y con el modo Layouts, varias apps a la vez, cada una en su zona. Todo sin modificar el sistema.',
    bullets: [
      '✅ Compatible con BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F).',
      '✅ Sin modificación del sistema: DashCast se instala como una app normal.',
      '✅ ADB local por TCP — sin ordenador tras la primera autorización.',
      '✅ 13 idiomas de interfaz, elegidos en el primer arranque.',
      '✅ Espejo táctil en tiempo real: controla el cluster desde la pantalla central.',
      '✅ Modo Layouts: varias apps lado a lado en el cluster, zonas dibujadas con el dedo.',
      '✅ Arranque automático: proyección + app (o layout favorito) al abrir DashCast.',
      '✅ Márgenes (overscan) memorizados por aplicación.',
      '✅ Actualizaciones OTA integradas (canal beta opcional).',
    ],
    note:
      '💡 Único requisito: activar la depuración ADB inalámbrica en Ajustes BYD → Desarrollador. En el primer arranque aparece el diálogo «¿Permitir depuración?» — marca «Permitir siempre» y confirma. Nunca tendrás que repetirlo.',
  },

  sections: [
    {
      id: 'welcome',
      screen: 'screen-1',
      title: '1. Pantalla de bienvenida — elección del idioma',
      lead:
        'En el primer arranque, DashCast muestra la cuadrícula con los 13 idiomas disponibles. Toca el tuyo: la elección se memoriza y la pantalla no vuelve a aparecer. Puedes cambiar el idioma en cualquier momento en Ajustes.',
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
        'La pantalla central de DashCast. A la izquierda: todas tus aplicaciones con búsqueda, filtros y favoritos. A la derecha: la vista previa del cluster en tiempo real, los botones Espejo a pantalla completa / Detener proyección, y el carrusel de layouts para elegir tu disposición multi-app favorita.',
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
          title: '🗂️ Carrusel de layouts',
          text:
            'Bajo los botones, cada tarjeta muestra una mini-vista de las zonas de un layout. Toca una tarjeta para hacerla layout favorito (estrella + borde azul). «Modo libre» desactiva los layouts; «＋ Gestionar» abre el editor.',
        },
        {
          title: '📺 Botón flotante',
          text:
            'Un botón 📺 persiste sobre las demás apps: toque = abrir el espejo, pulsación larga = cambio rápido entre las últimas apps proyectadas.',
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
        '💡 Layout favorito: la tarjeta seleccionada en el carrusel es la que activará el arranque automático (ver sección Layouts).',
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
            'Si está activado, DashCast arranca con el coche y restaura la última app proyectada. Si no, lánzalo desde el cajón BYD.',
        },
        {
          title: '🗂️ Modo Layouts',
          text:
            'Activa la proyección multi-aplicación con zonas personalizadas (requiere el Proxy ADB Daemon, gestionado automáticamente). Hace aparecer el carrusel en la pantalla principal y la pestaña Layouts.',
        },
        {
          title: '⭐ Layout favorito automático',
          text:
            'Al arrancar DashCast: activa la proyección del cluster, el layout favorito, y lanza las apps vinculadas a cada zona. Tu configuración multi-app completa, sin un solo toque.',
        },
        {
          title: '⚡ Pre-crear los slots al arrancar',
          text:
            'Prepara las pantallas virtuales del layout favorito al abrir (sin lanzar las apps) — la activación del layout es luego casi instantánea.',
        },
        {
          title: '📦 Actualizaciones OTA',
          text:
            'DashCast comprueba GitHub en cada arranque. Marca «Incluir pre-versiones» para el canal beta (novedades antes, menos estabilidad).',
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
            'En el lienzo (réplica del cluster 1920×720), desliza el dedo para trazar un rectángulo. Se abre un diálogo: nombre, posición/dimensiones al píxel, y la app a vincular.',
        },
        {
          title: '🔗 Vincular una aplicación',
          text:
            'Cada zona puede vincularse a una app: al activar el layout, la app se lanza automáticamente en su zona. Una zona sin app queda libre.',
        },
        {
          title: '✋ Mover y redimensionar',
          text:
            'Arrastra una zona para moverla; las asas blancas de las esquinas redimensionan. Los bordes se imantan automáticamente a los límites del cluster y a las zonas vecinas.',
        },
        {
          title: '✏️ Modificar una zona existente',
          text:
            'Toca una zona (en el lienzo o su chip abajo): renómbrala, ajusta su geometría, cambia la app vinculada o elimínala. Pulsación larga = eliminación rápida.',
        },
        {
          title: '💾 Layouts guardados',
          text:
            'Guarda tantos layouts como quieras («Nav+Media», «Triple pantalla»…). El panel lateral los lista con Activar / Desactivar / Modificar / Eliminar.',
        },
        {
          title: '⭐ Favorito y arranque automático',
          text:
            'El botón «Favorito» (o un toque en la tarjeta del carrusel de la pantalla principal) designa el layout que «Layout favorito automático» activará al arrancar DashCast — proyección incluida.',
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
        '💡 La mini-vista de cada tarjeta del carrusel muestra las zonas reales del layout — reconocible de un vistazo.',
        '💡 ¿Una app se niega a mostrarse en su zona? Algunas apps imponen su formato; prueba una zona más cercana al 16:9.',
      ],
      note:
        'ℹ️ El modo Layouts se apoya en el Proxy ADB Daemon (arrancado automáticamente). Primer arranque en frío: cuenta 6–8 s antes de que aparezcan las apps — es la secuencia de activación del cluster.',
    },

    {
      id: 'diagnostics',
      screen: 'screen-4',
      title: '5. Diagnóstico',
      lead:
        'Panel interno para las situaciones en las que la proyección no funciona como se espera. La mayoría de usuarios nunca lo necesitará — existe para soporte y depuración.',
      mockupLabel: 'Ver pantalla 4 (Diagnóstico)',
      featuresTitle: 'Herramientas disponibles',
      features: [
        {
          title: 'Tests de conexión',
          text:
            'Comprueba el túnel ADB local (localhost:5555), el estado del ClusterService y la presencia de la pantalla virtual del cluster.',
        },
        {
          title: 'Sondas de plataforma',
          text:
            'Detección DiLink (2/3/4/5), inventario de pantallas, instanciación de las API del vehículo BYD (velocidad, energía) y estado de los permisos BYDAUTO.',
        },
        {
          title: 'Informe compartible',
          text:
            'Genera un informe completo (sistema, pantallas, servicios, permisos, métricas del daemon) exportable como texto para el soporte.',
        },
      ],
      howTo: {
        title: 'Cuándo usar esta pestaña',
        steps: [
          'El cluster queda negro tras tocar una app → comprueba ClusterService y la pantalla virtual.',
          'La app indica «ADB no disponible» → botón «Probar ADB».',
          'El soporte te pide un informe → genéralo y compártelo.',
        ],
      },
      note: 'ℹ️ Los botones son tests de solo lectura, salvo indicación explícita.',
    },

    {
      id: 'sysinfo',
      screen: 'screen-5',
      title: '6. Informe del sistema',
      lead:
        'Panel de solo lectura: versiones, pantallas detectadas y estado en directo de los servicios DashCast. La primera pantalla a consultar cuando algo parece anómalo.',
      mockupLabel: 'Ver pantalla 5 (Sistema)',
      featuresTitle: 'Información mostrada',
      features: [
        {
          title: '🖥️ Pantallas',
          text:
            'Pantalla principal (resolución, densidad) y pantalla virtual del cluster (1920×720) con su estado en tiempo real.',
        },
        {
          title: '⚙️ Servicios',
          text:
            'ClusterService (proyección), MirrorDaemon (espejo), Proxy ADB Daemon (operaciones privilegiadas), AdbLocalClient (túnel ADB) — cada uno con punto verde/rojo y botón de relance si está detenido.',
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
        '💡 El informe completo es exportable desde esta pantalla para acompañar un reporte de bug.',
      ],
    },

    {
      id: 'journal',
      screen: 'screen-6',
      title: '7. Registro',
      lead:
        'El registro interno de DashCast: todas las acciones importantes (proyecciones, restauraciones, errores ADB, actualizaciones) quedan trazadas en continuo. Útil para entender un comportamiento inesperado o enviar un informe al soporte.',
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
        title: 'Enviar un informe de bug',
        steps: [
          'Reproduce el problema.',
          'Abre Registro → «Compartir».',
          'Elige tu canal (Telegram, e-mail, GitHub Issues).',
          'El archivo adjunto contiene la traza y el contexto (versión, modelo, firmware).',
        ],
      },
      note:
        '🔒 No se registra ningún dato personal (contactos, posición GPS, contenido de apps) — solo las acciones DashCast y los códigos de retorno técnicos.',
    },
  ],

  faq: {
    title: '8. FAQ — Preguntas frecuentes',
    items: [
      {
        question: '❓ El cluster queda negro cuando toco una app',
        answer:
          'Tres causas posibles: (1) ADB inalámbrico desactivado — comprueba Ajustes BYD → Desarrollador. (2) Un servicio detenido — pestaña Sistema, relanza la línea roja. (3) La app acaba de fallar — vuelve a tocar su icono.',
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
          'Comprueba las tres condiciones en Ajustes: «Modo Layouts» activado, «Layout favorito automático» activado, y un layout marcado ⭐ favorito (carrusel de la pantalla principal o botón Favorito de la pestaña Layouts). En arranque en frío, cuenta 6–8 s.',
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
          'GitHub: https://github.com/Kiroha/byd-dashcast — Issues para bugs, Discussions para preguntas. Adjunta una exportación del Registro para acelerar el diagnóstico.',
      },
    ],
  },

  footer:
    'DashCast es un proyecto open-source distribuido bajo licencia MIT. Sin afiliación con BYD Auto Co., Ltd.',
};
