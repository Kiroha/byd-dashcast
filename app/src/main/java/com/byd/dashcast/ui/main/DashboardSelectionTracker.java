package com.byd.dashcast.ui.main;

/** Tracks one pending boot-adoption lookup across taps and Activity/service invalidation. */
public final class DashboardSelectionTracker {

    public static final class Selection {
        private final long generation;
        private final boolean duplicatePending;

        private Selection(long generation, boolean duplicatePending) {
            this.generation = generation;
            this.duplicatePending = duplicatePending;
        }

        public long getGeneration() { return generation; }
        public boolean isDuplicatePending() { return duplicatePending; }
    }

    private long generation;
    private String pendingBootPackage;

    /** Begins a selection, or identifies a duplicate tap for the lookup already in flight. */
    public synchronized Selection begin(String packageName) {
        if (packageName != null && packageName.equals(pendingBootPackage)) {
            return new Selection(generation, true);
        }
        pendingBootPackage = null;
        return new Selection(++generation, false);
    }

    public synchronized boolean markBootPending(String packageName, long expectedGeneration) {
        if (packageName == null || generation != expectedGeneration) return false;
        pendingBootPackage = packageName;
        return true;
    }

    /** Completes only the current matching lookup and clears its pending state. */
    public synchronized boolean completeBoot(String packageName, long expectedGeneration) {
        if (generation != expectedGeneration
                || packageName == null
                || !packageName.equals(pendingBootPackage)) {
            return false;
        }
        pendingBootPackage = null;
        return true;
    }

    /** Invalidates callbacks and returns the package that must be restored to the process latch. */
    public synchronized String takePendingForInvalidation() {
        generation++;
        String pending = pendingBootPackage;
        pendingBootPackage = null;
        return pending;
    }
}
