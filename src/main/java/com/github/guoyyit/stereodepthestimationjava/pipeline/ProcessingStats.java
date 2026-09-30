package com.github.guoyyit.stereodepthestimationjava.pipeline;

public final class ProcessingStats {

    private final long submitted;
    private final long succeeded;
    private final long failed;
    private final long cancelled;
    private final int historySize;

    public ProcessingStats(long submitted, long succeeded, long failed, long cancelled,
                           int historySize) {
        this.submitted = submitted;
        this.succeeded = succeeded;
        this.failed = failed;
        this.cancelled = cancelled;
        this.historySize = historySize;
    }

    public long submitted() { return submitted; }

    public long succeeded() { return succeeded; }

    public long failed() { return failed; }

    public long cancelled() { return cancelled; }

    public int historySize() { return historySize; }
}
