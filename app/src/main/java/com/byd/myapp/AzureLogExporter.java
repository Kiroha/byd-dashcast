package com.byd.myapp;

import android.os.Build;
import android.util.Base64;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AzureLogExporter — exporte les entrées AppLogger vers Azure Log Analytics
 * via l'API HTTP Data Collector.
 *
 * Référence : https://learn.microsoft.com/fr-fr/azure/azure-monitor/logs/data-collector-api
 *
 * Destination : workspace law-byd-app (francecentral)
 *   Workspace ID : BuildConfig.AZURE_WORKSPACE_ID  (local.properties)
 *   Clé primaire  : BuildConfig.AZURE_PRIMARY_KEY   (local.properties)
 *
 * Table créée automatiquement dans Log Analytics : BYDAppLog_CL
 *
 * Colonnes disponibles pour les requêtes KQL :
 *   TimeGenerated, Level_s, Tag_s, Message_s, Thread_s,
 *   DeviceModel_s, AppVersion_s
 *
 * Authentification : HMAC-SHA256 (SharedKey)
 *   Authorization: SharedKey {workspaceId}:{base64(HMAC(stringToSign, base64decode(key)))}
 *
 * ⚠️  À appeler depuis un thread background UNIQUEMENT (pas le main thread).
 *    Utiliser export(callback) qui gère le thread automatiquement.
 */
public class AzureLogExporter {

    private static final String TAG        = "AzureLogExporter";
    private static final String LOG_TYPE   = "BYDAppLog";          // Table = BYDAppLog_CL
    private static final String API_VER    = "2016-04-01";

    /** Résultat d'un export */
    public interface ExportCallback {
        void onSuccess(int entriesCount, int httpStatus);
        void onError(String message);
    }

    // ── Point d'entrée public ─────────────────────────────────────────────────

    /**
     * Exporte toutes les entrées du buffer AppLogger vers Azure Log Analytics.
     * Lance un thread background et appelle le callback sur n'importe quel thread.
     */
    public static void export(final ExportCallback callback) {
        final String workspaceId = BuildConfig.AZURE_WORKSPACE_ID;
        final String primaryKey  = BuildConfig.AZURE_PRIMARY_KEY;

        if (workspaceId.isEmpty() || primaryKey.isEmpty()) {
            callback.onError("Azure non configuré (AZURE_WORKSPACE_ID / AZURE_PRIMARY_KEY vides)");
            return;
        }

        final List<AppLogger.Entry> entries = AppLogger.getEntries();
        if (entries.isEmpty()) {
            callback.onSuccess(0, 200);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = buildJsonBody(entries);
                    int status  = postToAzure(workspaceId, primaryKey, body);

                    if (status == 200 || status == 201 || status == 202) {
                        AppLogger.i(TAG, "Export Azure OK — " + entries.size()
                                + " entrées → HTTP " + status);
                        callback.onSuccess(entries.size(), status);
                    } else {
                        String msg = "HTTP " + status + " — vérifier clé/workspaceId";
                        AppLogger.w(TAG, "Export Azure : " + msg);
                        callback.onError(msg);
                    }
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    AppLogger.e(TAG, "Export Azure ERREUR : " + msg);
                    callback.onError(msg);
                }
            }
        }, "azure-export-thread").start();
    }

    // ── Construction du JSON ──────────────────────────────────────────────────

    private static String buildJsonBody(List<AppLogger.Entry> entries) {
        // SimpleDateFormat est instancié localement (non thread-safe si statique — deux exports simultanés
        // corrompraient mutuellement le formatage des dates).
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        String deviceModel  = Build.MANUFACTURER + " " + Build.MODEL;
        String appVersion   = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            AppLogger.Entry e = entries.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            appendField(sb, "TimeGenerated",  iso.format(new Date(e.timestamp)),  true);
            appendField(sb, "Level",          e.level.name(),                          false);
            appendField(sb, "Tag",            e.tag,                                   false);
            appendField(sb, "Message",        e.message,                               false);
            appendField(sb, "Thread",         e.threadName,                            false);
            appendField(sb, "DeviceModel",    deviceModel,                             false);
            appendField(sb, "AppVersion",     appVersion,                              false);
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void appendField(StringBuilder sb,
                                    String key, String value, boolean first) {
        if (!first) sb.append(",");
        sb.append("\"").append(key).append("\":\"")
          .append(value.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "")
                       .replace("\t", "\\t")
                       .replace("\b", "\\b")
                       .replace("\f", "\\f"))
          .append("\"");
    }

    // ── Appel HTTP ────────────────────────────────────────────────────────────

    private static int postToAzure(String workspaceId,
                                   String primaryKey,
                                   String body) throws Exception {

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        // Date au format RFC 1123
        SimpleDateFormat rfc = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        rfc.setTimeZone(TimeZone.getTimeZone("UTC"));
        String dateStr = rfc.format(new Date());

        // Chaîne à signer
        String stringToSign = "POST\n"
                + bodyBytes.length + "\n"
                + "application/json\n"
                + dateStr + "\n"
                + "/api/logs";

        // HMAC-SHA256
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                Base64.decode(primaryKey, Base64.DEFAULT), "HmacSHA256"));
        String signature = Base64.encodeToString(
                mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP);

        String auth = "SharedKey " + workspaceId + ":" + signature;

        // POST
        URL url = new URL("https://" + workspaceId
                + ".ods.opinsights.azure.com/api/logs?api-version=" + API_VER);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",   "application/json");
        conn.setRequestProperty("Log-Type",        LOG_TYPE);
        conn.setRequestProperty("x-ms-date",       dateStr);
        conn.setRequestProperty("Authorization",   auth);
        conn.setRequestProperty("time-generated-field", "TimeGenerated");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        // conn.disconnect() dans finally pour garantir la libération du socket même si
        // getResponseCode() lève une exception (timeout, reset serveur…).
        int status;
        try {
            status = conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
        return status;
    }
}
