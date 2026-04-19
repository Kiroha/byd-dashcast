package com.xdja.containerservice;

import android.view.Surface;

/**
 * STUB — réplique fidèle de la classe `com.xdja.containerservice.QtDisplayInfo`
 * de la ROM (cf. decompiled/containerservice/sources/com/xdja/containerservice/
 * QtDisplayInfo.java).
 *
 * Indispensable car le binding JNI de `libxdjacontainerservice_jni.so` cherche
 * EXACTEMENT cette classe + ces champs (par leur nom et leur type) pour
 * remplir l'objet retourné par `getQtProjectionDispInfoNative`.
 *
 * Champs identiques à la ROM, dans le même ordre. Les méthodes `release()` et
 * `isValid()` sont des copies de la ROM (utiles si on chaîne le code BYD).
 */
public class QtDisplayInfo {
    public int height;
    public String name;
    public Surface surface;
    public int width;

    public void release() {
        Surface s = this.surface;
        if (s != null) {
            s.release();
        }
        this.surface = null;
        this.name = null;
    }

    public boolean isValid() {
        Surface s = this.surface;
        return s != null && s.isValid();
    }

    @Override
    public String toString() {
        return "QtDisplayInfo: surface=" + surface + ", name=" + name
                + ", width=" + width + ", height=" + height;
    }
}
