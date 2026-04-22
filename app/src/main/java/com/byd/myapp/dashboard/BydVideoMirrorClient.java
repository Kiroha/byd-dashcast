package com.byd.myapp.dashboard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import com.byd.myapp.AppLogger;

public class BydVideoMirrorClient {
    private static final String TAG = "BydVideoMirrorClient";
    private static final String ACTION_START_MIRROR = "com.byd.intent.action.START_CLUSTER_MIRROR";
    private static final String BINDER_INTERFACE_TOKEN = "byd_cluster_video_service";
    
    private Context mContext;
    private Surface mDashboardSurface;
    private Rect mSrcRect;
    private Rect mDstRect;

    private final IBinder mSystemBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            AppLogger.i(TAG, "onTransact appelé avec le code " + code);
            if (code == 2) {
                try {
                    data.enforceInterface(BINDER_INTERFACE_TOKEN);
                    int unused = data.readInt();
                    
                    Surface clusterSurface = null;
                    if (data.readInt() != 0) {
                        clusterSurface = Surface.CREATOR.createFromParcel(data);
                    }
                    
                    Rect clusterSrcRect = null;
                    if (data.readInt() != 0) {
                        clusterSrcRect = Rect.CREATOR.createFromParcel(data);
                    }
                    
                    Rect clusterDstRect = null;
                    if (data.readInt() != 0) {
                        clusterDstRect = Rect.CREATOR.createFromParcel(data);
                    }

                    AppLogger.i(TAG, "Reçu Surface du cluster ! " + clusterSurface + " src=" + clusterSrcRect + " dst=" + clusterDstRect);
                    
                    if (clusterSurface != null && mDashboardSurface != null) {
                        // TODO: on DOIT transférer les pixels de myApp(mDashboardSurface) vers clusterSurface.
                        // Mais comment on copie mDashboardSurface vers clusterSurface ? 
                        // Normalement on configure un DisplayContext ou une SurfaceFlinger !
                    }
                    
                    return true;
                } catch (Exception e) {
                    AppLogger.e(TAG, "Erreur onTransact code 2", e);
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    public BydVideoMirrorClient(Context context) {
        this.mContext = context;
    }

    public void startListening() {
        // Obsolete, on ne liste plus, on envoie le binder au système.
    }

    public void stopListening() {
    }

    public void startMirroring(Surface surface, int clusterW, int clusterH, int viewW, int viewH) {
        this.mDashboardSurface = surface;
        
        AppLogger.i(TAG, "Envoi de notre Binder Vidéo au système BYD DiLink");
        Intent startIntent = new Intent(ACTION_START_MIRROR);
        
        // WindowManagement met son propre package, mais on peut le mettre ou non.
        // startIntent.setPackage(mContext.getPackageName());
        
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBinder(BINDER_INTERFACE_TOKEN, mSystemBinder);
        startIntent.putExtras(extras);
        
        mContext.sendBroadcast(startIntent);
    }
}
