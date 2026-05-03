package com.xdja.containerservice;

import android.view.Surface;

/**
 * EXACT copy of BYD's QtDisplayInfo.
 * Required so that the JNI mapping of libxdjacontainerservice_jni.so succeeds.
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
        return "QtDisplayInfo: surface=" + this.surface + ", name=" + this.name + ", w=" + this.width + ", h=" + this.height;
    }
}
