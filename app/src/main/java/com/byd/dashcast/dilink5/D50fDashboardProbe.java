package com.byd.dashcast.dilink5;

final class D50fDashboardProbe {

    static final long RESET_SETTLE_MS = 6_000L;
    static final long OBSERVATION_MS = 3_000L;

    interface Sender {
        int send(int type, int info) throws Exception;
    }

    interface Sleeper {
        void sleep(long durationMs) throws InterruptedException;
    }

    static final class Result {
        final int resetCode;
        final int unlockCode;
        final int restoreCode;

        Result(int resetCode, int unlockCode, int restoreCode) {
            this.resetCode = resetCode;
            this.unlockCode = unlockCode;
            this.restoreCode = restoreCode;
        }
    }

    private D50fDashboardProbe() {}

    static Result run(Sender sender, Sleeper sleeper) throws Exception {
        int resetCode = sender.send(1000, 18);
        int unlockCode;
        int restoreCode;
        try {
            sleeper.sleep(RESET_SETTLE_MS);
            unlockCode = sender.send(16, 35);
            sleeper.sleep(OBSERVATION_MS);
        } finally {
            restoreCode = sender.send(1000, 18);
        }
        return new Result(resetCode, unlockCode, restoreCode);
    }
}