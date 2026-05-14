export default {
  "code": "es",
  "flag": "🇪🇸",
  "name": "Español",
  "title": "DashCast — Manual de usuario",
  "meta": "v0.7.2 · BYD Seal EU · DiLink 3.0 · Android 10",
  "manualName": "Manual de usuario",
  "tocTitle": "📋 Índice",
  "sections": [
    "1. Descripción general",
    "2. Primer inicio — Selección de idioma",
    "3. Pantalla principal",
    "4. Proyectar una app al cuadro",
    "5. Durante la proyección — Panel de control",
    "6. Detener la proyección",
    "7. Ajustes",
    "8. Menú ⋮ — Herramientas adicionales",
    "9. FAQ y resolución de problemas"
  ],
  "overview": {
    "title": "1. Descripción general",
    "text": "DashCast es una aplicación Android que permite proyectar cualquier app desde la pantalla de infoentretenimiento al cuadro de instrumentos digital de tu BYD. Navegación, música, vídeos: todo lo que se ejecuta en la pantalla central puede redirigirse al cuadro.",
    "bullets": [
      "✅ Compatible con BYD Seal EU (DiLink 3.0, firmware Di3.0 / 6125F)",
      "✅ No requiere modificación del sistema",
      "✅ ADB local (TCP, localhost): sin ordenador una vez configurado",
      "✅ Detección automática al cerrar aplicaciones",
      "✅ Actualizaciones OTA integradas (Over-The-Air)",
      "✅ Overscan guardado de forma independiente por aplicación",
      "✅ Modo de visualización en cuadrícula o lista",
      "✅ Cierre forzado de emergencia (Cruz roja) por aplicación"
    ],
    "note": "💡 Requisito previo: activa la depuración ADB por TCP en Ajustes → Desarrollador → Depuración inalámbrica. Es un paso único. En el primer inicio aparecerá la ventana emergente '¿Permitir depuración?': toca Permitir siempre desde este dispositivo."
  },
  "firstLaunch": {
    "title": "2. Primer inicio — Selección de idioma",
    "text": "En el primer inicio aparece la pantalla de bienvenida. Elige tu idioma tocando uno de los botones disponibles. Esta elección se guarda: no volverás a ver esta pantalla salvo que cambies el idioma desde el menú ⋮.",
    "welcomeSubtitle": "Controlador del cuadro",
    "welcomeHint": "Elige tu idioma\nPlease select your language",
    "caption": "Pantalla de selección de idioma: solo aparece en el primer inicio"
  },
  "main": {
    "title": "3. Pantalla principal",
    "text": "La pantalla principal tiene dos zonas: una barra de estado arriba (fondo azul oscuro) y la lista de apps instaladas debajo. Puedes cambiar entre vista de lista y cuadrícula (iconos) desde el menú ⋮.",
    "status": "① Cuadro: no conectado",
    "buttons": [
      "② Activar proyección",
      "③ Detener proyección",
      "④ Restaurar cuadro original",
      "⑤ ⋮",
      "✕",
      "✕",
      "✕",
      "✕"
    ],
    "listTitle": "⑥ Apps instaladas",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify",
      "Waze"
    ],
    "caption": "Pantalla principal: sin aplicación proyectada (estado inicial)",
    "annotations": [
      {
        "tone": "",
        "marker": "①",
        "label": "Estado",
        "text": "Muestra el estado de conexión con el cuadro. Cambia a 'Cuadro: [NombreApp]' cuando una app está activa."
      },
      {
        "tone": "",
        "marker": "②",
        "label": "Activar proyección",
        "text": "Establece la conexión con el cuadro y prepara el envío de apps. Pulsa este botón primero."
      },
      {
        "tone": "red",
        "marker": "③",
        "label": "Detener proyección",
        "text": "Detiene la proyección en curso sin restaurar el cuadro BYD original."
      },
      {
        "tone": "green",
        "marker": "④",
        "label": "Restaurar cuadro original",
        "text": "Detiene la proyección Y restaura el cuadro BYD nativo (velocidad, indicadores…)."
      },
      {
        "tone": "gray",
        "marker": "⑤",
        "label": "Menú ⋮",
        "text": "Acceso a Ajustes, Diagnóstico, Informe del sistema, Registros, cambio de idioma y alternancia Lista/Cuadrícula."
      },
      {
        "tone": "gray",
        "marker": "⑥",
        "label": "Lista de apps",
        "text": "Todas las apps instaladas. Toca una app para proyectarla, ✕ para cerrarla y ❌ para forzar la detención total del proceso."
      }
    ]
  },
  "projection": {
    "title": "4. Proyectar una app al cuadro",
    "steps": [
      "Pulsa 'Activar proyección' (botón azul superior). El estado cambia a 'Iniciando cuadro…'. Se establece la conexión ADB local y el cuadro entra en modo proyección.",
      "Pulsa la app deseada en la lista. DashCast mueve la app a la pantalla del cuadro. El estado cambia a 'Cuadro: [Nombre de la app]'.",
      "Aparece el panel de control en la parte inferior. Los valores de overscan guardados para esta app se aplican automáticamente."
    ],
    "activeStatus": "Cuadro: Maps ✓",
    "buttons": [
      "Activar proyección",
      "📺 Espejo",
      "Detener proyección",
      "Restaurar cuadro original",
      "⋮",
      "← Principal",
      "✕",
      "→ Cuadro",
      "✕",
      "→ Cuadro",
      "✕",
      "📐 Ajustar",
      "⬛⬛ División",
      "Ocultar ▼"
    ],
    "listTitle": "Apps instaladas",
    "apps": [
      "Maps",
      "YouTube",
      "Spotify"
    ],
    "controlLabel": "Control del cuadro",
    "controlApp": "Maps",
    "mirrorText": "Pantalla activa en el cuadro ✓",
    "caption": "Pantalla principal: Maps proyectado en el cuadro de instrumentos",
    "annotations": [
      {
        "tone": "green",
        "marker": "●",
        "label": "Barra verde",
        "text": "Indicador visual en cada elemento: la app está activa actualmente en el cuadro."
      },
      {
        "tone": "",
        "marker": "→",
        "label": "→ Cuadro",
        "text": "Envía otra app al cuadro (sustituye la actual)."
      },
      {
        "tone": "gray",
        "marker": "←",
        "label": "← Principal",
        "text": "Devuelve la app a la pantalla central (la quita del cuadro)."
      },
      {
        "tone": "teal",
        "marker": "📺",
        "label": "Espejo",
        "text": "Muestra una vista previa en vivo del contenido del cuadro dentro de DashCast."
      },
      {
        "tone": "red",
        "marker": "❌",
        "label": "Cruz roja (Forzar detención)",
        "text": "Fuerza la detención completa de una app bloqueada y la elimina de aplicaciones recientes."
      },
      {
        "tone": "gray",
        "marker": "🔲",
        "label": "Vista cuadrícula/lista",
        "text": "Alterna entre vista clásica de lista y cuadrícula de iconos desde el menú ⋮."
      }
    ]
  },
  "control": {
    "title": "5. Durante la proyección — Panel de control",
    "intro": "Cuando una app está activa en el cuadro, aparece un panel oscuro en la parte inferior de la pantalla principal con cuatro controles:",
    "mirror": {
      "title": "5.1 Espejo (📺 Espejo)",
      "text": "Pulsa 📺 Espejo en la barra de estado para mostrar una copia en vivo del cuadro dentro de DashCast. Puedes interactuar con esta copia mediante toque: los eventos se envían a la pantalla del cuadro.",
      "note": "La función espejo usa SurfaceControl para capturar la pantalla. Si no está disponible, se usa como alternativa una captura automática cada 2 segundos."
    },
    "resize": {
      "title": "5.2 Ajustar (📐 Overscan por app)",
      "text": "El botón 📐 Ajustar muestra dos deslizadores: margen horizontal y margen vertical. Recortan los bordes de la imagen proyectada en el cuadro. Los valores se guardan por aplicación y se reaplican automáticamente en cada inicio mediante wm overscan.",
      "note": "💡 Valores recomendados para Seal EU: ancho 80 px, alto 50 px."
    },
    "split": {
      "title": "5.3 Modo dividido (⬛⬛ División)",
      "text": "Pulsa ⬛⬛ División para compartir el cuadro entre dos apps:",
      "items": [
        "Pantalla completa: una app ocupa todo el cuadro",
        "⬜⬛ Izquierda (50%): app principal a la izquierda, segunda app a la derecha",
        "⬛⬜ Derecha (50%): app principal a la derecha"
      ],
      "extra": "En modo dividido, puedes seleccionar una segunda app en la lista. Ocupará la otra mitad del cuadro."
    },
    "hide": {
      "title": "5.4 Ocultar el panel",
      "text": "Pulsa Ocultar ▼ para plegar el panel de control y volver a la lista completa de apps."
    }
  },
  "stopping": {
    "title": "6. Detener la proyección",
    "intro": "Dos botones permiten detener la proyección:",
    "table": {
      "headers": [
        "Botón",
        "Comportamiento",
        "Cuándo usarlo"
      ],
      "rows": [
        [
          "Detener proyección",
          "Finaliza la proyección. El cuadro permanece en negro.",
          "Si solo quieres detener temporalmente la visualización."
        ],
        [
          "Restaurar cuadro original",
          "Finaliza la proyección Y restaura la pantalla BYD nativa (velocidad, autonomía, indicadores…).",
          "Al terminar el uso, para volver al cuadro BYD normal."
        ]
      ]
    },
    "warning": "⚠️ Si cierras DashCast sin pulsar uno de estos botones, la proyección permanece activa en el cuadro hasta el siguiente reinicio del servicio."
  },
  "settings": {
    "title": "7. Ajustes",
    "intro": "Accede a Ajustes desde ⋮ → ⚙️ Ajustes. La pantalla tiene dos secciones:",
    "titleLabel": "Ajustes",
    "clusterTypeLabel": "Tipo de cuadro",
    "clusterOptions": [
      "8.8 pulgadas (cmd=29)",
      "12.3 pulgadas (cmd=30) — Seal EU",
      "10.25 pulgadas (cmd=31)"
    ],
    "marginsLabel": "Márgenes de pantalla (overscan global)",
    "horizontalMarginLabel": "Izquierda / Derecha:",
    "verticalMarginLabel": "Arriba / Abajo:",
    "applyButton": "Aplicar ahora",
    "resetButton": "Restablecer (80 / 50)",
    "caption": "Página de ajustes",
    "type": {
      "title": "7.1 Tipo de cuadro",
      "text": "Selecciona el tamaño de pantalla de tu cuadro de instrumentos. Para BYD Seal EU, selecciona 12.3 pulgadas (cmd=30). Esta opción define el comando enviado al cuadro durante la activación."
    },
    "margins": {
      "title": "7.2 Márgenes globales de pantalla (overscan)",
      "text": "Ajusta los márgenes para encuadrar perfectamente el contenido dentro del área visible. Estos márgenes se aplican globalmente. Para configuración por app, usa el botón 📐 Ajustar del panel de control.",
      "items": [
        "Izquierda / Derecha — Margen horizontal (0-200 px en cada lado)",
        "Arriba / Abajo — Margen vertical (0-200 px arriba y abajo)"
      ],
      "applyText": "Pulsa Aplicar ahora para ver el resultado al instante si hay una app proyectada. Los valores se guardan entre sesiones.",
      "note": "💡 Valores por defecto recomendados para Seal EU: Izquierda/Derecha = 80 px, Arriba/Abajo = 50 px."
    }
  },
  "tools": {
    "title": "8. Menú ⋮ — Herramientas adicionales",
    "intro": "El botón ⋮ en la esquina superior derecha abre un menú con las siguientes opciones:",
    "table": {
      "headers": [
        "Opción",
        "Descripción"
      ],
      "rows": [
        [
          "⚙️ Ajustes",
          "Tipo de cuadro + configuración global de márgenes de overscan"
        ],
        [
          "🔲 Cuadrícula / Lista",
          "Alterna la visualización de apps entre lista clásica y cuadrícula de iconos (5 columnas)"
        ],
        [
          "🔧 Diagnóstico",
          "Herramientas avanzadas de desarrollador: conexión ADB, pantallas, tamaño de cuadro, sniffer ADB"
        ],
        [
          "📋 Informe del sistema",
          "Genera un informe completo (pantallas, APIs BYD, permisos), útil para soporte"
        ],
        [
          "📜 Registros",
          "Visor de logs en tiempo real: filtro por etiqueta/nivel, compartir por correo o archivo (pulsación larga para Telegram)"
        ],
        [
          "🌐 Idioma",
          "Vuelve a la pantalla de selección de idioma"
        ]
      ]
    },
    "logs": {
      "title": "Visor de logs (📜 Registros)",
      "header": "📋 Visor de logs",
      "clearButton": "Limpiar",
      "shareButton": "Compartir",
      "filterPlaceholder": "Filtrar (etiqueta / mensaje / nivel)…",
      "lines": [
        "[INFO ] ClusterService → Pantalla del cuadro conectada: id=1",
        "[INFO ] launchOnDashboard OK → com.google.android.apps.maps",
        "[DEBUG] watchdog iniciado para com.google.android.apps.maps pid=4821",
        "[WARN ] setTaskWindowingMode: SecurityException (esperada)",
        "[INFO ] wm overscan aplicado en pantalla 1 inset=80,50"
      ],
      "caption": "Visor de logs: filtro en tiempo real, exportación disponible"
    }
  },
  "faq": {
    "title": "9. FAQ y resolución de problemas",
    "items": [
      {
        "question": "❓ No aparece la ventana '¿Permitir depuración ADB?'",
        "answer": "Asegúrate de que la depuración ADB por TCP esté activada en los ajustes de desarrollador del sistema multimedia. Si la opción no aparece, activa primero el modo desarrollador (toca 7 veces el número de compilación en Acerca de).",
        "items": []
      },
      {
        "question": "❓ La app no aparece en el cuadro tras seleccionarla",
        "answer": "",
        "items": [
          "Comprueba que pulsaste Activar proyección antes de elegir la app.",
          "Algunas apps rechazan abrirse en una pantalla secundaria (restricciones internas). Consulta Registros para ver el error.",
          "Prueba a cerrar y volver a abrir DashCast, y repite la secuencia."
        ]
      },
      {
        "question": "❓ El contenido está recortado o desplazado en el cuadro",
        "answer": "Usa el botón 📐 Ajustar del panel de control para ajustar los márgenes por app. Los márgenes globales de Ajustes actúan como respaldo.",
        "items": []
      },
      {
        "question": "❓ Una app se queda congelada o bloqueada en el cuadro",
        "answer": "Pulsa la ❌ Cruz roja junto a la app en la lista. Esto fuerza la detención completa del proceso y limpia Recientes. Después, la app puede relanzarse.",
        "items": []
      },
      {
        "question": "❓ Los botones ← Principal y ✕ siguen visibles tras cerrar la app",
        "answer": "DashCast detecta automáticamente el cierre de apps (mediante monitorización de /proc). Si la interfaz sigue bloqueada, pulsa Detener proyección para forzar un reinicio.",
        "items": []
      }
    ]
  },
  "footer": "DashCast v0.7.2 — BYD Seal EU · DiLink 3.0 · Android 10 · github.com/Kiroha/byd-dashcast"
};
