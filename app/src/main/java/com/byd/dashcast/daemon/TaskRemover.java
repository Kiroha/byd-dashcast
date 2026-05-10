package com.byd.dashcast.daemon;

import java.lang.reflect.Method;

/**
 * Utilitaire exécuté par l'ADB (shell uid=2000) pour nettoyer l'écran Récents.
 * L'exécution via app_process permet d'utiliser la Réflexion Java pour trouver
 * dynamiquement la fonction removeTask(id) sans avoir à tester en boucle 
 * des codes de transactions Binder qui changent selon les versions Android !
 */
public class TaskRemover {
    public static void main(String[] args) {
        if (args.length < 1) return;
        try {
            int taskId = Integer.parseInt(args[0]);
            System.out.println("Java TaskRemover: Attempting to remove taskId " + taskId);

            Object amService;
            Class<?> iClass;
            try {
                // Android 10+ (DiLink 3.0)
                Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
                amService = atmClass.getMethod("getService").invoke(null);
                iClass = Class.forName("android.app.IActivityTaskManager");
            } catch (Exception e) {
                // DiLink 1.0 / 2.0
                Class<?> amClass = Class.forName("android.app.ActivityManager");
                amService = amClass.getMethod("getService").invoke(null);
                iClass = Class.forName("android.app.IActivityManager");
            }

            boolean found = false;
            for (Method m : iClass.getMethods()) {
                if (m.getName().equals("removeTask")) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length >= 1 && params[0] == int.class) {
                        if (params.length == 1) {
                            // removeTask(int taskId)
                            m.invoke(amService, taskId);
                        } else if (params.length == 2 && params[1] == int.class) {
                            // removeTask(int taskId, int flags)
                            m.invoke(amService, taskId, 0);
                        } else {
                            continue;
                        }
                        found = true;
                        System.out.println("Method removeTask successfully invoked!");
                        break;
                    }
                }
            }
            if (!found) {
                System.out.println("Error: Could not find removeTask method.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
