package com.byd.dashcast.ime;

/** Process-local identity of the editable field session opened on the cluster. */
final class ClusterImeRelaySession {

    private int displayId = -1;
    private String packageName;

    synchronized void bind(int targetDisplayId, String targetPackage) {
        if (targetDisplayId <= 0 || targetPackage == null || targetPackage.isEmpty()) {
            clear();
            return;
        }
        displayId = targetDisplayId;
        packageName = targetPackage;
    }

    synchronized void clear() {
        displayId = -1;
        packageName = null;
    }

    synchronized boolean hasTargetOn(int activeDisplayId) {
        return activeDisplayId > 0 && displayId == activeDisplayId && packageName != null;
    }

    synchronized String packageOn(int activeDisplayId) {
        return hasTargetOn(activeDisplayId) ? packageName : null;
    }

    synchronized boolean accepts(int activeDisplayId, CharSequence candidatePackage) {
        return hasTargetOn(activeDisplayId)
                && candidatePackage != null
                && packageName.contentEquals(candidatePackage);
    }
}