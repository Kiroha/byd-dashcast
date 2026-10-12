package com.byd.dashcast.webviewprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** External test APK only: executes the release's exact HTML in the installed system WebView. */
public final class SatelliteWebViewProbeActivity extends Activity {
    private static final String TAG = "SatelliteWebViewProbe";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView view;
    private boolean ready;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "WEBVIEW_VERSION=" + WebView.getCurrentWebViewPackage().versionName);
        view = new WebView(this);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        view.addJavascriptInterface(new Bridge(), "DashCastVideo");
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                // Never print arbitrary console text, URLs or signalling data.
                if (message.message().contains("SyntaxError")) Log.i(TAG, "BROWSER_SCRIPT_SYNTAX_ERROR");
                return true;
            }
        });
        setContentView(view);
        try (InputStream input = getAssets().open("satellite_receiver.html")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = input.read(buffer)) >= 0;) bytes.write(buffer, 0, n);
            String html = new String(bytes.toByteArray(), "UTF-8").replace("__WAITING__", "Waiting for test video");
            view.loadDataWithBaseURL("https://satellite.dashcast.invalid/", html, "text/html", "UTF-8", null);
        } catch (Exception ignored) { Log.i(TAG, "BROWSER_PROBE_LOAD_FAILED"); }
        main.postDelayed(() -> {
            Log.i(TAG, ready ? "BROWSER_BOOTSTRAP_READY" : "BROWSER_BOOTSTRAP_NOT_READY");
            view.evaluateJavascript("(function(){var r=document.getElementById('status').getBoundingClientRect();"
                + "return r.width>0&&r.height>0&&r.top>=0&&r.bottom<=innerHeight+1;})()", value -> {
                    Log.i(TAG, "true".equals(value) ? "BROWSER_WAITING_VISIBLE" : "BROWSER_WAITING_OFFSCREEN");
                    finish();
                });
        }, 8000);
    }

    public final class Bridge {
        @JavascriptInterface public void ready(boolean supported) { main.post(() -> ready = supported); }
        @JavascriptInterface public void failed(String code) {
            if ("script_error".equals(code)) Log.i(TAG, "BROWSER_SCRIPT_ERROR");
        }
        @JavascriptInterface public void send(String session, String message) { /* No sender in bootstrap check. */ }
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        view.removeJavascriptInterface("DashCastVideo");
        view.destroy();
        super.onDestroy();
    }
}
