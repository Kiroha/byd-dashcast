package com.byd.dashcast.hud;

import android.app.Notification;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.byd.dashcast.CanBusController;

import java.util.Locale;
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

    private static final String PKG_MAPS          = "com.google.android.apps.maps";
    private static final String PKG_MAPS_REVANCED  = "app.revanced.android.apps.maps";
    private static final String PKG_WAZE           = "com.waze";

    // ─── Distance regex: "300 m", "1.2 km", "1,2 km", "500m" ────────────
    private static final Pattern RX_DIST_M  =
            Pattern.compile("(\\d+)\\s*m\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RX_DIST_KM =
            Pattern.compile("(\\d+[.,]\\d+)\\s*km\\b|(\\d+)\\s*km\\b", Pattern.CASE_INSENSITIVE);

    // ─── Road name: "onto X", "sur X", "on X" ────────────────────────────
    private static final Pattern RX_ROAD_ONTO =
            Pattern.compile("(?:onto|on|sur|vers)\\s+(.+?)(?:\\s+in\\s+\\d|\\s+dans\\s+\\d|$)",
                    Pattern.CASE_INSENSITIVE);

    // ─── Remaining time: "12 min", "1h 5m", "1 h 5 min" ─────────────────
    private static final Pattern RX_REMAIN_TIME =
            Pattern.compile("(\\d+)\\s*h\\s*(\\d+)\\s*(?:m|min)|(?<!\\d)(\\d+)\\s*(?:min|m)(?!\\w)",
                    Pattern.CASE_INSENSITIVE);

    // ─── Remaining distance: "3.2 km", "500 m" (for route-level info) ────
    // (reuses RX_DIST_* on the subtext / parenthetical part)

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
    };

    // ─── NotificationListenerService callbacks ────────────────────────────

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

        HudController.INSTANCE.updateNavigation(this, data);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isNavPackage(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            Log.d(TAG, "nav notification removed → closeNavigation");
            HudController.INSTANCE.closeNavigation(this);
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

    /**
     * Returns the first distance value found (in metres) or -1.
     * Prefers km over m when both appear (km is usually the step distance in longer segments).
     */
    private static int parseFirstDistance(String text) {
        // Try km first.
        Matcher m = RX_DIST_KM.matcher(text);
        if (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw != null) {
                try {
                    float km = Float.parseFloat(raw.replace(',', '.'));
                    return Math.round(km * 1000f);
                } catch (NumberFormatException ignore) {}
            }
        }
        // Fall back to metres.
        m = RX_DIST_M.matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignore) {}
        }
        return -1;
    }

    /**
     * Tries to find a "remaining" or "route-level" distance in the subtext / route summary.
     * Returns null if not found (caller passes null to HudNavigationData).
     */
    private static Integer parseRemainingMeters(String text) {
        if (text == null || text.isEmpty()) return null;
        // Look for parenthetical like "(3.2 km)" or "· 3.2 km" in the route summary.
        Matcher m = RX_DIST_KM.matcher(text);
        if (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw != null) {
                try {
                    float km = Float.parseFloat(raw.replace(',', '.'));
                    return Math.round(km * 1000f);
                } catch (NumberFormatException ignore) {}
            }
        }
        m = RX_DIST_M.matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignore) {}
        }
        return null;
    }

    /**
     * Parses a remaining time string like "12 min", "1h 5m", "1 h 05 min"
     * into total seconds. Returns null if no time found.
     */
    private static Integer parseRemainingSeconds(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = RX_REMAIN_TIME.matcher(text);
        if (!m.find()) return null;
        // Group 1/2 = hours + minutes form; group 3 = minutes-only form.
        if (m.group(1) != null && m.group(2) != null) {
            try {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                return (h * 60 + min) * 60;
            } catch (NumberFormatException ignore) {}
        } else if (m.group(3) != null) {
            try {
                return Integer.parseInt(m.group(3)) * 60;
            } catch (NumberFormatException ignore) {}
        }
        return null;
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
