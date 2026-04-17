package com.byd.myapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AppLogger — journal de bord structuré.
 *
 * Niveaux : DEBUG / INFO / WARN / ERROR (avec couleur dans LogActivity).
 * Chaque entrée capture : timestamp, niveau, tag, message, nom du thread.
 * Helpers timing : startTiming() / endTiming() pour mesurer les durées.
 * Backward-compat : log(tag, msg) existant → INFO.
 *
 * Thread-safe via CopyOnWriteArrayList (lecture sans lock, écriture copiée).
 * Buffer circulaire : MAX_ENTRIES = 3000 entrées.
 */
public class AppLogger {

    // ── Niveau de log ─────────────────────────────────────────────────────────
    public enum Level { DEBUG, INFO, WARN, ERROR }

    // ── Entrée structurée ─────────────────────────────────────────────────────
    public static class Entry {
        public final long   timestamp;
        public final Level  level;
        public final String tag;
        public final String message;
        public final String threadName;

        Entry(Level level, String tag, String message) {
            this.timestamp  = System.currentTimeMillis();
            this.level      = level;
            this.tag        = tag;
            this.message    = message;
            this.threadName = Thread.currentThread().getName();
        }
    }

    // ── Buffer circulaire ─────────────────────────────────────────────────────
    private static final int MAX_ENTRIES = 3000;
    private static final List<Entry> sEntries = new CopyOnWriteArrayList<>();

    private AppLogger() {}

    // ── Helper interne ────────────────────────────────────────────────────────

    /** Ajoute une entrée dans le buffer et taille si nécessaire (opération unique sur COWAL). */
    private static void addEntry(Level level, String tag, String msg) {
        sEntries.add(new Entry(level, tag, msg));
        int excess = sEntries.size() - MAX_ENTRIES;
        if (excess > 0) {
            sEntries.subList(0, excess).clear();
        }
    }

    // ── Méthodes principales ──────────────────────────────────────────────────

    public static void log(Level level, String tag, String msg) {
        addEntry(level, tag, msg);
        // Miroir logcat
        switch (level) {
            case DEBUG: Log.d(tag, msg); break;
            case INFO:  Log.i(tag, msg); break;
            case WARN:  Log.w(tag, msg); break;
            case ERROR: Log.e(tag, msg); break;
        }
    }

    /** Backward-compatible : log(tag, msg) → INFO */
    public static void log(String tag, String msg) { log(Level.INFO, tag, msg); }

    public static void d(String tag, String msg) { log(Level.DEBUG, tag, msg); }
    public static void i(String tag, String msg) { log(Level.INFO,  tag, msg); }
    public static void w(String tag, String msg) { log(Level.WARN,  tag, msg); }
    public static void e(String tag, String msg) { log(Level.ERROR, tag, msg); }

    /** Variante avec Throwable — stocke le message + type d'exception dans le buffer,
     *  délègue la stacktrace complète à Log.e() (visible dans logcat/ADB). */
    public static void e(String tag, String msg, Throwable t) {
        String full = t != null
                ? msg + " [" + t.getClass().getSimpleName() + ": " + t.getMessage() + "]"
                : msg;
        addEntry(Level.ERROR, tag, full);
        Log.e(tag, msg, t); // stacktrace complète dans logcat
    }

    public static void w(String tag, String msg, Throwable t) {
        String full = t != null
                ? msg + " [" + t.getClass().getSimpleName() + ": " + t.getMessage() + "]"
                : msg;
        addEntry(Level.WARN, tag, full);
        Log.w(tag, msg, t);
    }

    // ── Helpers timing ────────────────────────────────────────────────────────

    /** Démarre un chrono. À passer à endTiming(). */
    public static long startTiming() {
        return System.currentTimeMillis();
    }

    /** Logue "[tag] msg (42 ms)" au niveau DEBUG. */
    public static void endTiming(String tag, long start, String msg) {
        long ms = System.currentTimeMillis() - start;
        d(tag, msg + " (" + ms + " ms)");
    }

    // ── Helper lifecycle ──────────────────────────────────────────────────────

    /** Log d'un événement lifecycle (DEBUG) avec nom de l'activity + thread. */
    public static void lifecycle(String className, String event) {
        d("Lifecycle",
          className + " → " + event + "  [" + Thread.currentThread().getName() + "]");
    }

    // ── Accès au buffer ───────────────────────────────────────────────────────

    /** Retourne le nombre d'entrées dans le buffer sans allouer de copie. */
    public static int getEntriesCount() {
        return sEntries.size();
    }

    /** Retourne une copie immuable du buffer (pour LogActivity). */
    public static List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(sEntries));
    }

    /** Retourne le buffer complet en String formatée (pour partage texte). */
    public static String get() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        for (Entry e : sEntries) {
            sb.append("[").append(fmt.format(new Date(e.timestamp))).append("]")
              .append("[").append(e.level.name()).append("]")
              .append("[").append(e.tag).append("] ")
              .append(e.message).append("\n");
        }
        return sb.toString();
    }

    public static void clear() {
        sEntries.clear();
    }

    // ── Sauvegarde fichier ────────────────────────────────────────────────────

    /**
     * Écrit le journal dans un fichier .log horodaté dans getExternalFilesDir().
     * Récupérable via : adb pull /sdcard/Android/data/com.byd.myapp/files/
     * @return le File créé, ou null en cas d'erreur
     */
    public static File saveToFile(Context context) {
        String filename = "byd_log_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                + ".log";
        File outDir = context.getExternalFilesDir(null);
        if (outDir == null) outDir = context.getFilesDir();
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, filename);
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(get());
        } catch (IOException ex) {
            Log.e("AppLogger", "saveToFile échec", ex);
            return null;
        }
        return outFile;
    }

    // ── Partage ───────────────────────────────────────────────────────────────

    /**
     * Sauvegarde le journal dans un fichier .log puis ouvre le share chooser
     * avec le fichier en pièce jointe (content:// via FileProvider).
     * Fallback texte brut si l'écriture échoue.
     */
    public static void share(Context context) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_SUBJECT, "MyBYDApp — Journal de bord");
        File logFile = saveToFile(context);
        if (logFile != null) {
            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", logFile);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Fallback : partage texte brut
            String content = get();
            if (content.isEmpty()) content = "(journal vide)";
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, content);
        }
        context.startActivity(Intent.createChooser(intent, "Partager le journal…"));
    }

    public static void shareWithReport(Context context, String reportText) {
        String combined = reportText
                + "\n\n════════════════════════════════════\n"
                + "JOURNAL DE BORD\n"
                + "════════════════════════════════════\n"
                + get();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "MyBYDApp — Rapport + Journal");
        intent.putExtra(Intent.EXTRA_TEXT, combined);
        context.startActivity(Intent.createChooser(intent, "Partager le rapport…"));
    }
}
