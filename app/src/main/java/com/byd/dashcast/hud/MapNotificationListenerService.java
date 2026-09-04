package com.byd.dashcast.hud;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.byd.dashcast.system.CanBusController;
import com.byd.dashcast.data.prefs.ClusterPrefs;
import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.util.PackagePseudonymizer;
import com.byd.dashcast.util.concurrent.LatestValueDispatcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    // guidance-frame order → CAN frames are never reordered. LatestValueDispatcher
    // keeps at most one drain task and one pending state during notification bursts.
    // appContext is the process-scoped application context (safe to retain — not
    // the service instance, so no leak).
    private LatestValueDispatcher<PendingHudUpdate> hudDispatcher;
    private volatile Context appContext;
    private final HudDeliveryTracker hudDeliveryTracker = new HudDeliveryTracker();

    // Notification-level deduplication — avoid reprocessing identical notification content
    // (same pattern as OpenBYD MapNotificationListenerService lastNotification* fields).
    private String lastTitle   = "";
    private String lastText    = "";
    private String lastBigText = "";
    private String lastSubText = "";
    private String lastNotificationKey;

    // Last logged (icon|road) so the NAV PARSE diagnostic (raw notification → parsed icon, captured
    // in the DashCast journal / bug report) is written once per distinct maneuver, not every second.
    private String lastLoggedNav = "";

    // Throttle for the "unsupported nav app" diagnostic — the same app is logged at most once per
    // ~30 s so a "no arrow" bug report always carries a RECENT line explaining why (e.g. Telenav).
    private String lastUnsupportedNavPkg = "";
    private long   lastUnsupportedLogMs;
    private byte[] packageMarkerKey;

    private static final String PACKAGE_MARKER_KEY_FILE = "nav_package_marker.key";
    private static final int PACKAGE_MARKER_KEY_BYTES = 32;

    // Cache of per-source-package Resources (Maps/Waze/ReVanced). A nav app's resources
    // don't change at runtime, so build the Context + AssetManager once per package instead
    // of on every notification (createPackageContext().getResources() is a PM lookup + asset
    // load that previously ran per nav frame on the dispatch thread).
    private final java.util.concurrent.ConcurrentHashMap<String, Resources> mResCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final String PKG_MAPS          = "com.google.android.apps.maps";
    private static final String PKG_MAPS_REVANCED  = "app.revanced.android.apps.maps";
    private static final String PKG_WAZE           = "com.waze";

    /**
     * How recently a supported nav app must have been active for the bug reporter to accept that a
     * route was running. Deliberately generous: the wizard is normally used AFTER the drive, so a
     * tight window would wrongly accuse a driver who has just parked. A window (rather than a
     * latch-forever flag) also stops one route from making every later report claim a route was live.
     */
    private static final long NAV_RECENT_MS = 30L * 60L * 1000L;

    /**
     * Per supported nav package: {@code {last guidance notification POSTED, last frame fully PARSED}}
     * as {@code elapsedRealtime}.
     *
     * <p>Two separate timestamps because they answer different questions, and conflating them is
     * exactly what made the first version of this gate harmful:
     * <ul>
     *   <li><b>posted</b> — the nav app IS guiding (it only posts turn-by-turn while a route runs).</li>
     *   <li><b>parsed</b> — and DashCast understood it well enough to drive the HUD.</li>
     * </ul>
     * "posted but never parsed" is a DashCast <em>parser</em> bug — the single most valuable "no
     * arrow" report there is — so it must never be gated away as "you did not start a route".
     * Tracked per package so a Waze parse failure is not masked by an earlier Google Maps route.
     *
     * <p>Everything runs in one app process (no {@code android:process} in the manifest), so this is
     * shared with {@link com.byd.dashcast.report.BugWizardActivity}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> sNavActivity =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A supported nav app recently posted AND we parsed it — a route is (or just was) running. */
    public static final String NAV_PARSED     = "parsed";
    /** It posted guidance but nothing parsed — a real DashCast parser bug; never gate this away. */
    public static final String NAV_PARSE_FAIL = "parse-fail";
    /** Nothing recent at all — most likely no route was ever started. */
    public static final String NAV_NONE       = "none";

    /** Records that {@code pkg} posted a guidance notification, and whether we fully parsed it. */
    private static void noteNavActivity(String pkg, boolean parsed) {
        if (pkg == null) return;
        long[] slot = sNavActivity.computeIfAbsent(pkg, k -> new long[2]);
        slot[parsed ? 1 : 0] = SystemClock.elapsedRealtime();
    }

    /** Whether {@code pkg} is the app the driver named in the wizard ("maps"/"waze"; empty = any). */
    private static boolean matchesNavKey(String navAppKey, String pkg) {
        if (navAppKey == null || navAppKey.isEmpty()) return true;
        if ("waze".equals(navAppKey)) return PKG_WAZE.equals(pkg);
        if ("maps".equals(navAppKey)) return PKG_MAPS.equals(pkg) || PKG_MAPS_REVANCED.equals(pkg);
        return true;
    }

    /** Newest {posted, parsed} pair across the packages matching {@code navAppKey}. */
    private static long[] newestFor(String navAppKey) {
        long posted = 0L, parsed = 0L;
        for (java.util.Map.Entry<String, long[]> e : sNavActivity.entrySet()) {
            if (!matchesNavKey(navAppKey, e.getKey())) continue;
            posted = Math.max(posted, e.getValue()[0]);
            parsed = Math.max(parsed, e.getValue()[1]);
        }
        return new long[] { posted, parsed };
    }

    /**
     * What we have recently seen from the nav app the driver says they use — {@link #NAV_PARSED},
     * {@link #NAV_PARSE_FAIL} or {@link #NAV_NONE}. Only {@code NAV_NONE} means "no route running".
     */
    public static String recentNavStatus(String navAppKey) {
        long now = SystemClock.elapsedRealtime();
        long[] t = newestFor(navAppKey);
        if (t[1] != 0L && now - t[1] <= NAV_RECENT_MS) return NAV_PARSED;
        if (t[0] != 0L && now - t[0] <= NAV_RECENT_MS) return NAV_PARSE_FAIL;
        return NAV_NONE;
    }

    /** Compact caption line for triage, e.g. {@code "yes (3m ago)"} / {@code "parse-fail (12s ago)"}. */
    public static String navSeenSummary(String navAppKey) {
        long now = SystemClock.elapsedRealtime();
        long[] t = newestFor(navAppKey);
        if (t[1] != 0L && now - t[1] <= NAV_RECENT_MS) return "yes (" + ago(now - t[1]) + ")";
        if (t[0] != 0L && now - t[0] <= NAV_RECENT_MS) return "parse-fail (" + ago(now - t[0]) + ")";
        long newest = Math.max(t[0], t[1]);
        // "stale" ≠ "never": tells triage a route ran, just not recently enough to explain this report.
        if (newest != 0L) return "stale (" + ago(now - newest) + ")";
        return "no";
    }

    private static String ago(long ms) {
        long s = ms / 1000L;
        return s < 90L ? (s + "s ago") : ((s / 60L) + "m ago");
    }

    // ─── Distance: "300 m", "1.2 km", "1,2 km", "500 m", "3 км" ─
    // Mirrors OpenBYD regex: \b(\d+[.,]?\d*)[\s ]*(km|км|m|м)\b
    // Handles ASCII and Cyrillic unit suffixes, optional decimal, optional NBSP.
    // IMPERIAL + "mt" added 1.6.144: the metric-only pattern meant that in a UK/US locale (Maps and
    // Waze post "in 500 ft" / "0.5 mi") NO frame of an entire route could ever parse, so the HUD arrow
    // never worked there at all — and the 1.6.143 gate then told those drivers they had not started a
    // route. Italian "300 mt" failed the same way. Alternation is longest-first so "mi"/"mt" are tried
    // before "m"; "25 min" still cannot match (the trailing \b fails on "min" for every alternative),
    // so remaining-time text is never misread as a distance.
    // PORTABILITY (2026-09) — \b replaced by explicit lookarounds in RX_DIST / RX_HOURS / RX_MINS.
    //
    // \b is defined on ASCII word chars, so a boundary that follows a non-ASCII unit token
    // (Arabic "م", Cyrillic "м") is engine-dependent. Proven with the identical pattern on two JVMs:
    // JDK 17 matched "400 م" and "300 м"; JDK 21 matched NEITHER, while ASCII "300 m" matched on
    // both. That is not a formatting nuance - the parser returns nothing, and an arrow with no
    // distance is the worst state for a driver.
    //
    // (?<![\p{L}\p{N}]) ... (?![\p{L}\p{N}]) says exactly what \b was meant to say here and
    // gives the same answer on both JVMs. It deliberately does NOT use UNICODE_CHARACTER_CLASS,
    // which would also make \d match Arabic-Indic digits directly and bypass normaliseDigits().
    // The "25 min must not parse as a distance" guarantee above is preserved: after "m" the next
    // char is "i", a letter, so the lookahead fails exactly as the trailing \b did.
    private static final Pattern RX_DIST =
            Pattern.compile("(?<![\\p{L}\\p{N}])(\\d+[.,]?\\d*)[\\s\\u00A0]*(km|км|كم|mi|ft|yd|mt|m|м|م)(?![\\p{L}\\p{N}])",
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
            Pattern.compile("(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:h|hr|hrs|hour|hours|ч|ч\\.|ساعة|س)(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_MINS =
            Pattern.compile("(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:min|mins|мин|دقيقة|د)(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE);

    // ─── Arrival wall-clock ETA — "· 14:32", "2:05 pm" (OEM EXPECTED_ARRIVE_*) ─
    // Best-effort: a bare HH:MM in a nav notification is the arrival clock — remaining DURATION here
    // is always word-unit ("25 min", "1 hr"), never colon-separated. g1=hour, g2=minute, g3=am/pm.
    private static final Pattern RX_ETA_CLOCK =
            Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s*(am|pm)?\\b",
                    Pattern.CASE_INSENSITIVE);
        private static final Pattern RX_ETA_MARKER = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:eta|arriv(?:e|al|ée?|ee|o|ato|ata)|llegada|ankunft|"
                + "прибыт\\p{L}*|прибут\\p{L}*|прыбы\\p{L}*|przyjazd|varış|varis|"
                + "الوصول|келу|yetib)(?![\\p{L}\\p{N}])");

    // ─── Roundabout exit number — "3rd exit", "take the 2nd exit", "sortie 3" ─
    private static final Pattern RX_ROUNDABOUT_EXIT =
            Pattern.compile(
                    "(?:(\\d+)(?:st|nd|rd|th|er|e|ème)?[\\s\\u00A0]+(?:exit|sortie)"
                            + "|(?:exit|sortie)[\\s\\u00A0]+(\\d+))",
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
        // Roundabout — BEFORE the exit block, and this order is load-bearing.
        //
        // The lookup is first-match-wins over this array (see the loop that scans it), and a real
        // roundabout instruction almost always names an exit: "At the roundabout, take the 3rd
        // exit", "Au rond-point, prenez la 3e sortie", "Im Kreisverkehr, nehmen Sie die 3.
        // Ausfahrt". With "exit"/"sortie"/"ausfahrt" tested first, every one of those drew a
        // motorway-exit arrow on the instrument cluster instead of a roundabout — the wrong glyph,
        // in front of a driver, at the moment they need it. The same specificity rule the rest of
        // this table already documents ("most specific first", "must not shadow more specific
        // keys") simply had not been applied to this pair.
        { "roundabout",               CanBusController.ICON_ROUNDABOUT_CW_1_LAP  },
        { "rond-point",               CanBusController.ICON_ROUNDABOUT_CW_1_LAP  },
        { "kreisverkehr",             CanBusController.ICON_ROUNDABOUT_CW_1_LAP  }, // DE
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
        final Context processContext = getApplicationContext();
        appContext = processContext;
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hud-nav-writer");
            t.setDaemon(true);
            return t;
        });
        hudDispatcher = new LatestValueDispatcher<>(executor, pending -> {
            if (HudController.INSTANCE.updateNavigation(processContext, pending.data)) {
                hudDeliveryTracker.markDelivered(pending.generation);
            }
        });
    }

    @Override
    public void onDestroy() {
        clearTrackedNavigation();
        LatestValueDispatcher<PendingHudUpdate> dispatcher = hudDispatcher;
        hudDispatcher = null;
        Context ctx = appContext;
        appContext = null;
        if (dispatcher != null && ctx != null) {
            // close() drops queued guidance, waits behind any update already executing, then
            // clears the HUD before gracefully terminating the writer. The Runnable captures
            // only application Context, not this service instance.
            dispatcher.close(() -> HudController.INSTANCE.closeNavigation(ctx));
        }
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // The system (re)bound us — possibly mid-route, after a process restart or a rebind.
        // onNotificationPosted only fires on NEW posts, so an ALREADY-posted ongoing nav notification
        // stays invisible until the nav app next changes its content: the HUD would not resume, and
        // the bug reporter would wrongly conclude "no route is running". Replay what is already on
        // screen through the normal pipeline. Guarded — a listener callback must never crash.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) {
                if (sbn != null && isNavPackage(sbn.getPackageName())) {
                    onNotificationPosted(sbn);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onListenerConnected rescan failed: " + t.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        // System unbound the listener (e.g. permission revoked, system crash).
        // Close the HUD so the cluster doesn't stay frozen on the last nav state.
        clearTrackedNavigation();
        postNavClose();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Notification n = sbn.getNotification();
        if (!isNavPackage(sbn.getPackageName())) {
            maybeLogUnsupportedNav(sbn.getPackageName(), n);
            return;
        }
        if (n == null) return;

        if (!isNavigationNotification(n)) return;

        // NOTE: whether this counts as nav activity is decided LOWER DOWN, once we know if it looks
        // like guidance (a resolved maneuver or a real distance) — see the guidance-signal gate after
        // distance parsing. A nav app's NON-guidance ongoing notification (e.g. Waze's
        // CLOSE_WAZE_CHANNEL foreground/close prompt) must NOT be recorded as a nav frame.

        Bundle extras = n.extras;
        if (extras == null) return;

        String title   = charSeqToString(extras.getCharSequence("android.title"));
        String text    = charSeqToString(extras.getCharSequence("android.text"));
        String bigText = charSeqToString(extras.getCharSequence("android.bigText"));
        String subText = charSeqToString(extras.getCharSequence("android.subText"));

        // Notification-level deduplication: skip if content hasn't changed since last call.
        if (isSameNotificationContent(
            sbn.getKey(), lastNotificationKey,
            title, lastTitle, text, lastText,
            bigText, lastBigText, subText, lastSubText)) {
            // AUD-003 — but first, keep the HUD alive. This is a re-post of the very frame already
            // displayed, so the arrow up there is still correct and the staleness watchdog must not
            // read "no update" as "frozen". Only when that frame actually reached the HUD: an
            // identical re-post of something that never parsed proves nothing, and letting it
            // refresh liveness would disarm the watchdog for the case it exists for.
            if (hudDeliveryTracker.currentContentWasDelivered()) {
                HudController.INSTANCE.noteNavFrameSeen();
            }
            return;
        }
        lastTitle   = title;
        lastText    = text;
        lastBigText = bigText;
        lastSubText = subText;
        lastNotificationKey = sbn.getKey();
        // New content gets a generation before parsing. It is acknowledged only after the serial
        // writer confirms a guidance output; unparseable or failed frames remain unacknowledged.
        final long deliveryGeneration = hudDeliveryTracker.beginContent();

        // OPT-IN raw capture (Diagnostics → "Capture raw nav-notification text"): the ACTUAL text a
        // supported nav app posts, clipped — logged here for EVERY distinct notification content,
        // BEFORE the skip / guidance gates below, so notifications that FAIL to parse (the Waze case
        // we need to diagnose: no matchable distance/icon → early return) are still captured. Location
        // PII; OFF by default; deduped on content by the check just above. See KEY_NAV_RAW_CAPTURE.
        if (ClusterPrefs.isNavRawCaptureEnabled(this)) {
            AppLogger.i(TAG, "NAV RAW pkg=" + sbn.getPackageName()
                    + " title='" + clip(title) + "'"
                    + " text='" + clip(text) + "'"
                    + (bigText.isEmpty() ? "" : " big='" + clip(bigText) + "'")
                    + (subText.isEmpty() ? "" : " sub='" + clip(subText) + "'"));
        }

        // Skip noise notifications (OpenBYD MapNotificationListenerService pattern).
        // These are ongoing navigation notifications that carry no maneuver data.
        String lowerText  = text.toLowerCase(Locale.ROOT);
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        for (String skip : SKIP_STRINGS) {
            if (lowerText.contains(skip) || lowerTitle.contains(skip)) return;
        }

        // Combine title + text for pattern matching.
        // Normalise Eastern Arabic-Indic (U+0660-U+0669) and Extended (U+06F0-U+06F9) digits
        // to ASCII before anything parses this. The maneuver-keyword table below has a full
        // Arabic section, so on an Arabic head unit the ARROW resolves — while \\d in the
        // distance and time patterns is ASCII-only, so the DISTANCE never did. An arrow with
        // no distance is the worst of the three states: the driver sees a turn and no idea when.
        String combined = normaliseDigits((title + " " + text + " " + bigText).trim());
        String lower    = combined.toLowerCase(Locale.ROOT);

        if (combined.isEmpty()) return;

        // 1. Turn icon — try icon resource name first, then text; track WHICH source resolved it so
        //    the NAV PARSE diagnostic shows whether we read the direction from the small-icon
        //    resource, a text keyword, or fell back to the straight default.
        String iconResName = smallIconResName(sbn.getPackageName(), n.getSmallIcon());
        int iconId = resolveIconFromResource(sbn.getPackageName(), n.getSmallIcon());
        String iconSrc;
        if (iconId > 0) {
            iconSrc = "resource";
        } else {
            iconId = resolveIconFromText(lower);
            if (iconId > 0) {
                iconSrc = "text";
            } else {
                iconId = -1;
                iconSrc = "unresolved";
            }
        }

        // 1b. Roundabout exit number (OEM parity): a generic roundabout icon carries no exit count.
        //     If the instruction names an exit ("3rd exit" / "sortie 3"), promote it to the OEM
        //     exit-numbered icon — CCW 25-34 / CW 35-44 (exit N = base+N). Handedness comes from the
        //     resolved generic icon (CW is the parser's default); a notification doesn't expose it.
        if (iconId == CanBusController.ICON_ROUNDABOUT_CW_1_LAP
                || iconId == CanBusController.ICON_ROUNDABOUT_CCW_1_LAP) {
            int exit = parseRoundaboutExit(lower);
            if (exit > 0) {
                int base = (iconId == CanBusController.ICON_ROUNDABOUT_CCW_1_LAP) ? 24 : 34;
                iconId = base + exit;
            }
        }

        // 2. Distance to next turn — scan title then text then combined.
        int distance = parseFirstDistance(combined);

        // GUIDANCE-SIGNAL GATE: only treat this as a navigation frame if it actually looks like
        // guidance — a resolved maneuver icon OR a real forward distance (> 0). This rejects a nav
        // app's NON-guidance ongoing notifications (e.g. Waze's CLOSE_WAZE_CHANNEL foreground/close
        // prompt: unresolved icon + no distance), which otherwise (a) were pushed to the HUD as a
        // bogus "straight, 0 m" arrow and (b) recorded as a false "parse-fail" that mislabelled a
        // no-route case as a parser bug (INC-20260726-140441). Nothing recorded here ⇒ NavSeen stays
        // "no", so the reporter correctly tells the driver to start a route.
        boolean looksLikeGuidance = hasGuidanceSignal(iconId, distance);
        if (!looksLikeGuidance) return;

        // A supported nav app posted a GUIDANCE frame ⇒ a route IS running. Recorded now (before the
        // parse below can still bail) so the reporter can tell "no route" from "route running but we
        // could not fully parse it" — the latter is a real parser bug, never gated away as user error.
        noteNavActivity(sbn.getPackageName(), false);

        if (!isCompleteGuidance(iconId, distance)) {
            if (iconId <= 0) {
                if (ClusterPrefs.isNavRawCaptureEnabled(this)) {
                    Log.d(TAG, "no maneuver found in: " + combined);
                } else {
                    Log.d(TAG, "no maneuver found (len=" + combined.length()
                            + ", raw nav capture off — enable it in Diagnostics to see the text)");
                }
            } else {
                // This printed the whole notification — current road, upcoming street,
                // destination, ETA — with no gate. Preserve the opt-in privacy boundary.
                if (ClusterPrefs.isNavRawCaptureEnabled(this)) {
                    Log.d(TAG, "no distance found in: " + combined);
                } else {
                    Log.d(TAG, "no distance found (len=" + combined.length()
                            + ", raw nav capture off — enable it in Diagnostics to see the text)");
                }
            }
            return;
        }

        // 3. Road name — look for "onto X" / "sur X" pattern.
        String roadName = parseRoadName(combined);

        // 4. Remaining route (from subText or parenthetical in text).
        String routeSrc = subText.isEmpty() ? text : subText;
        Integer remainSec  = parseRemainingSeconds(routeSrc);
        Integer remainDist = parseRemainingMeters(routeSrc);

        // 5. Arrival wall-clock ETA (OEM EXPECTED_ARRIVE_* family) — best-effort from the summary
        //    line; distinct from the remaining DURATION above. A notification carries no day-code.
        int[] eta = parseEtaClock(routeSrc);
        Integer etaHour   = eta != null ? eta[0] : null;
        Integer etaMinute = eta != null ? eta[1] : null;

        HudNavigationData data = new HudNavigationData(
                iconId, distance, roadName, remainDist, remainSec, etaHour, etaMinute);

        // NAV PARSE diagnostic (raw notification → parsed icon), written to the DashCast journal so
        // every bug report shows GROUND TRUTH: what the nav app's notification actually said vs the
        // arrow we produced — the way to verify the direction mapping on real Maps/Waze notifications.
        // Deduped per distinct (icon|road) maneuver so it never floods the ~1 Hz distance ticks.
        String navKey = iconId + "|" + roadName;
        if (!navKey.equals(lastLoggedNav)) {
            lastLoggedNav = navKey;
            // The raw notification title/text/bigText and the parsed road name are location PII
            // (destination / current road / ETA / upcoming street) and this journal line flows
            // into every bug report. Log only what the icon-mapping diagnostic needs — the icon
            // source + resource name + distance — plus non-PII length/found flags.
            AppLogger.i(TAG, "NAV PARSE icon=" + iconId + " src=" + iconSrc
                    + " smallIcon='" + iconResName + "'"
                    + " titleLen=" + title.length() + " textLen=" + text.length()
                    + (bigText.isEmpty() ? "" : " bigLen=" + bigText.length())
                    + " -> dist=" + distance + " road=" + (roadName.isEmpty() ? "no" : "yes")
                    + " eta=" + (eta != null ? "yes" : "no"));
        }
        Log.d(TAG, "nav update: icon=" + iconId + " dist=" + distance
                + " road=" + (roadName.isEmpty() ? "no" : "yes")
                + " remDist=" + remainDist + " remSec=" + remainSec);

        // Fully parsed into a HudNavigationData ⇒ we understood the frame, not just received it.
        noteNavActivity(sbn.getPackageName(), true);

        // Offload the ProxyClient/CAN write off the notification dispatch thread.
        // Remember WHICH notification is driving the HUD, so the removal path can tell it apart
        // from the nav app's other ongoing notifications. See onNotificationRemoved.
        sDrivingKey = sbn.getKey();
        postNavUpdate(data, deliveryGeneration);
    }

    /** A partial frame still proves that navigation is active, but must not drive the HUD. */
    static boolean hasGuidanceSignal(int iconId, int distanceMeters) {
        return iconId > 0 || distanceMeters > 0;
    }

    /** Direction and distance must both be known before emitting driver-facing guidance. */
    static boolean isCompleteGuidance(int iconId, int distanceMeters) {
        return iconId > 0 && distanceMeters >= 0;
    }

    static boolean isSameNotificationContent(
            String key, String previousKey,
            String title, String previousTitle,
            String text, String previousText,
            String bigText, String previousBigText,
            String subText, String previousSubText) {
        return java.util.Objects.equals(key, previousKey)
                && title.equals(previousTitle)
                && text.equals(previousText)
                && bigText.equals(previousBigText)
                && subText.equals(previousSubText);
    }

    /** Same eligibility rule for posting and removal, including non-ongoing nav categories. */
    static boolean isNavigationNotification(Notification notification) {
        return notification != null
                && ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0
                    || Notification.CATEGORY_NAVIGATION.equals(notification.category));
    }

    /**
     * Key of the notification currently driving the HUD, or null when none is.
     *
     * Written only after the guidance gate has accepted a frame, so it identifies the ONE
     * notification whose disappearance means the route ended. Null is treated as "unknown, close
     * anyway": a listener that reconnects mid-route has no key yet, and failing to close a HUD is
     * worse than closing one that was already stopping.
     */
    private static volatile String sDrivingKey;

    /**
     * The removal path needs the same gate the posting path has.
     *
     * onNotificationPosted carries a GUIDANCE-SIGNAL GATE, added because a nav app's non-guidance
     * ongoing notifications — Waze's CLOSE_WAZE_CHANNEL prompt is the documented one — were being
     * pushed to the HUD as a bogus "straight, 0 m" arrow (INC-20260726-140441). This method had no
     * such gate: ANY ongoing notification from a nav package tore the HUD down. So the very prompt
     * that gate was written to reject would, on disappearing, kill live guidance mid-route.
     *
     * Rather than duplicate the gate's logic, this reuses its verdict: the key of the last
     * notification that actually passed it and reached the HUD. Only that one closing means the
     * route is over. As a side effect it also fixes the two-nav-apps case, where one app's
     * teardown used to cancel the other's guidance.
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (!isNavigationNotification(n)) return;

        String key = sbn.getKey();
        String driving = sDrivingKey;
        if (driving != null && !driving.equals(key)) {
            // A different ongoing notification from the same nav app. Not our guidance frame.
            Log.d(TAG, "nav notification removed but it was not the one driving the HUD — ignored");
            return;
        }
        Log.d(TAG, "nav notification removed → closeNavigation");
        sDrivingKey = null;
        if (replayRemainingNavigation(key)) return;
        postNavClose();
        resetNotificationIdentity();
    }

    private boolean replayRemainingNavigation(String removedKey) {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (Throwable t) {
            Log.w(TAG, "remaining navigation rescan failed: " + t.getMessage());
            return false;
        }
        for (StatusBarNotification candidate : remainingNavigationNotifications(active, removedKey)) {
            resetNotificationIdentity();
            onNotificationPosted(candidate);
            if (sDrivingKey != null) {
                Log.d(TAG, "restored remaining navigation notification after removal");
                return true;
            }
        }
        return false;
    }

    static List<StatusBarNotification> remainingNavigationNotifications(
            StatusBarNotification[] active, String removedKey) {
        java.util.ArrayList<StatusBarNotification> candidates = new java.util.ArrayList<>();
        if (active == null) return candidates;
        for (StatusBarNotification notification : active) {
                if (notification == null
                    || java.util.Objects.equals(removedKey, notification.getKey())) continue;
            if (!isNavPackage(notification.getPackageName())) continue;
            if (!isNavigationNotification(notification.getNotification())) continue;
            candidates.add(notification);
        }
        candidates.sort((left, right) -> Long.compare(right.getPostTime(), left.getPostTime()));
        return candidates;
    }

    private void resetNotificationIdentity() {
        lastTitle   = "";
        lastText    = "";
        lastBigText = "";
        lastSubText = "";
        lastNotificationKey = null;
        hudDeliveryTracker.invalidate();
    }

    private void clearTrackedNavigation() {
        sDrivingKey = null;
        resetNotificationIdentity();
    }

    // ─── HUD write dispatch (serial writer thread) ────────────────────────

    /**
     * Queue a nav update on the serial HUD writer thread. If updates arrive faster than the daemon
     * drains them, only the newest pending state survives and at most one drain task stays queued.
     */
    private void postNavUpdate(HudNavigationData data, long generation) {
        if (data == null) return;
        LatestValueDispatcher<PendingHudUpdate> dispatcher = hudDispatcher;
        if (dispatcher != null) dispatcher.submit(new PendingHudUpdate(data, generation));
    }

    /**
     * Queue a HUD close on the serial writer thread. Cancels any queued-but-unrun
     * guidance frame first so a stale update cannot re-open the HUD after removal.
     * Falls back to a synchronous close ONLY when the executor is already gone or
     * rejects (service teardown) so the cluster never freezes on the last nav state.
     */
    private void postNavClose() {
        final Context ctx = appContext;
        if (ctx == null) return;
        LatestValueDispatcher<PendingHudUpdate> dispatcher = hudDispatcher;
        if (dispatcher == null) {
            HudController.INSTANCE.closeNavigation(ctx);
            return;
        }
        if (!dispatcher.cancelPendingAndExecute(
                () -> HudController.INSTANCE.closeNavigation(ctx))) {
            HudController.INSTANCE.closeNavigation(ctx);
        }
    }

    private static final class PendingHudUpdate {
        final HudNavigationData data;
        final long generation;

        PendingHudUpdate(HudNavigationData data, long generation) {
            this.data = data;
            this.generation = generation;
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
            Resources res = mResCache.get(pkg);
            if (res == null) {
                res = createPackageContext(pkg, 0).getResources();
                mResCache.put(pkg, res);
            }
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

    /**
     * The small-icon resource entry name (e.g. {@code ic_maneuver_turn_right}) or {@code ""} — for
     * the NAV PARSE diagnostic, so on a real car we can see what the nav app's small icon actually is
     * (and whether it carries the maneuver at all, or is generic → we must rely on text parsing).
     */
    private String smallIconResName(String pkg, Icon icon) {
        if (icon == null || icon.getType() != Icon.TYPE_RESOURCE) return "";
        try {
            Resources res = mResCache.get(pkg);
            if (res == null) {
                res = createPackageContext(pkg, 0).getResources();
                mResCache.put(pkg, res);
            }
            int resId = icon.getResId();
            return resId == 0 ? "" : res.getResourceEntryName(resId);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Package-private, not private, so NavKeywordOrderTest can drive it. The order of
     * TEXT_KEYWORD_MAP is load-bearing — first match wins — and an ordering mistake there puts the
     * wrong arrow on a driver's instrument cluster with nothing anywhere to say so.
     */
    static int resolveIconFromText(String lower) {
        for (Object[] entry : TEXT_KEYWORD_MAP) {
            if (lower.contains((String) entry[0])) return (int) entry[1];
        }
        return -1;
    }

    // ─── Distance parsing ─────────────────────────────────────────────────

    /** Returns the first distance found (in metres) or -1. */
    /**
     * Maps Arabic-Indic digits onto ASCII, leaving everything else untouched.
     *
     * Two ranges, because both are in use: U+0660-U+0669 (Arabic-Indic) and U+06F0-U+06F9
     * (Extended Arabic-Indic, Persian/Urdu). Cheap enough to run on every notification — one scan
     * of a short string, allocating only when it actually finds a digit to convert.
     */
    static String normaliseDigits(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v = -1;
            if (c >= '\u0660' && c <= '\u0669') v = c - '\u0660';
            else if (c >= '\u06F0' && c <= '\u06F9') v = c - '\u06F0';
            if (v >= 0) {
                if (sb == null) sb = new StringBuilder(s);
                sb.setCharAt(i, (char) ('0' + v));
            }
        }
        return sb == null ? s : sb.toString();
    }

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
            switch (unit) {
                case "km": case "км": return Math.round(val * 1000f);
                case "mi":            return Math.round(val * 1609.344f);   // statute mile
                case "ft":            return Math.round(val * 0.3048f);
                case "yd":            return Math.round(val * 0.9144f);
                default:              return Math.round(val);               // m / м / mt
            }
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

    // ─── Arrival ETA clock ────────────────────────────────────────────────

    /** Parses an arrival wall-clock "HH:MM" (optionally am/pm) → {hour24, minute}, or null. */
    private static int[] parseEtaClock(String text) {
        if (text == null) return null;
        Matcher m = RX_ETA_CLOCK.matcher(text);
        List<EtaClockCandidate> candidates = new ArrayList<>();
        while (m.find()) {
            try {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                String ap = m.group(3);
                if (ap != null) {
                    ap = ap.toLowerCase(Locale.ROOT);
                    if (ap.equals("pm") && h < 12) h += 12;
                    else if (ap.equals("am") && h == 12) h = 0;
                }
                if (h >= 0 && h <= 23 && min >= 0 && min <= 59) {
                    candidates.add(new EtaClockCandidate(h, min, m.start(), m.end()));
                }
            } catch (NumberFormatException ignored) { }
        }
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0).value();

        EtaClockCandidate closest = null;
        int closestGap = Integer.MAX_VALUE;
        Matcher marker = RX_ETA_MARKER.matcher(text);
        while (marker.find()) {
            for (EtaClockCandidate candidate : candidates) {
                int gap = candidate.end <= marker.start()
                        ? marker.start() - candidate.end
                        : candidate.start >= marker.end()
                                ? candidate.start - marker.end() : 0;
                if (gap < closestGap) {
                    closest = candidate;
                    closestGap = gap;
                }
            }
        }
        // Summary layouts without an arrival label conventionally place ETA last. Keeping that
        // fallback preserves bare vendor formats while no longer mistaking a leading duration.
        return (closest != null ? closest : candidates.get(candidates.size() - 1)).value();
    }

    private static final class EtaClockCandidate {
        final int hour;
        final int minute;
        final int start;
        final int end;

        EtaClockCandidate(int hour, int minute, int start, int end) {
            this.hour = hour;
            this.minute = minute;
            this.start = start;
            this.end = end;
        }

        int[] value() {
            return new int[] { hour, minute };
        }
    }

    // ─── Roundabout exit ──────────────────────────────────────────────────

    /** Parses a roundabout exit number (1-10) from the instruction text, or 0 if none/out of range. */
    private static int parseRoundaboutExit(String lower) {
        if (lower == null) return 0;
        Matcher m = RX_ROUNDABOUT_EXIT.matcher(lower);
        if (!m.find()) return 0;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        try {
            int n = Integer.parseInt(g);
            return (n >= 1 && n <= 10) ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private static boolean isNavPackage(String pkg) {
        return PKG_MAPS.equals(pkg) || PKG_MAPS_REVANCED.equals(pkg) || PKG_WAZE.equals(pkg);
    }

    // Known navigation apps we do NOT support: their guidance is not exposed as a parseable
    // notification (e.g. Telenav delivers binary NaviInfo AIDL, not text). Prefix match on the pkg.
    // Public + single source of truth so the bug reporter can also probe the running-process list and
    // self-diagnose a "no arrow on HUD" report even when the unsupported nav posts NO notification at
    // all — Telenav is the archetype: it never triggers the notification-driven maybeLogUnsupportedNav
    // below, so before the presence probe such reports were mute about the real culprit
    // (INC-20260718-114114).
    public static final String[] KNOWN_UNSUPPORTED_NAV = {
        "com.telenav",              // Telenav (EU OEM nav: .app.arp / .app.isa)
        "com.sealnav",              // BYD Seal nav
        "com.autonavi", "com.amap", // AMap / AutoNavi
        "com.baidu.baidumaps",      // Baidu Maps
        "ru.yandex.yandexnavi", "ru.yandex.yandexmaps",  // Yandex
    };

    /**
     * Scans a {@code ps -A}-style process listing and returns the first running process whose name
     * matches a {@link #KNOWN_UNSUPPORTED_NAV} prefix, or {@code null} if none is running. Lets the
     * bug reporter record which unsupported OEM nav (if any) is live when a HUD "no arrow" report is
     * filed — because such navs post no notification, this presence probe is the only way the report
     * can name the culprit. Matches whole whitespace-delimited tokens so a substring can't false-hit.
     */
    public static String firstUnsupportedNavProcess(String procListing) {
        if (procListing == null) return null;
        for (String line : procListing.split("\\R")) {
            for (String tok : line.trim().split("\\s+")) {
                for (String p : KNOWN_UNSUPPORTED_NAV) {
                    if (tok.startsWith(p)) return tok;
                }
            }
        }
        return null;
    }

    /**
     * Logs (throttled, once per ~30 s per app) when a notification comes from a KNOWN unsupported
     * nav app, or any app whose notification is {@code category=navigation}. This makes a
     * "no arrow in HUD" bug report self-explanatory — DashCast only reads Google Maps / Maps
     * ReVanced / Waze notifications, so an EU Telenav user gets no arrow and, before this, no clue
     * why (field report INC-20260715-161802). Written to the DashCast journal → into every report.
     */
    private void maybeLogUnsupportedNav(String pkg, Notification n) {
        if (pkg == null || n == null) return;
        boolean navCategory = Notification.CATEGORY_NAVIGATION.equals(n.category);
        boolean knownNav = false;
        for (String p : KNOWN_UNSUPPORTED_NAV) {
            if (pkg.startsWith(p)) { knownNav = true; break; }
        }
        if (!navCategory && !knownNav) return;
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(lastUnsupportedNavPkg) && now - lastUnsupportedLogMs < 30_000L) return;
        lastUnsupportedNavPkg = pkg;
        lastUnsupportedLogMs = now;
        // Privacy: the package name is written to the DashCast journal → every bug report → Telegram.
        // For a KNOWN unsupported nav app the package comes from our own hardcoded list (below), so
        // naming it discloses nothing new and is diagnostically essential (e.g. the EU Telenav "no
        // arrow" field report). But the category=navigation branch fires for ANY installed app, so
        // logging its raw package would leak the driver's app inventory off-device. Emit a keyed,
        // per-install marker instead — enough to correlate recurring reports from this installation
        // without enabling a global package-name dictionary lookup.
        String who = knownNav ? ("app=" + pkg + " (known nav app)")
                              : ("pkgHash=" + coarsePkgMarker(pkg) + " (category=navigation)");
        AppLogger.i(TAG, "NAV UNSUPPORTED " + who
                + " — DashCast HUD nav only reads Google Maps / Maps ReVanced / Waze");
    }

    /** Per-install HMAC marker for an unknown package. Falls back to a fixed token on any failure. */
    private String coarsePkgMarker(String pkg) {
        if (pkg == null) return "?";
        try {
            return PackagePseudonymizer.marker(getOrCreatePackageMarkerKey(), pkg);
        } catch (Exception e) {
            AppLogger.w(TAG, "Package marker unavailable: " + e.getClass().getSimpleName());
            return "marker-na";
        }
    }

    /** Loads or atomically creates a non-backed-up 256-bit key scoped to this installation. */
    private synchronized byte[] getOrCreatePackageMarkerKey() throws java.io.IOException {
        if (packageMarkerKey != null) return packageMarkerKey;
        File dir = getNoBackupFilesDir();
        File keyFile = new File(dir, PACKAGE_MARKER_KEY_FILE);
        byte[] existing = readPackageMarkerKey(keyFile);
        if (existing != null) {
            packageMarkerKey = existing;
            return existing;
        }
        if (keyFile.exists() && !keyFile.delete()) {
            throw new java.io.IOException("cannot replace invalid package marker key");
        }

        byte[] generated = new byte[PACKAGE_MARKER_KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        File temp = File.createTempFile("nav_package_marker_", ".tmp", dir);
        boolean published = false;
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(generated);
                out.flush();
                out.getFD().sync();
            }
            published = temp.renameTo(keyFile);
            if (!published) {
                byte[] raced = readPackageMarkerKey(keyFile);
                if (raced == null) throw new java.io.IOException("cannot publish package marker key");
                generated = raced;
            }
        } finally {
            if (!published) temp.delete();
        }
        packageMarkerKey = generated;
        return generated;
    }

    private static byte[] readPackageMarkerKey(File keyFile) {
        if (!keyFile.isFile() || keyFile.length() != PACKAGE_MARKER_KEY_BYTES) return null;
        byte[] key = new byte[PACKAGE_MARKER_KEY_BYTES];
        try (FileInputStream in = new FileInputStream(keyFile)) {
            int offset = 0;
            while (offset < key.length) {
                int read = in.read(key, offset, key.length - offset);
                if (read < 0) return null;
                offset += read;
            }
            return in.read() == -1 ? key : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String charSeqToString(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }

    /** One-line, length-bounded form of a notification string for the NAV PARSE diagnostic. */
    private static String clip(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
