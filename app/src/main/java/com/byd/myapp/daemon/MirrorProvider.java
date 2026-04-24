package com.byd.myapp.daemon;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.byd.myapp.AppLogger;

public class MirrorProvider extends ContentProvider {
    private static final String TAG = "MirrorProvider";
    
    public static Surface sTargetSurface;
    public static int sViewW, sViewH, sClusterW, sClusterH, sLayerStack;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String authority, @NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if ("getSurface".equals(method)) {
            AppLogger.i(TAG, "Daemon is requesting the Surface via ContentProvider.");
            Bundle b = new Bundle();
            b.putBinder("surface_binder", new android.os.Binder() {
                @Override
                protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                    if (code == 1) {
                        reply.writeNoException();
                        if (sTargetSurface != null && sTargetSurface.isValid()) {
                            reply.writeInt(1);
                            sTargetSurface.writeToParcel(reply, 0);
                        } else {
                            reply.writeInt(0);
                        }
                        reply.writeInt(sViewW);
                        reply.writeInt(sViewH);
                        reply.writeInt(sClusterW);
                        reply.writeInt(sClusterH);
                        reply.writeInt(sLayerStack);
                        return true;
                    }
                    return false;
                }
            });
            return b;
        }
        return super.call(authority, method, arg, extras);
    }

    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) { return null; }
    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
