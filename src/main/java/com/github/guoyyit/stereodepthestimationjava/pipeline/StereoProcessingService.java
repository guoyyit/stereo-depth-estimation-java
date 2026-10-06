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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class StereoProcessingService implements AutoCloseable {

    private final CalibrationRegistry registry;
    private final PipelineConfig config;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<String, FrameTask> active = new ConcurrentHashMap<>();
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
        FrameTask task = new FrameTask(frameId, snapshot);
        FrameTask existing = active.putIfAbsent(frameId, task);
        if (existing != null) {
            throw new InvalidFrameException("frame id already in flight: " + frameId);
        }
        submitted.incrementAndGet();

        StereoPipeline pipeline = new StereoPipeline(registry, config);
        try {
            executor.submit(() -> runFrame(request, snapshot, pipeline, task,
                    completionCallback));
        } catch (RuntimeException rejected) {
            // The executor refused the task (e.g. service closed): the frame must
            // still reach a terminal state instead of hanging forever.
            active.remove(frameId, task);
            finalizeExactlyOnce(task, snapshot, null,
                    new StereoPipelineException("frame was rejected by the executor: "
                            + rejected.getMessage()),
                    completionCallback);
        }
        return task.future;
    }

    private void runFrame(FrameRequest request, CalibrationParams snapshot,
                          StereoPipeline pipeline, FrameTask task,
                          Consumer<FrameExecutionInfo> completionCallback) {
        StereoResult result = null;
        Throwable failure = null;
        try {
            if (task.cancelToken.get()) {
                throw new FrameCancelledException(
                        "frame cancelled before processing: " + task.frameId);
            }
            result = pipeline.processWith(request, snapshot, task.cancelToken::get);
        } catch (Throwable t) {
            failure = t;
        } finally {
            active.remove(task.frameId, task);
            finalizeExactlyOnce(task, snapshot, result, failure, completionCallback);
        }
    }

    /**
     * Exactly-once terminal bookkeeping. Whichever path wins the CAS (worker
     * completion, executor rejection, or service close) records the outcome; every
     * submitted frame therefore always reaches a terminal state and its future
     * always completes.
     */
    private void finalizeExactlyOnce(FrameTask task, CalibrationParams snapshot,
                                     StereoResult result, Throwable failure,
                                     Consumer<FrameExecutionInfo> completionCallback) {
        if (!task.completed.compareAndSet(false, true)) {
            return;
        }
        long durationMillis = (System.nanoTime() - task.startedNanos) / 1_000_000L;
        boolean wasCancelled = failure instanceof FrameCancelledException
                || (failure == null && task.cancelToken.get());
        FrameExecutionInfo info;
        if (failure == null && !task.cancelToken.get()) {
            succeeded.incrementAndGet();
            info = new FrameExecutionInfo(task.frameId, snapshot.version(), true, false,
                    null, durationMillis);
            recordHistory(info);
            invokeCallback(completionCallback, info);
            task.future.complete(result);
        } else if (wasCancelled) {
            cancelled.incrementAndGet();
            String message = failure != null ? failure.getMessage()
                    : "frame cancelled after processing reached completion";
            info = new FrameExecutionInfo(task.frameId, snapshot.version(), false, true,
                    message, durationMillis);
            recordHistory(info);
            invokeCallback(completionCallback, info);
            task.future.completeExceptionally(
                    failure instanceof FrameCancelledException ? failure
                            : new FrameCancelledException("frame cancelled: " + task.frameId));
        } else {
            failed.incrementAndGet();
            info = new FrameExecutionInfo(task.frameId, snapshot.version(), false, false,
                    failure.getMessage(), durationMillis);
            recordHistory(info);
            invokeCallback(completionCallback, info);
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
        executor.shutdownNow();
        // Frames still queued or running when the service closes must not hang:
        // force every remaining in-flight frame to a cancelled terminal state.
        for (Map.Entry<String, FrameTask> entry : active.entrySet()) {
            FrameTask task = entry.getValue();
            if (active.remove(entry.getKey(), task)) {
                task.cancelToken.set(true);
                finalizeExactlyOnce(task, task.snapshot, null,
                        new FrameCancelledException(
                                "service closed before frame completed: " + task.frameId),
                        null);
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
        if (callback == null) {
            return;
        }
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

    private static final class FrameTask {
        private final String frameId;
        private final CalibrationParams snapshot;
        private final long startedNanos;
        private final AtomicBoolean cancelToken = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final CompletableFuture<StereoResult> future = new CompletableFuture<>();

        private FrameTask(String frameId, CalibrationParams snapshot) {
            this.frameId = frameId;
            this.snapshot = snapshot;
            this.startedNanos = System.nanoTime();
        }
    }
}
