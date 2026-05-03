package com.xdja.containerservice;

import android.util.Log;
import java.io.File;

/**
 * EXACT bypass wrapper for libxdjacontainerservice_jni.so
 * Allows us to hijack the Qt surface pointer of DiLink directly bypassing ownership limits.
 */
public class ContainerService {
    private static final String TAG = "JNI_BYPASS";
    public static boolean isLoaded = false;

    private static native QtDisplayInfo[] getQtProjectionDispInfoArrayNative();
    private static native QtDisplayInfo getQtProjectionDispInfoNative(int i);

    public static void ensureLoaded() {
        if (isLoaded) return;
        
        try {
            // First attempt: direct load if added to APK (unlikely for system lib)
            System.loadLibrary("xdjacontainerservice_jni");
            isLoaded = true;
            Log.i(TAG, "Loaded via loadLibrary");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "Standard loadLibrary failed: " + t.getMessage());
        }

        // Second attempt: brutal system paths (Car ROMs put it there)
        String[] paths = {
            "/system/lib64/libxdjacontainerservice_jni.so",
            "/system/lib/libxdjacontainerservice_jni.so",
            "/vendor/lib64/libxdjacontainerservice_jni.so",
            "/vendor/lib/libxdjacontainerservice_jni.so"
        };
        
        for (String path : paths) {
            if (new File(path).exists()) {
                try {
                    System.load(path);
                    isLoaded = true;
                    Log.i(TAG, "Loaded explicit path: " + path);
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "Failed loading explicit path " + path + ": " + t.getMessage());
                }
            }
        }
        Log.e(TAG, "Could not load libxdjacontainerservice_jni.so anywhere.");
    }

    public static QtDisplayInfo getQtProjectionDispInfo(int id) {
        if (!isLoaded) ensureLoaded();
        try {
            return getQtProjectionDispInfoNative(id);
        } catch (Throwable t) {
            Log.e(TAG, "Exception during getQtProjectionDispInfoNative(" + id + "): " + t.getMessage());
            return null;
        }
    }

    public static QtDisplayInfo[] getQtProjectionDispInfoArray() {
        if (!isLoaded) ensureLoaded();
        try {
            return getQtProjectionDispInfoArrayNative();
        } catch (Throwable t) {
            Log.e(TAG, "Exception during getQtProjectionDispInfoArrayNative: " + t.getMessage());
            return null;
        }
    }
}
