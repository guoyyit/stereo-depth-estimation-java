package com.github.guoyyit.stereodepthestimationjava.pipeline;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.CalibrationVersionException;
import com.github.guoyyit.stereodepthestimationjava.error.FrameCancelledException;
import com.github.guoyyit.stereodepthestimationjava.error.InvalidFrameException;
import com.github.guoyyit.stereodepthestimationjava.error.StereoPipelineException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.model.FrameRequest;
import com.github.guoyyit.stereodepthestimationjava.model.PipelineConfig;
import com.github.guoyyit.stereodepthestimationjava.model.StereoResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class StereoProcessingService implements AutoCloseable {

    private final CalibrationRegistry registry;
    private final PipelineConfig config;
    private final ThreadPoolExecutor executor;
    private final java.util.concurrent.ConcurrentHashMap<String, FrameTask> active =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ArrayDeque<FrameExecutionInfo> history = new ArrayDeque<>();
    private final Object historyLock = new Object();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();

    public StereoProcessingService(CalibrationRegistry registry, PipelineConfig config,
                                   int parallelism) {
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism must be within [1, 64]");
        }
        this.registry = registry;
        this.config = config;
        this.executor = new ThreadPoolExecutor(parallelism, parallelism, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "stereo-worker");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public CompletableFuture<StereoResult> submit(FrameRequest request) {
        return submit(request, null);
    }

    public CompletableFuture<StereoResult> submit(FrameRequest request,
                                                  Consumer<FrameExecutionInfo> completionCallback) {
        validateRequestShape(request);
        CalibrationParams snapshot = request.requestedCalibrationVersion() == null
                ? registry.resolveActive()
                : registry.resolve(request.requestedCalibrationVersion());

        String frameId = request.frameId();
        FrameTask task = new FrameTask(frameId, snapshot, completionCallback);
        FrameTask existing = active.putIfAbsent(frameId, task);
        if (existing != null) {
            throw new InvalidFrameException("frame id already in flight: " + frameId);
        }
        submitted.incrementAndGet();

        StereoPipeline pipeline = new StereoPipeline(registry, config);
        try {
            // execute() (not submit()) keeps the FrameRunnable itself on the work queue,
            // so close() can identify and terminally complete dropped queued frames.
            executor.execute(new FrameRunnable(task, () -> runFrame(task, request, snapshot, pipeline)));
        } catch (RejectedExecutionException e) {
            active.remove(frameId, task);
            submitted.decrementAndGet();
            throw new StereoPipelineException("processing service is closed; frame rejected: "
                    + frameId);
        }
        return task.future;
    }

    private void runFrame(FrameTask task, FrameRequest request, CalibrationParams snapshot,
                          StereoPipeline pipeline) {
        StereoResult result = null;
        Throwable failure = null;
        try {
            if (task.cancelToken.get()) {
                throw new FrameCancelledException("frame cancelled before processing: "
                        + task.frameId);
            }
            result = pipeline.processWith(request, snapshot, task.cancelToken::get);
        } catch (Throwable t) {
            failure = t;
        } finally {
            finish(task, snapshot, result, failure);
        }
    }

    /**
     * Terminally records and completes a frame exactly once, whether the frame ran to
     * completion, failed, was cancelled cooperatively, or was dropped from the queue on
     * shutdown. This is what guarantees that no submitted request can ever remain
     * unanswered and that cancelled frames stay distinguishable in stats and history.
     */
    private void finish(FrameTask task, CalibrationParams snapshot, StereoResult result,
                        Throwable failure) {
        if (!task.terminalRecorded.compareAndSet(false, true)) {
            return;
        }
        active.remove(task.frameId, task);
        long durationMillis = (System.nanoTime() - task.startedNanos) / 1_000_000L;
        boolean wasCancelled = failure instanceof FrameCancelledException
                || (failure == null && task.cancelToken.get());
        FrameExecutionInfo info;
        if (failure == null && !task.cancelToken.get()) {
            succeeded.incrementAndGet();
            info = new FrameExecutionInfo(task.frameId, snapshot.version(), true, false,
                    null, durationMillis);
        } else if (wasCancelled) {
            cancelled.incrementAndGet();
            String message = failure != null ? failure.getMessage()
                    : "frame cancelled after processing reached completion";
            info = new FrameExecutionInfo(task.frameId, snapshot.version(), false, true,
                    message, durationMillis);
        } else {
            failed.incrementAndGet();
            info = new FrameExecutionInfo(task.frameId, snapshot.version(), false, false,
                    failure.getMessage(), durationMillis);
        }
        recordHistory(info);
        if (task.completionCallback != null) {
            invokeCallback(task.completionCallback, info);
        }
        if (failure == null && !task.cancelToken.get()) {
            task.future.complete(result);
        } else if (wasCancelled) {
            task.future.completeExceptionally(
                    failure instanceof FrameCancelledException ? failure
                            : new FrameCancelledException("frame cancelled: " + task.frameId));
        } else {
            task.future.completeExceptionally(failure);
        }
    }

    public boolean cancel(String frameId, boolean mayInterruptIfRunning) {
        FrameTask task = active.get(frameId);
        if (task == null) {
            return false;
        }
        task.cancelToken.set(true);
        // Cancellation is cooperative through the token only. Worker threads are never
        // interrupted and queued tasks are never removed, so a fixed-size pool cannot lose
        // a worker or leave the returned CompletableFuture permanently unfinished.
        return true;
    }

    public List<FrameExecutionInfo> history() {
        synchronized (historyLock) {
            return new ArrayList<>(history);
        }
    }

    public ProcessingStats stats() {
        synchronized (historyLock) {
            return new ProcessingStats(submitted.get(), succeeded.get(), failed.get(),
                    cancelled.get(), history.size());
        }
    }

    @Override
    public void close() {
        // Frames still queued are dropped by shutdownNow; complete them as cancelled so
        // their futures never stay pending forever. Frames already running are not
        // interrupted and finish cooperatively, completing their own futures.
        List<Runnable> dropped = executor.shutdownNow();
        for (Runnable runnable : dropped) {
            if (runnable instanceof FrameRunnable frameRunnable) {
                FrameTask task = frameRunnable.task;
                task.cancelToken.set(true);
                finish(task, task.snapshot, null,
                        new FrameCancelledException(
                                "service closed before frame processing started: "
                                        + task.frameId));
            }
        }
    }

    private void recordHistory(FrameExecutionInfo info) {
        synchronized (historyLock) {
            history.addLast(info);
            while (history.size() > config.maxFrameHistory()) {
                history.removeFirst();
            }
        }
    }

    private static void invokeCallback(Consumer<FrameExecutionInfo> callback,
                                       FrameExecutionInfo info) {
        try {
            callback.accept(info);
        } catch (RuntimeException ignored) {
            // A failing user callback must never corrupt pipeline state.
        }
    }

    private static void validateRequestShape(FrameRequest request) {
        if (request == null) {
            throw new InvalidFrameException("frame request must not be null");
        }
        if (request.frameId() == null || request.frameId().isBlank()) {
            throw new InvalidFrameException("frame id must not be blank");
        }
        if (request.left() == null || request.right() == null) {
            throw new InvalidFrameException("left and right images must not be null");
        }
        if (request.left().width() != request.right().width()
                || request.left().height() != request.right().height()) {
            throw new InvalidFrameException("left and right image dimensions must match");
        }
        if (request.requestedCalibrationVersion() != null
                && request.requestedCalibrationVersion().isBlank()) {
            throw new CalibrationVersionException(
                    "requested calibration version must not be blank");
        }
    }

    private static final class FrameRunnable implements Runnable {
        private final FrameTask task;
        private final Runnable body;

        private FrameRunnable(FrameTask task, Runnable body) {
            this.task = task;
            this.body = body;
        }

        @Override
        public void run() {
            body.run();
        }
    }

    private static final class FrameTask {
        private final String frameId;
        private final CalibrationParams snapshot;
        private final Consumer<FrameExecutionInfo> completionCallback;
        private final AtomicBoolean cancelToken = new AtomicBoolean(false);
        private final AtomicBoolean terminalRecorded = new AtomicBoolean(false);
        private final CompletableFuture<StereoResult> future = new CompletableFuture<>();
        private final long startedNanos = System.nanoTime();

        private FrameTask(String frameId, CalibrationParams snapshot,
                          Consumer<FrameExecutionInfo> completionCallback) {
            this.frameId = frameId;
            this.snapshot = snapshot;
            this.completionCallback = completionCallback;
        }
    }
}
