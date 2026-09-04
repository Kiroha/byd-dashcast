package com.byd.dashcast.proxy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

/** Registers daemon Binder broadcasts with a sender permission held by the uid-2000 shell. */
// `object` supplies the non-instantiable shape the Java original expressed as a final class with
// a private constructor; the FQN, the static field and the static method are unchanged. `const`
// keeps SENDER_PERMISSION a real compile-time constant (a ConstantValue in the class file), so
// Java call sites inline it exactly as they did with the `static final` field.
object DaemonBroadcastRegistrar {

    const val SENDER_PERMISSION: String = Manifest.permission.DUMP

    @JvmStatic
    fun register(context: Context?, receiver: BroadcastReceiver?, filter: IntentFilter?) {
        // Parameters stay nullable: a still-Java caller (ProxyClient.ensureReceiverRegistered)
        // reaches this method with platform types. Non-null parameters would emit
        // Intrinsics.checkNotNullParameter guards the Java original never had, rejecting at entry
        // a call the platform itself accepts — Context.registerReceiver leaves `receiver` and
        // `filter` unannotated in android.jar, so null is legal for both. The exception *type* is
        // not the difference: since Kotlin 1.4 checkNotNullParameter also throws
        // NullPointerException (only the retired checkParameterIsNotNull threw
        // IllegalArgumentException); the narrowed contract is.
        // `!!` keeps a null Context throwing NullPointerException, exactly as the Java original.
        context!!.registerReceiver(receiver, filter, SENDER_PERMISSION, null)
    }
}
