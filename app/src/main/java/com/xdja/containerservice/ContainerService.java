package com.xdja.containerservice;

/**
 * STUB — réplique fidèle de la classe `com.xdja.containerservice.ContainerService`
 * de la ROM (cf. decompiled/containerservice/sources/com/xdja/containerservice/
 * ContainerService.java).
 *
 * Pourquoi ce stub ?
 * Le binding JNI de `libxdjacontainerservice_jni.so` enregistre ses méthodes
 * natives en cherchant les symboles
 *   `Java_com_xdja_containerservice_ContainerService_getQtProjectionDispInfoNative`
 * et `Java_com_xdja_containerservice_ContainerService_getQtProjectionDispInfoArrayNative`.
 *
 * Pour que ces symboles soient résolus quand on appelle ces méthodes depuis
 * notre code, il FAUT que la classe Java existe avec exactement ce package
 * (`com.xdja.containerservice`) et ce nom (`ContainerService`), ainsi que les
 * mêmes signatures de méthodes natives (incluant le retour `QtDisplayInfo`).
 *
 * Une fois `System.load("/system/lib64/libxdjacontainerservice_jni.so")` (ou
 * lib32) effectué, on peut appeler ces méthodes — le linker dynamique trouve
 * les symboles natifs et les invoque. Le code natif renverra la même Surface
 * que celle utilisée par AutoDisplayService (uid system) parce qu'elle vient
 * du même endroit (process Qt cluster, exposé via shm/socket).
 *
 * Si le code natif fait un check UID en interne et refuse uid 10100, on
 * obtiendra `null` ou une exception — le log nous le dira.
 */
public class ContainerService {

    /**
     * Retourne la `QtDisplayInfo` (surface + nom + dimensions) du Qt projection
     * surface d'identifiant `id` (id=0 = surface principale, id=1+ = secondaires).
     */
    public static native QtDisplayInfo getQtProjectionDispInfoNative(int id);

    /**
     * Retourne TOUTES les Qt projection surfaces (peut révéler des surfaces
     * supplémentaires non utilisées par AutoDisplayService).
     */
    public static native QtDisplayInfo[] getQtProjectionDispInfoArrayNative();

    public static QtDisplayInfo getQtProjectionDispInfo(int id) {
        return getQtProjectionDispInfoNative(id);
    }

    public static QtDisplayInfo[] getQtProjectionDispInfoArray() {
        return getQtProjectionDispInfoArrayNative();
    }
}
