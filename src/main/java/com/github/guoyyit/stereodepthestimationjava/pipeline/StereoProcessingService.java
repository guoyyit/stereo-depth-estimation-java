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
        AtomicBoolean cancelToken = new AtomicBoolean(false);
        CompletableFuture<StereoResult> future = new CompletableFuture<>();
        long startedNanos = System.nanoTime();

        FrameTask existing = active.putIfAbsent(frameId, new FrameTask(cancelToken));
        if (existing != null) {
            throw new InvalidFrameException("frame id already in flight: " + frameId);
        }
        submitted.incrementAndGet();

        StereoPipeline pipeline = new StereoPipeline(registry, config);
        executor.submit(() -> {
            StereoResult result = null;
            Throwable failure = null;
            try {
                if (cancelToken.get()) {
                    throw new FrameCancelledException("frame cancelled before processing: " + frameId);
                }
                result = pipeline.processWith(request, snapshot, cancelToken::get);
            } catch (Throwable t) {
                failure = t;
            } finally {
                active.remove(frameId);
                long durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
                boolean wasCancelled = failure instanceof FrameCancelledException
                        || (failure == null && cancelToken.get());
                FrameExecutionInfo info;
                if (failure == null) {
                    if (cancelToken.get()) {
                        cancelled.incrementAndGet();
                        info = new FrameExecutionInfo(frameId, snapshot.version(), false, true,
                                "frame cancelled after processing reached completion",
                                durationMillis);
                    } else {
                        succeeded.incrementAndGet();
                        info = new FrameExecutionInfo(frameId, snapshot.version(), true, false,
                                null, durationMillis);
                    }
                } else if (wasCancelled) {
                    cancelled.incrementAndGet();
                    info = new FrameExecutionInfo(frameId, snapshot.version(), false, true,
                            failure.getMessage(), durationMillis);
                } else {
                    failed.incrementAndGet();
                    info = new FrameExecutionInfo(frameId, snapshot.version(), false, false,
                            failure.getMessage(), durationMillis);
                }
                recordHistory(info);
                if (completionCallback != null) {
                    invokeCallback(completionCallback, info);
                }
                if (failure == null) {
                    if (cancelToken.get()) {
                        future.completeExceptionally(
                                new FrameCancelledException("frame cancelled: " + frameId));
                    } else {
                        future.complete(result);
                    }
                } else if (wasCancelled) {
                    future.completeExceptionally(
                            failure instanceof FrameCancelledException ? failure
                                    : new FrameCancelledException("frame cancelled: " + frameId));
                } else {
                    future.completeExceptionally(failure);
                }
            }
        });
        return future;
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

    private static final class FrameTask {
        private final AtomicBoolean cancelToken;

        private FrameTask(AtomicBoolean cancelToken) {
            this.cancelToken = cancelToken;
        }
    }
}
