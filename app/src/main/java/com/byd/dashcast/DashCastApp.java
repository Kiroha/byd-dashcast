package com.byd.dashcast;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

import com.byd.dashcast.platform.Platform;

public class DashCastApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // appcompat:1.1.0 defaults to MODE_NIGHT_UNSPECIFIED (= always light).
        // Explicitly follow the system dark/light setting so DayNight theme works.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        // Initialise platform detection once. Reads ro.product.name, Build.* etc.
        // Snapshot is process-wide and immutable. The DiLink-5 override (auto /
        // force-on / force-off) is read from SharedPreferences on demand.
        Platform p = Platform.get();
        AppLogger.i("Platform",
                "product=" + p.rawProductName()
                + " model="   + p.rawModel()
                + " api="     + p.androidApi()
                + " autoDiLink5=" + p.isAutoDetectedDiLink5()
                + " effectiveDiLink5=" + p.isDiLink5(this));
    }
}
