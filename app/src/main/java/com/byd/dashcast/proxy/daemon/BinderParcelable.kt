package com.byd.dashcast.proxy.daemon

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * Thin Parcelable wrapper for a single [IBinder], used to ship the
 * daemon's Binder reference from the daemon process to the app via an Intent
 * extra (which does not natively accept raw IBinder values).
 *
 * Pattern borrowed from OpenBYD's `ProxyBinderParcelable`: it is the
 * only way to cross the SELinux app↔shell boundary on Android 10+ for our use
 * case, since [android.net.LocalServerSocket] in the abstract namespace
 * is denied to `untrusted_app` domains trying to `connectto` a
 * socket owned by the `shell` domain.
 */
class BinderParcelable(
    @JvmField val binder: IBinder?
) : Parcelable {

    private constructor(parcel: Parcel) : this(parcel.readStrongBinder())

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BinderParcelable> =
            object : Parcelable.Creator<BinderParcelable> {
                override fun createFromParcel(parcel: Parcel): BinderParcelable {
                    return BinderParcelable(parcel)
                }

                override fun newArray(size: Int): Array<BinderParcelable?> {
                    return arrayOfNulls(size)
                }
            }
    }
}
