package com.byd.dashcast.proxy.daemon

import android.util.Log
import java.lang.reflect.Method

/**
 * Utilitaire exécuté par l'ADB (shell uid=2000) pour nettoyer l'écran Récents.
 * L'exécution via app_process permet d'utiliser la Réflexion Java pour trouver
 * dynamiquement la fonction removeTask(id) sans avoir à tester en boucle
 * des codes de transactions Binder qui changent selon les versions Android !
 */
class TaskRemover {
    companion object {
        private const val TAG = "TaskRemover"

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.size < 1) return
            try {
                val taskId = args[0].toInt()
                Log.i(TAG, "Attempting to remove taskId $taskId")

                var amService: Any?
                var iClass: Class<*>
                try {
                    // Android 10+ (DiLink 3.0)
                    val atmClass = Class.forName("android.app.ActivityTaskManager")
                    amService = atmClass.getMethod("getService").invoke(null)
                    iClass = Class.forName("android.app.IActivityTaskManager")
                } catch (e: Exception) {
                    // DiLink 1.0 / 2.0
                    val amClass = Class.forName("android.app.ActivityManager")
                    amService = amClass.getMethod("getService").invoke(null)
                    iClass = Class.forName("android.app.IActivityManager")
                }

                var found = false
                for (m in iClass.methods) {
                    if (m.name == "removeTask") {
                        val params = m.parameterTypes
                        if (params.size >= 1 && params[0] == Int::class.javaPrimitiveType) {
                            if (params.size == 1) {
                                // removeTask(int taskId)
                                m.invoke(amService, taskId)
                            } else if (params.size == 2 && params[1] == Int::class.javaPrimitiveType) {
                                // removeTask(int taskId, int flags)
                                m.invoke(amService, taskId, 0)
                            } else {
                                continue
                            }
                            found = true
                            Log.i(TAG, "Method removeTask successfully invoked!")
                            break
                        }
                    }
                }
                if (!found) {
                    Log.e(TAG, "Could not find removeTask method.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeTask failed", e)
            }
        }
    }
}
