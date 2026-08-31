package com.byd.dashcast.ime;

/** Versioned pending text so an older IME completion cannot clear a newer edit. */
final class ImePendingText {
    static final class Snapshot {
        final CharSequence text;
        final long generation;

        Snapshot(CharSequence text, long generation) {
            this.text = text;
            this.generation = generation;
        }
    }

    private CharSequence text;
    private long generation;

    synchronized void set(CharSequence value) {
        text = value;
        generation++;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(text, generation);
    }

    synchronized boolean clearIfCurrent(long expectedGeneration) {
        if (generation != expectedGeneration) return false;
        text = null;
        generation++;
        return true;
    }

    synchronized void clear() {
        text = null;
        generation++;
    }
}