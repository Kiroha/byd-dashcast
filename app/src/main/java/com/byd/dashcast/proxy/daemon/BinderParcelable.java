package com.byd.dashcast.proxy.daemon;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Thin Parcelable wrapper for a single {@link IBinder}, used to ship the
 * daemon's Binder reference from the daemon process to the app via an Intent
 * extra (which does not natively accept raw IBinder values).
 *
 * <p>Pattern borrowed from OpenBYD's {@code ProxyBinderParcelable}: it is the
 * only way to cross the SELinux app↔shell boundary on Android 10+ for our use
 * case, since {@link android.net.LocalServerSocket} in the abstract namespace
 * is denied to {@code untrusted_app} domains trying to {@code connectto} a
 * socket owned by the {@code shell} domain.
 */
public final class BinderParcelable implements Parcelable {

    public final IBinder binder;

    public BinderParcelable(IBinder binder) {
        this.binder = binder;
    }

    private BinderParcelable(Parcel in) {
        this.binder = in.readStrongBinder();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(binder);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BinderParcelable> CREATOR = new Creator<BinderParcelable>() {
        @Override
        public BinderParcelable createFromParcel(Parcel in) {
            return new BinderParcelable(in);
        }

        @Override
        public BinderParcelable[] newArray(int size) {
            return new BinderParcelable[size];
        }
    };
}
