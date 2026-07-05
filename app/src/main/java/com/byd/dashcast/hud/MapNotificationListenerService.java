package com.byd.dashcast.hud;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.byd.dashcast.system.CanBusController;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MapNotificationListenerService — parses Google Maps (and compatible) navigation
 * notifications and drives the {@link HudController} to update the BYD cluster HUD.
 *
 * <p>The user must enable this service once in
 * <b>Settings → Apps → Special app access → Notification access → DashCast</b>.
 * Once enabled it runs as a system-bound service and receives all notifications
 * automatically — no further interaction required.
 *
 * <p>Supported navigation apps:
 * <ul>
 *   <li>{@code com.google.android.apps.maps} — Google Maps</li>
 *   <li>{@code app.revanced.android.apps.maps} — Maps ReVanced</li>
 *   <li>{@code com.waze} — Waze (best-effort text parsing)</li>
 * </ul>
 *
 * <p>Parsing strategy (applied in order):
 * <ol>
 *   <li>Try to resolve the notification small icon resource name and map it to a BYD
 *       turn icon ID (most reliable, version-independent).</li>
 *   <li>Fall back to keyword-based text parsing of the notification title + text
 *       (handles all locales, but less precise for ambiguous instructions).</li>
 * </ol>
 */
public final class MapNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "MapNavListener";

    // ─── HUD write offloading ─────────────────────────────────────────────
    // All ProxyClient/CAN writes (HudController.updateNavigation/closeNavigation
    // → CanBusController.send* → ProxyClient binder round-trips + sendBroadcast)
    // run on this single-thread SERIAL executor so the system notification
    // dispatch thread never blocks on the daemon. Serial (not pooled) preserves
    // guidance-frame order → CAN frames are never reordered. pendingNav coalesces
    // bursts: only the latest queued nav state survives; stale frames are dropped.
    // appContext is the process-scoped application context (safe to retain — not
    // the service instance, so no leak).
    private ExecutorService hudExecutor;
    private volatile Context appContext;
    private final AtomicReference<HudNavigationData> pendingNav = new AtomicReference<>();

    /** Drains the newest queued nav state on the serial writer thread. */
    private final Runnable navUpdateTask = () -> {
        HudNavigationData d = pendingNav.getAndSet(null);
        if (d == null) return;            // superseded by a newer frame — coalesced away
        Context ctx = appContext;
        if (ctx == null) return;          // service torn down between enqueue and drain
        HudController.INSTANCE.updateNavigation(ctx, d);
    };

    // Notification-level deduplication — avoid reprocessing identical notification content
    // (same pattern as OpenBYD MapNotificationListenerService lastNotification* fields).
    private String lastTitle   = "";
    private String lastText    = "";
    private String lastSubText = "";

    private static final String PKG_MAPS          = "com.google.android.apps.maps";
    private static final String PKG_MAPS_REVANCED  = "app.revanced.android.apps.maps";
    private static final String PKG_WAZE           = "com.waze";

    // ─── Distance: "300 m", "1.2 km", "1,2 km", "500 m", "3 км" ─
    // Mirrors OpenBYD regex: \b(\d+[.,]?\d*)[\s ]*(km|км|m|м)\b
    // Handles ASCII and Cyrillic unit suffixes, optional decimal, optional NBSP.
    private static final Pattern RX_DIST =
            Pattern.compile("\\b(\\d+[.,]?\\d*)[\\s\\u00A0]*(km|км|m|м)\\b",
                    Pattern.CASE_INSENSITIVE);

    // ─── Road name: "onto X", "sur X", "on X" ────────────────────────────
    private static final Pattern RX_ROAD_ONTO =
            Pattern.compile("(?:onto|on|sur|vers)\\s+(.+?)(?:\\s+in\\s+\\d|\\s+dans\\s+\\d|$)",
                    Pattern.CASE_INSENSITIVE);

    // ─── Remaining time — two-pattern approach matching OpenBYD smali ─────
    // Hours: \b(\d+)[\s ]*(?:h|hr|hrs|hour|hours|ч|ч\.)\b
    // Mins:  \b(\d+)[\s ]*(?:min|mins|мин)\b
    // Both handle ASCII and Cyrillic units plus non-breaking space.
    private static final Pattern RX_HOURS =
            Pattern.compile("\\b(\\d+)[\\s\\u00A0]*(?:h|hr|hrs|hour|hours|ч|ч\\.)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_MINS =
            Pattern.compile("\\b(\\d+)[\\s\\u00A0]*(?:min|mins|мин)\\b",
                    Pattern.CASE_INSENSITIVE);

    // ─── Noise-notification skip strings (from OpenBYD smali) ────────────
    // Matched (lowercase) against both title and text. These appear in ongoing
    // navigation notifications that carry no maneuver data (GPS acquiring, etc.).
    private static final String[] SKIP_STRINGS = {
        "tap to open",
        "running in the background",
        "app is running",
        "searching for gps",
        "gps signal lost",
    };

    // ─── Google Maps icon resource name → BYD turn icon ID ───────────────
    // Names observed across Maps versions 10.x–11.x (as of 2024-2025).
    // Partial match is used (contains check) so minor version suffixes don't break it.
    private static final Object[][] ICON_NAME_MAP = {
        { "arrow_right",         CanBusController.ICON_TURN_RIGHT        },
        { "arrow_left",          CanBusController.ICON_TURN_LEFT         },
        { "slight_right",        CanBusController.ICON_SLIGHT_RIGHT      },
        { "slight_left",         CanBusController.ICON_SLIGHT_LEFT       },
        { "sharp_right",         CanBusController.ICON_SHARP_RIGHT       },
        { "sharp_left",          CanBusController.ICON_SHARP_LEFT        },
        { "u_turn_right",        CanBusController.ICON_U_TURN_RIGHT      },
        { "u_turn_left",         CanBusController.ICON_U_TURN_LEFT       },
        { "u_turn",              CanBusController.ICON_U_TURN_LEFT       },
        { "uturn_right",         CanBusController.ICON_U_TURN_RIGHT      },
        { "uturn_left",          CanBusController.ICON_U_TURN_LEFT       },
        { "straight",            CanBusController.ICON_STRAIGHT_SOLID    },
        { "continue",            CanBusController.ICON_STRAIGHT_SOLID    },
        { "roundabout_cw",       CanBusController.ICON_ROUNDABOUT_CW_1_LAP  },
        { "roundabout_ccw",      CanBusController.ICON_ROUNDABOUT_CCW_1_LAP },
        { "roundabout",          CanBusController.ICON_ROUNDABOUT_CW_1_LAP  },
        { "destination",         CanBusController.ICON_DESTINATION       },
        { "arrive",              CanBusController.ICON_DESTINATION       },
        { "finish",              CanBusController.ICON_DESTINATION       },
        { "merge_right",         CanBusController.ICON_SLIGHT_RIGHT      },
        { "merge_left",          CanBusController.ICON_SLIGHT_LEFT       },
        { "merge",               CanBusController.ICON_SLIGHT_RIGHT      },
        { "ramp_right",          CanBusController.ICON_SLIGHT_RIGHT      },
        { "ramp_left",           CanBusController.ICON_SLIGHT_LEFT       },
        { "fork_right",          CanBusController.ICON_SLIGHT_RIGHT      },
        { "fork_left",           CanBusController.ICON_SLIGHT_LEFT       },
        { "exit_right",          CanBusController.ICON_DETOUR_RIGHT      },
        { "exit_left",           CanBusController.ICON_DETOUR_LEFT       },
        { "tollbooth",           CanBusController.ICON_TOLLBOOTH         },
        { "tunnel",              CanBusController.ICON_TUNNEL            },
    };

    // ─── Text keyword → BYD turn icon ID (EN + FR + DE) ─────────────────
    // Evaluated in order; first match wins. More specific patterns come first.
    // All keywords are lowercase — matched against combined.toLowerCase(Locale.ROOT).
    private static final Object[][] TEXT_KEYWORD_MAP = {
        // Destination / arrival (must be before generic "right"/"left")
        { "you have arrived",         CanBusController.ICON_DESTINATION      },
        { "you've arrived",           CanBusController.ICON_DESTINATION      },
        { "vous êtes arrivé",         CanBusController.ICON_DESTINATION      }, // "Vous êtes arrivé(e)"
        { "destination",              CanBusController.ICON_DESTINATION      },
        { "sie haben ihr ziel",       CanBusController.ICON_DESTINATION      }, // DE
        // U-turn (most specific first — must precede plain "right"/"left")
        { "u-turn right",             CanBusController.ICON_U_TURN_RIGHT     },
        { "u-turn left",              CanBusController.ICON_U_TURN_LEFT      },
        { "u-turn",                   CanBusController.ICON_U_TURN_LEFT      },
        { "uturn",                    CanBusController.ICON_U_TURN_LEFT      },
        { "faites demi-tour",         CanBusController.ICON_U_TURN_LEFT      }, // exact Google Maps FR
        { "demi-tour",                CanBusController.ICON_U_TURN_LEFT      },
        { "kehren sie um",            CanBusController.ICON_U_TURN_LEFT      }, // DE
        // Sharp (before slight/standard to avoid substring matches)
        { "sharp right",              CanBusController.ICON_SHARP_RIGHT      },
        { "sharp left",               CanBusController.ICON_SHARP_LEFT       },
        { "virez fortement à droite", CanBusController.ICON_SHARP_RIGHT      },
        { "virez fortement à gauche", CanBusController.ICON_SHARP_LEFT       },
        { "scharf rechts",            CanBusController.ICON_SHARP_RIGHT      }, // DE
        { "scharf links",             CanBusController.ICON_SHARP_LEFT       }, // DE
        // Slight / keep (before standard turns)
        { "slight right",             CanBusController.ICON_SLIGHT_RIGHT     },
        { "slight left",              CanBusController.ICON_SLIGHT_LEFT      },
        { "légèrement à droite",      CanBusController.ICON_SLIGHT_RIGHT     },
        { "légèrement à gauche",      CanBusController.ICON_SLIGHT_LEFT      },
        { "keep right",               CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "keep left",                CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "restez à droite",          CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "restez à gauche",          CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "halbrechts",               CanBusController.ICON_SLIGHT_RIGHT     }, // DE
        { "halblinks",                CanBusController.ICON_SLIGHT_LEFT      }, // DE
        // Standard turns
        { "turn right",               CanBusController.ICON_TURN_RIGHT       },
        { "turn left",                CanBusController.ICON_TURN_LEFT        },
        { "tournez à droite",         CanBusController.ICON_TURN_RIGHT       },
        { "tournez à gauche",         CanBusController.ICON_TURN_LEFT        },
        { "rechts abbiegen",          CanBusController.ICON_TURN_RIGHT       }, // DE
        { "links abbiegen",           CanBusController.ICON_TURN_LEFT        }, // DE
        // Exit / ramp
        { "exit right",               CanBusController.ICON_DETOUR_RIGHT     },
        { "exit left",                CanBusController.ICON_DETOUR_LEFT      },
        { "take the exit",            CanBusController.ICON_DETOUR_RIGHT     },
        { "exit",                     CanBusController.ICON_DETOUR_RIGHT     },
        { "prenez la sortie",         CanBusController.ICON_DETOUR_RIGHT     }, // "Prenez la sortie n°5"
        { "sortie",                   CanBusController.ICON_DETOUR_RIGHT     },
        { "ausfahrt",                 CanBusController.ICON_DETOUR_RIGHT     }, // DE
        // Merge / ramp
        { "merge right",              CanBusController.ICON_SLIGHT_RIGHT     },
        { "merge left",               CanBusController.ICON_SLIGHT_LEFT      },
        { "merge",                    CanBusController.ICON_SLIGHT_RIGHT     },
        { "rejoin",                   CanBusController.ICON_SLIGHT_RIGHT     }, // "rejoindre" and "rejoignez"
        // Roundabout
        { "roundabout",               CanBusController.ICON_ROUNDABOUT_CW_1_LAP  },
        { "rond-point",               CanBusController.ICON_ROUNDABOUT_CW_1_LAP  },
        { "kreisverkehr",             CanBusController.ICON_ROUNDABOUT_CW_1_LAP  }, // DE
        // Straight / continue (last — very generic, must not shadow more specific keys)
        { "head north",               CanBusController.ICON_STRAIGHT_SOLID   },
        { "head south",               CanBusController.ICON_STRAIGHT_SOLID   },
        { "head east",                CanBusController.ICON_STRAIGHT_SOLID   },
        { "head west",                CanBusController.ICON_STRAIGHT_SOLID   },
        { "head toward",              CanBusController.ICON_STRAIGHT_SOLID   },
        { "continue straight",        CanBusController.ICON_STRAIGHT_SOLID   },
        { "continue",                 CanBusController.ICON_STRAIGHT_SOLID   },
        { "continuez tout droit",     CanBusController.ICON_STRAIGHT_SOLID   },
        { "continuez",                CanBusController.ICON_STRAIGHT_SOLID   },
        { "tout droit",               CanBusController.ICON_STRAIGHT_SOLID   },
        { "straight",                 CanBusController.ICON_STRAIGHT_SOLID   },
        { "geradeaus",                CanBusController.ICON_STRAIGHT_SOLID   }, // DE
        // Tollbooth / tunnel
        { "tollbooth",                CanBusController.ICON_TOLLBOOTH        },
        { "péage",                    CanBusController.ICON_TOLLBOOTH        },
        { "maut",                     CanBusController.ICON_TOLLBOOTH        }, // DE
        { "tunnel",                   CanBusController.ICON_TUNNEL           },
        { "galleria",                 CanBusController.ICON_TUNNEL           }, // IT (tunnel in road)

        // ── ES (Spanish) ─────────────────────────────────────────────────
        { "has llegado a tu destino", CanBusController.ICON_DESTINATION      },
        { "llegaste a tu destino",    CanBusController.ICON_DESTINATION      },
        { "da media vuelta a la derecha", CanBusController.ICON_U_TURN_RIGHT },
        { "da media vuelta a la izquierda", CanBusController.ICON_U_TURN_LEFT },
        { "da media vuelta",          CanBusController.ICON_U_TURN_LEFT      },
        { "gira bruscamente a la derecha", CanBusController.ICON_SHARP_RIGHT },
        { "gira bruscamente a la izquierda", CanBusController.ICON_SHARP_LEFT },
        { "dobla levemente a la derecha", CanBusController.ICON_SLIGHT_RIGHT },
        { "dobla levemente a la izquierda", CanBusController.ICON_SLIGHT_LEFT },
        { "mantente a la derecha",    CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "mantente a la izquierda",  CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "gira a la derecha",        CanBusController.ICON_TURN_RIGHT       },
        { "dobla a la derecha",       CanBusController.ICON_TURN_RIGHT       },
        { "gira a la izquierda",      CanBusController.ICON_TURN_LEFT        },
        { "dobla a la izquierda",     CanBusController.ICON_TURN_LEFT        },
        { "toma la salida a la derecha", CanBusController.ICON_DETOUR_RIGHT  },
        { "toma la salida a la izquierda", CanBusController.ICON_DETOUR_LEFT },
        { "toma la salida",           CanBusController.ICON_DETOUR_RIGHT     },
        { "salida",                   CanBusController.ICON_DETOUR_RIGHT     },
        { "incorpórate",              CanBusController.ICON_SLIGHT_RIGHT     },
        { "glorieta",                 CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "continúa recto",           CanBusController.ICON_STRAIGHT_SOLID   },
        { "continúa",                 CanBusController.ICON_STRAIGHT_SOLID   },
        { "sigue recto",              CanBusController.ICON_STRAIGHT_SOLID   },
        { "peaje",                    CanBusController.ICON_TOLLBOOTH        },
        { "túnel",                    CanBusController.ICON_TUNNEL           },

        // ── IT (Italian) ─────────────────────────────────────────────────
        { "sei arrivato a destinazione", CanBusController.ICON_DESTINATION   },
        { "hai raggiunto la destinazione", CanBusController.ICON_DESTINATION },
        { "fai inversione",           CanBusController.ICON_U_TURN_LEFT      },
        { "inversione a u",           CanBusController.ICON_U_TURN_LEFT      },
        { "svolta nettamente a destra", CanBusController.ICON_SHARP_RIGHT    },
        { "svolta nettamente a sinistra", CanBusController.ICON_SHARP_LEFT   },
        { "svolta leggermente a destra", CanBusController.ICON_SLIGHT_RIGHT  },
        { "svolta leggermente a sinistra", CanBusController.ICON_SLIGHT_LEFT },
        { "mantieni la destra",       CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "mantieni la sinistra",     CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "svolta a destra",          CanBusController.ICON_TURN_RIGHT       },
        { "svolta a sinistra",        CanBusController.ICON_TURN_LEFT        },
        { "prendi l'uscita a destra", CanBusController.ICON_DETOUR_RIGHT     },
        { "prendi l'uscita a sinistra", CanBusController.ICON_DETOUR_LEFT    },
        { "prendi l'uscita",          CanBusController.ICON_DETOUR_RIGHT     },
        { "uscita",                   CanBusController.ICON_DETOUR_RIGHT     },
        { "immettiti",                CanBusController.ICON_SLIGHT_RIGHT     },
        { "rotatoria",                CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "prosegui dritto",          CanBusController.ICON_STRAIGHT_SOLID   },
        { "continua dritto",          CanBusController.ICON_STRAIGHT_SOLID   },
        { "casello",                  CanBusController.ICON_TOLLBOOTH        },
        { "pedaggio",                 CanBusController.ICON_TOLLBOOTH        },

        // ── RU (Russian) ─────────────────────────────────────────────────
        { "вы достигли места назначения", CanBusController.ICON_DESTINATION  },
        { "вы прибыли",               CanBusController.ICON_DESTINATION      },
        { "развернитесь",             CanBusController.ICON_U_TURN_LEFT      },
        { "сделайте разворот",        CanBusController.ICON_U_TURN_LEFT      },
        { "круто поверните направо",  CanBusController.ICON_SHARP_RIGHT      },
        { "круто поверните налево",   CanBusController.ICON_SHARP_LEFT       },
        { "немного поверните направо", CanBusController.ICON_SLIGHT_RIGHT    },
        { "немного поверните налево", CanBusController.ICON_SLIGHT_LEFT      },
        { "держитесь правее",         CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "держитесь левее",          CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "поверните направо",        CanBusController.ICON_TURN_RIGHT       },
        { "поверните налево",         CanBusController.ICON_TURN_LEFT        },
        { "кольцевая развязка",       CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "езжайте прямо",            CanBusController.ICON_STRAIGHT_SOLID   },
        { "продолжайте движение",     CanBusController.ICON_STRAIGHT_SOLID   },
        { "пункт оплаты",             CanBusController.ICON_TOLLBOOTH        },
        { "тоннель",                  CanBusController.ICON_TUNNEL           },

        // ── PL (Polish) ──────────────────────────────────────────────────
        { "dotarłeś do celu",         CanBusController.ICON_DESTINATION      },
        { "jesteś na miejscu",        CanBusController.ICON_DESTINATION      },
        { "zawróć",                   CanBusController.ICON_U_TURN_LEFT      },
        { "ostry skręt w prawo",      CanBusController.ICON_SHARP_RIGHT      },
        { "ostry skręt w lewo",       CanBusController.ICON_SHARP_LEFT       },
        { "łagodny skręt w prawo",    CanBusController.ICON_SLIGHT_RIGHT     },
        { "łagodny skręt w lewo",     CanBusController.ICON_SLIGHT_LEFT      },
        { "trzymaj się prawej",       CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "trzymaj się lewej",        CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "skręć w prawo",            CanBusController.ICON_TURN_RIGHT       },
        { "skręć w lewo",             CanBusController.ICON_TURN_LEFT        },
        { "włącz się do ruchu",       CanBusController.ICON_SLIGHT_RIGHT     },
        { "rondo",                    CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "jedź prosto",              CanBusController.ICON_STRAIGHT_SOLID   },
        { "myto",                     CanBusController.ICON_TOLLBOOTH        },
        { "tunel",                    CanBusController.ICON_TUNNEL           },

        // ── UK (Ukrainian) ───────────────────────────────────────────────
        { "ви досягли пункту призначення", CanBusController.ICON_DESTINATION },
        { "ви прибули",               CanBusController.ICON_DESTINATION      },
        { "розгорніться",             CanBusController.ICON_U_TURN_LEFT      },
        { "різко поверніть праворуч", CanBusController.ICON_SHARP_RIGHT      },
        { "різко поверніть ліворуч",  CanBusController.ICON_SHARP_LEFT       },
        { "трохи поверніть праворуч", CanBusController.ICON_SLIGHT_RIGHT     },
        { "трохи поверніть ліворуч",  CanBusController.ICON_SLIGHT_LEFT      },
        { "тримайтесь правіше",       CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "тримайтесь лівіше",        CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "поверніть праворуч",       CanBusController.ICON_TURN_RIGHT       },
        { "поверніть ліворуч",        CanBusController.ICON_TURN_LEFT        },
        { "кільцева розв'язка",       CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "їдьте прямо",              CanBusController.ICON_STRAIGHT_SOLID   },
        { "продовжуйте рух",          CanBusController.ICON_STRAIGHT_SOLID   },
        { "тунель",                   CanBusController.ICON_TUNNEL           },

        // ── BE (Belarusian) ──────────────────────────────────────────────
        { "вы дасягнулі месца прызначэння", CanBusController.ICON_DESTINATION },
        { "разгарніцеся",             CanBusController.ICON_U_TURN_LEFT      },
        { "паварачвайце направа",     CanBusController.ICON_TURN_RIGHT       },
        { "паварачвайце налева",      CanBusController.ICON_TURN_LEFT        },
        { "кальцавая развязка",       CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "едзьце прама",             CanBusController.ICON_STRAIGHT_SOLID   },

        // ── TR (Turkish) ─────────────────────────────────────────────────
        { "hedefinize ulaştınız",     CanBusController.ICON_DESTINATION      },
        { "u dönüşü yapın",           CanBusController.ICON_U_TURN_LEFT      },
        { "geri dönün",               CanBusController.ICON_U_TURN_LEFT      },
        { "sert sağa dönün",          CanBusController.ICON_SHARP_RIGHT      },
        { "sert sola dönün",          CanBusController.ICON_SHARP_LEFT       },
        { "hafifçe sağa dönün",       CanBusController.ICON_SLIGHT_RIGHT     },
        { "hafifçe sola dönün",       CanBusController.ICON_SLIGHT_LEFT      },
        { "sağ şeritte kalın",        CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "sol şeritte kalın",        CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "sağa dönün",               CanBusController.ICON_TURN_RIGHT       },
        { "sola dönün",               CanBusController.ICON_TURN_LEFT        },
        { "çıkışı alın",              CanBusController.ICON_DETOUR_RIGHT     },
        { "dönel kavşak",             CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "düz gidin",                CanBusController.ICON_STRAIGHT_SOLID   },
        { "ücretli gişe",             CanBusController.ICON_TOLLBOOTH        },
        { "tünel",                    CanBusController.ICON_TUNNEL           },

        // ── AR (Arabic) ──────────────────────────────────────────────────
        { "لقد وصلت إلى وجهتك",      CanBusController.ICON_DESTINATION      },
        { "وصلت إلى وجهتك",          CanBusController.ICON_DESTINATION      },
        { "استدر",                    CanBusController.ICON_U_TURN_LEFT      },
        { "انعطف بشدة يمينًا",        CanBusController.ICON_SHARP_RIGHT      },
        { "انعطف بشدة يسارًا",        CanBusController.ICON_SHARP_LEFT       },
        { "انعطف قليلاً يمينًا",      CanBusController.ICON_SLIGHT_RIGHT     },
        { "انعطف قليلاً يسارًا",      CanBusController.ICON_SLIGHT_LEFT      },
        { "التزم اليمين",             CanBusController.ICON_SLIGHT_RIGHT_ALT },
        { "التزم اليسار",             CanBusController.ICON_SLIGHT_LEFT_ALT  },
        { "اتجه يمينًا",              CanBusController.ICON_TURN_RIGHT       },
        { "اتجه يسارًا",              CanBusController.ICON_TURN_LEFT        },
        { "دوار",                     CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "سر مستقيمًا",              CanBusController.ICON_STRAIGHT_SOLID   },
        { "رسوم",                     CanBusController.ICON_TOLLBOOTH        },
        { "نفق",                      CanBusController.ICON_TUNNEL           },

        // ── KK (Kazakh) ──────────────────────────────────────────────────
        { "сіз межеңізге жеттіңіз",   CanBusController.ICON_DESTINATION      },
        { "артқа бұрылыңыз",          CanBusController.ICON_U_TURN_LEFT      },
        { "оңға бұрылыңыз",           CanBusController.ICON_TURN_RIGHT       },
        { "солға бұрылыңыз",          CanBusController.ICON_TURN_LEFT        },
        { "кольцелік қиылыс",         CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "тура жүріңіз",             CanBusController.ICON_STRAIGHT_SOLID   },

        // ── UZ (Uzbek) ───────────────────────────────────────────────────
        { "manzilga yetib keldingiz", CanBusController.ICON_DESTINATION      },
        { "orqaga buriling",          CanBusController.ICON_U_TURN_LEFT      },
        { "o'ngga buriling",          CanBusController.ICON_TURN_RIGHT       },
        { "chapga buriling",          CanBusController.ICON_TURN_LEFT        },
        { "aylana chorraha",          CanBusController.ICON_ROUNDABOUT_CW_1_LAP },
        { "to'g'ri boring",           CanBusController.ICON_STRAIGHT_SOLID   },
    };

    // ─── NotificationListenerService callbacks ────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        hudExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hud-nav-writer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onDestroy() {
        pendingNav.set(null);
        ExecutorService ex = hudExecutor;
        hudExecutor = null;
        if (ex != null) ex.shutdownNow();
        // appContext is the application context (process-scoped); leave it set so a
        // task racing shutdownNow still has a valid context and NPEs are impossible.
        super.onDestroy();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // System unbound the listener (e.g. permission revoked, system crash).
        // Close the HUD so the cluster doesn't stay frozen on the last nav state.
        postNavClose();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;

        Notification n = sbn.getNotification();
        if (n == null) return;

        // Only process ongoing navigation notifications.
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0
                && !Notification.CATEGORY_NAVIGATION.equals(n.category)) {
            return;
        }

        Bundle extras = n.extras;
        if (extras == null) return;

        String title   = charSeqToString(extras.getCharSequence("android.title"));
        String text    = charSeqToString(extras.getCharSequence("android.text"));
        String bigText = charSeqToString(extras.getCharSequence("android.bigText"));
        String subText = charSeqToString(extras.getCharSequence("android.subText"));

        // Notification-level deduplication: skip if content hasn't changed since last call.
        if (title.equals(lastTitle) && text.equals(lastText) && subText.equals(lastSubText)) return;
        lastTitle   = title;
        lastText    = text;
        lastSubText = subText;

        // Skip noise notifications (OpenBYD MapNotificationListenerService pattern).
        // These are ongoing navigation notifications that carry no maneuver data.
        String lowerText  = text.toLowerCase(Locale.ROOT);
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        for (String skip : SKIP_STRINGS) {
            if (lowerText.contains(skip) || lowerTitle.contains(skip)) return;
        }

        // Combine title + text for pattern matching.
        String combined = (title + " " + text + " " + bigText).trim();
        String lower    = combined.toLowerCase(Locale.ROOT);

        if (combined.isEmpty()) return;

        // 1. Turn icon — try icon resource name first, then text.
        int iconId = resolveIconFromResource(sbn.getPackageName(), n.getSmallIcon());
        if (iconId <= 0) {
            iconId = resolveIconFromText(lower);
        }
        if (iconId <= 0) iconId = CanBusController.ICON_STRAIGHT_SOLID; // safe default

        // 2. Distance to next turn — scan title then text then combined.
        int distance = parseFirstDistance(combined);
        if (distance < 0) {
            Log.d(TAG, "no distance found in: " + combined);
            return; // cannot update HUD without a valid distance
        }

        // 3. Road name — look for "onto X" / "sur X" pattern.
        String roadName = parseRoadName(combined);

        // 4. Remaining route (from subText or parenthetical in text).
        String routeSrc = subText.isEmpty() ? text : subText;
        Integer remainSec  = parseRemainingSeconds(routeSrc);
        Integer remainDist = parseRemainingMeters(routeSrc);

        HudNavigationData data = new HudNavigationData(
                iconId, distance, roadName, remainDist, remainSec);

        Log.d(TAG, "nav update: icon=" + iconId + " dist=" + distance
                + " road='" + roadName + "'"
                + " remDist=" + remainDist + " remSec=" + remainSec);

        // Offload the ProxyClient/CAN write off the notification dispatch thread.
        postNavUpdate(data);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            Log.d(TAG, "nav notification removed → closeNavigation");
            postNavClose();
            lastTitle   = "";
            lastText    = "";
            lastSubText = "";
        }
    }

    // ─── HUD write dispatch (serial writer thread) ────────────────────────

    /**
     * Queue a nav update on the serial HUD writer thread. Publishes the latest
     * state and enqueues a drain task; if updates arrive faster than the daemon
     * drains them, {@link #navUpdateTask} coalesces to the newest state (older
     * frames dropped). Never blocks the notification dispatch thread. When the
     * daemon is warm the task drains immediately → one write per update.
     */
    private void postNavUpdate(HudNavigationData data) {
        if (data == null) return;
        pendingNav.set(data);
        ExecutorService ex = hudExecutor;
        if (ex == null) return; // service torn down — nothing left to drive the HUD
        try {
            ex.execute(navUpdateTask);
        } catch (RejectedExecutionException ignore) {
            // Executor shutting down: drop this frame; teardown close resets the cluster.
        }
    }

    /**
     * Queue a HUD close on the serial writer thread. Cancels any queued-but-unrun
     * guidance frame first so a stale update cannot re-open the HUD after removal.
     * Falls back to a synchronous close ONLY when the executor is already gone or
     * rejects (service teardown) so the cluster never freezes on the last nav state.
     */
    private void postNavClose() {
        pendingNav.set(null); // cancel any pending guidance frame (close wins)
        final Context ctx = appContext;
        if (ctx == null) return;
        ExecutorService ex = hudExecutor;
        if (ex == null) {
            HudController.INSTANCE.closeNavigation(ctx);
            return;
        }
        try {
            ex.execute(() -> HudController.INSTANCE.closeNavigation(ctx));
        } catch (RejectedExecutionException ignore) {
            HudController.INSTANCE.closeNavigation(ctx);
        }
    }

    // ─── Icon resolution ──────────────────────────────────────────────────

    /**
     * Try to get the icon resource entry name from the source package and map it
     * to a BYD turn icon ID. Returns ≤ 0 on failure (caller falls back to text).
     */
    private int resolveIconFromResource(String pkg, Icon icon) {
        if (icon == null || icon.getType() != Icon.TYPE_RESOURCE) return -1;
        try {
            Resources res = createPackageContext(pkg, 0).getResources();
            int resId = icon.getResId();
            if (resId == 0) return -1;
            String name = res.getResourceEntryName(resId).toLowerCase(Locale.ROOT);
            for (Object[] entry : ICON_NAME_MAP) {
                if (name.contains((String) entry[0])) return (int) entry[1];
            }
        } catch (Throwable t) {
            Log.d(TAG, "icon res lookup failed: " + t.getMessage());
        }
        return -1;
    }

    private static int resolveIconFromText(String lower) {
        for (Object[] entry : TEXT_KEYWORD_MAP) {
            if (lower.contains((String) entry[0])) return (int) entry[1];
        }
        return -1;
    }

    // ─── Distance parsing ─────────────────────────────────────────────────

    /** Returns the first distance found (in metres) or -1. */
    private static int parseFirstDistance(String text) {
        return parseMeters(text, -1);
    }

    /** Returns the first distance found (in metres) or null. */
    private static Integer parseRemainingMeters(String text) {
        if (text == null || text.isEmpty()) return null;
        int v = parseMeters(text, Integer.MIN_VALUE);
        return v == Integer.MIN_VALUE ? null : v;
    }

    /**
     * Core distance parser matching OpenBYD smali regex
     * \b(\d+[.,]?\d*)[\s ]*(km|км|m|м)\b
     * Unit determines conversion; returns {@code notFound} sentinel on failure.
     */
    private static int parseMeters(String text, int notFound) {
        Matcher m = RX_DIST.matcher(text);
        if (!m.find()) return notFound;
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        try {
            float val = Float.parseFloat(m.group(1).replace(',', '.'));
            return (unit.equals("km") || unit.equals("км"))
                    ? Math.round(val * 1000f)
                    : Math.round(val);
        } catch (NumberFormatException ignore) {
            return notFound;
        }
    }

    /**
     * Parses remaining time using two separate patterns (hours + minutes),
     * matching the OpenBYD smali approach. Supports "12 min", "1h 5m",
     * "1 h 05 min", "45 мин", "2 ч 10 мин". Returns null if nothing found.
     */
    private static Integer parseRemainingSeconds(String text) {
        if (text == null || text.isEmpty()) return null;
        int totalSeconds = 0;
        boolean found = false;
        Matcher mh = RX_HOURS.matcher(text);
        if (mh.find()) {
            try { totalSeconds += Integer.parseInt(mh.group(1)) * 3600; found = true; }
            catch (NumberFormatException ignore) {}
        }
        Matcher mm = RX_MINS.matcher(text);
        if (mm.find()) {
            try { totalSeconds += Integer.parseInt(mm.group(1)) * 60; found = true; }
            catch (NumberFormatException ignore) {}
        }
        return found ? totalSeconds : null;
    }

    // ─── Road name ────────────────────────────────────────────────────────

    private static String parseRoadName(String text) {
        Matcher m = RX_ROAD_ONTO.matcher(text);
        if (m.find()) {
            String name = m.group(1);
            return name != null ? name.trim() : "";
        }
        return "";
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static boolean isNavPackage(String pkg) {
        return PKG_MAPS.equals(pkg) || PKG_MAPS_REVANCED.equals(pkg) || PKG_WAZE.equals(pkg);
    }

    private static String charSeqToString(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }
}
