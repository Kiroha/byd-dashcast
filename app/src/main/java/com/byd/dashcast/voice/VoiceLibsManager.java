package com.byd.dashcast.voice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.byd.dashcast.AppLogger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * On-demand download and loading of voice native libraries.
 *
 * The following .so files are excluded from the APK to reduce its size (~44 MB saved):
 *   - libonnxruntime.so       (ONNX Runtime core)
 *   - libonnxruntime4j_jni.so (ONNX JNI bridge)
 *   - libjnidispatch.so       (JNA runtime, required by Vosk)
 *   - libvosk.so              (Vosk ASR)
 *
 * They are downloaded once into {@code getFilesDir()/voice_libs/} when the user
 * first enables voice recognition, then reused on subsequent launches.
 *
 * <p><b>Load order invariant</b>: {@link #ensureLoaded} must be called before
 * the first access to {@code OrtEnvironment} or any Vosk class. Because Android
 * tracks loaded libs by base name, a {@code System.load(absolutePath)} call for
 * "libfoo.so" satisfies a subsequent {@code System.loadLibrary("foo")} — so
 * loading before OrtEnvironment's static initialiser runs is sufficient.
 *
 * <p><b>Hosting</b>: upload {@code voice_native_libs_arm64.zip} (produced by
 * {@code scripts/prepare_voice_libs.sh}) to a GitHub release at
 * {@link #LIBS_ZIP_URL}. Update {@link #VERSION_TAG} when libs change so that
 * devices re-download automatically.
 */
public final class VoiceLibsManager {

    private static final String TAG = "VoiceLibsManager";

    // ─── Configuration ─────────────────────────────────────────────────────
    // Replace this URL with the GitHub release asset URL after running
    // scripts/prepare_voice_libs.sh and uploading the zip.
    public static final String LIBS_ZIP_URL =
            "https://github.com/Kiroha/byd-dashcast/releases/download/voice-libs-v1/voice_native_libs_arm64.zip";

    // Approximate zip download size (compressed .so files). Used for:
    //   - HTTP Range fallback total when server doesn't repeat Content-Length on 206
    //   - disk space pre-check (together with LIBS_EXTRACTED_BYTES)
    private static final long LIBS_ZIP_BYTES = 10_000_000L;        // ~8-10 MB compressed
    // Approximate total of extracted .so files (disk space pre-check).
    private static final long LIBS_EXTRACTED_BYTES = 27_000_000L;  // ~25 MB uncompressed

    // Bump this when the zip changes (new lib versions, etc.) — triggers re-download.
    private static final String VERSION_TAG = "onnx1171_vosk0347_v1";

    // ─── Broadcast contract ─────────────────────────────────────────────────
    public static final String ACTION_LIBS            = "com.byd.dashcast.voice.LIBS";
    /** int 0–100, or -1 for indeterminate (unzipping) */
    public static final String EXTRA_LIBS_PERCENT     = "libs_pct";
    /** int — MB downloaded so far */
    public static final String EXTRA_LIBS_MB_DONE     = "libs_mb";
    /** int — total MB expected */
    public static final String EXTRA_LIBS_MB_TOTAL    = "libs_mb_total";
    /** String — error message if download/load failed */
    public static final String EXTRA_LIBS_ERROR       = "libs_error";
    /** boolean — true once all libs are loaded and ready */
    public static final String EXTRA_LIBS_READY       = "libs_ready";

    // ─── Native lib names (order matters for loading) ──────────────────────
    private static final String[] LIB_LOAD_ORDER = {
            "libonnxruntime.so",        // core — no app-level deps
            "libonnxruntime4j_jni.so",  // JNI bridge — depends on libonnxruntime
            "libjnidispatch.so",        // JNA runtime
            "libvosk.so",               // Vosk ASR
    };

    // ─── State ─────────────────────────────────────────────────────────────
    private static volatile boolean sLoaded = false;
    private static final Object sLock = new Object();
    @SuppressLint("StaticFieldLeak") // application context, set once, safe
    private static Context sAppCtx;

    private VoiceLibsManager() {}

    /**
     * Ensures all voice native libs are on disk and loaded into the JVM.
     * Blocks the calling thread until ready; downloads if needed (~25 MB, one time).
     * Safe to call multiple times — no-op once loaded.
     *
     * @throws Exception if download fails or a lib cannot be loaded.
     *                   The exception message is human-readable for display in the UI.
     */
    public static void ensureLoaded(Context ctx) throws Exception {
        if (sLoaded) return;
        synchronized (sLock) {
            if (sLoaded) return;
            sAppCtx = ctx.getApplicationContext();
            File libDir = getLibDir(sAppCtx);
            libDir.mkdirs();

            if (!areLibsPresent(libDir)) {
                AppLogger.i(TAG, "Voice libs missing or outdated — downloading");
                downloadLibsZip(sAppCtx, libDir);
            } else {
                AppLogger.i(TAG, "Voice libs found at " + libDir.getAbsolutePath());
            }

            loadLibsInOrder(libDir);
            sLoaded = true;
            broadcastReady(sAppCtx);
            AppLogger.i(TAG, "Voice libs loaded");
        }
    }

    /** True once all libs have been loaded into the JVM. */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /**
     * True if the libs are already on disk (download not needed).
     * Does NOT imply they are loaded into the JVM.
     */
    public static boolean isDownloaded(Context ctx) {
        return areLibsPresent(getLibDir(ctx.getApplicationContext()));
    }

    /** Deletes downloaded lib files (forces re-download on next voice enable). */
    public static void deleteLibs(Context ctx) {
        File libDir = getLibDir(ctx.getApplicationContext());
        if (!libDir.exists()) return;
        File[] files = libDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        AppLogger.i(TAG, "Voice libs deleted from " + libDir.getAbsolutePath());
    }

    // ─── Internals ──────────────────────────────────────────────────────────

    private static File getLibDir(Context ctx) {
        return new File(ctx.getFilesDir(), "voice_libs");
    }

    private static boolean areLibsPresent(File libDir) {
        // Version marker must exist and match — if not, re-download
        File marker = new File(libDir, ".version");
        if (!marker.exists()) return false;
        try {
            String tag = new String(readFileBytes(marker)).trim();
            if (!VERSION_TAG.equals(tag)) {
                AppLogger.i(TAG, "Lib version mismatch (" + tag + " vs " + VERSION_TAG + ") — will re-download");
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        for (String name : LIB_LOAD_ORDER) {
            if (!new File(libDir, name).exists()) return false;
        }
        return true;
    }

    private static byte[] readFileBytes(File f) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = fis.read(buf);
            return java.util.Arrays.copyOf(buf, n);
        }
    }

    private static void downloadLibsZip(Context ctx, File libDir) throws Exception {
        File tmpZip = new File(libDir, "voice_libs.download.tmp");
        long alreadyDone = tmpZip.exists() ? tmpZip.length() : 0;

        // Disk space check: need zip download + extracted files
        long freeBytes = libDir.getFreeSpace();
        long needed = Math.max(0, LIBS_ZIP_BYTES - alreadyDone) + LIBS_EXTRACTED_BYTES;
        if (freeBytes < needed) {
            throw new IOException("Espace insuffisant : besoin de "
                    + (needed / 1024 / 1024) + " Mo, disponible : "
                    + (freeBytes / 1024 / 1024) + " Mo");
        }

        // ── Download with HTTP Range resume ──────────────────────────────────
        AppLogger.i(TAG, "Downloading voice libs from " + LIBS_ZIP_URL
                + " (resume at " + (alreadyDone / 1024 / 1024) + " Mo)");
        URL url = new URL(LIBS_ZIP_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(0);
        if (alreadyDone > 0) conn.setRequestProperty("Range", "bytes=" + alreadyDone + "-");
        conn.connect();

        int code = conn.getResponseCode();
        boolean resumed = (code == 206);
        if (code != HttpURLConnection.HTTP_OK && !resumed) {
            conn.disconnect();
            throw new IOException("Téléchargement échoué — HTTP " + code);
        }
        if (!resumed) alreadyDone = 0;
        // For 206 (partial), use LIBS_ZIP_BYTES as fallback since Content-Length
        // reflects only the partial range. For 200, use the actual Content-Length.
        final long totalBytes = resumed ? LIBS_ZIP_BYTES : conn.getContentLengthLong();

        try (FileOutputStream fos = new FileOutputStream(tmpZip, resumed);
             BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 65_536)) {
            byte[] buf = new byte[65_536];
            int n;
            long downloaded = alreadyDone;
            int lastPct = -1;
            while ((n = bis.read(buf)) != -1) {
                fos.write(buf, 0, n);
                downloaded += n;
                int pct = totalBytes > 0 ? (int) (downloaded * 100L / totalBytes) : 0;
                if (pct != lastPct) {
                    lastPct = pct;
                    broadcastProgress(ctx, pct,
                            (int) (downloaded / 1024 / 1024),
                            (int) (totalBytes  / 1024 / 1024));
                }
            }
        } finally {
            conn.disconnect();
        }

        // ── Unzip ─────────────────────────────────────────────────────────────
        AppLogger.i(TAG, "Extracting voice libs zip");
        broadcastProgress(ctx, -1, 0, (int) (LIBS_ZIP_BYTES / 1024 / 1024));
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(tmpZip), 65_536))) {
            ZipEntry entry;
            byte[] buf = new byte[65_536];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                // Only extract files that match our expected lib names (strip any subdir)
                String name = new File(entry.getName()).getName();
                File out = new File(libDir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                }
                zis.closeEntry();
            }
        }
        tmpZip.delete();

        // Write version marker
        try (FileOutputStream fos = new FileOutputStream(new File(libDir, ".version"))) {
            fos.write(VERSION_TAG.getBytes());
        }
        AppLogger.i(TAG, "Voice libs extracted to " + libDir.getAbsolutePath());
    }

    private static void loadLibsInOrder(File libDir) throws UnsatisfiedLinkError {
        for (String name : LIB_LOAD_ORDER) {
            File lib = new File(libDir, name);
            if (!lib.exists()) {
                throw new UnsatisfiedLinkError("Lib manquante après extraction : " + name);
            }
            AppLogger.d(TAG, "Loading " + lib.getAbsolutePath());
            System.load(lib.getAbsolutePath());
        }
    }

    // ─── Broadcasts ────────────────────────────────────────────────────────

    private static void broadcastProgress(Context ctx, int pct, int mbDone, int mbTotal) {
        Intent i = new Intent(ACTION_LIBS);
        i.putExtra(EXTRA_LIBS_PERCENT,  pct);
        i.putExtra(EXTRA_LIBS_MB_DONE,  mbDone);
        i.putExtra(EXTRA_LIBS_MB_TOTAL, mbTotal);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
    }

    private static void broadcastReady(Context ctx) {
        Intent i = new Intent(ACTION_LIBS);
        i.putExtra(EXTRA_LIBS_READY, true);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
    }

    public static void broadcastError(Context ctx, String msg) {
        Intent i = new Intent(ACTION_LIBS);
        i.putExtra(EXTRA_LIBS_ERROR, msg == null ? "erreur inconnue" : msg);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
    }
}
