package com.byd.myapp;

import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AppLogger — journal de bord singleton.
 *
 * Capture tous les événements clés de l'app avec timestamp.
 * Partager via Intent.ACTION_SEND (email, WhatsApp, Drive…).
 * Utilisé pour diagnostiquer sans câble ADB lors des tests sur véhicule.
 */
public class AppLogger {

    private static final int MAX_CHARS = 80_000;

    private static final StringBuilder sBuffer = new StringBuilder();
    private static final SimpleDateFormat sFmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private AppLogger() {}

    public static synchronized void log(String tag, String msg) {
        sBuffer.append("[").append(sFmt.format(new Date())).append("] ")
               .append(tag).append(": ").append(msg).append("\n");
        // Trim si trop volumineux — garder les 2/3 récents
        if (sBuffer.length() > MAX_CHARS) {
            int cut = sBuffer.indexOf("\n", sBuffer.length() - (MAX_CHARS * 2 / 3));
            if (cut > 0) sBuffer.delete(0, cut + 1);
        }
    }

    public static synchronized String get() {
        return sBuffer.toString();
    }

    public static synchronized void clear() {
        sBuffer.setLength(0);
    }

    /**
     * Ouvre le sélecteur de partage Android avec le contenu du journal.
     */
    public static void share(Context context) {
        String content = get();
        if (content.isEmpty()) content = "(journal vide)";

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "MyBYDApp — Journal de bord");
        intent.putExtra(Intent.EXTRA_TEXT, content);
        context.startActivity(Intent.createChooser(intent, "Partager le journal…"));
    }

    /**
     * Partage le journal combiné avec un rapport texte additionnel.
     */
    public static void shareWithReport(Context context, String reportText) {
        String log = get();
        String combined = reportText
                + "\n\n════════════════════════════════════\n"
                + "JOURNAL DE BORD\n"
                + "════════════════════════════════════\n"
                + (log.isEmpty() ? "(journal vide)" : log);

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "MyBYDApp — Rapport + Journal");
        intent.putExtra(Intent.EXTRA_TEXT, combined);
        context.startActivity(Intent.createChooser(intent, "Partager le rapport…"));
    }
}
