package com.github.guoyyit.stereodepthestimationjava.stereo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Incremental and concurrent processing of frame sequences with bounded
 * history. Failed or cancelled frames leave no partial state behind:
 * the history is only updated after a frame completes successfully, and
 * each frame runs on its own pipeline invocation with its own
 * cancellation token.
 */
public final class FrameProcessor implements AutoCloseable {
    private final StereoPipeline pipeline;
    private final ExecutorService executor;
    private final int maxHistory;
    private final Deque<StereoResult> history = new ArrayDeque<>();

    public FrameProcessor(StereoPipeline pipeline, int maxHistory, int parallelism) {
        if (maxHistory < 1) {
            throw new IllegalArgumentException("maxHistory must be >= 1");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.pipeline = pipeline;
        this.maxHistory = maxHistory;
        this.executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "frame-processor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Submits one frame for asynchronous processing. Cancelling the
     * returned future cooperatively aborts the frame at the next
     * pipeline stage boundary and records nothing in the history.
     */
    public CompletableFuture<StereoResult> submit(StereoImagePair pair, boolean wantPointCloud) {
        StereoPipeline.CancellationToken token = new StereoPipeline.CancellationToken();
        CompletableFuture<StereoResult> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                token.cancel();
                return super.cancel(mayInterruptIfRunning);
            }
        };
        executor.execute(() -> {
            try {
                StereoResult result = pipeline.process(pair, wantPointCloud, token);
                if (future.complete(result)) {
                    record(result);
                }
            } catch (ProcessingCancelledException e) {
                future.completeExceptionally(e);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Returns up to the most recent {@code maxHistory} completed
     * results, oldest first. Memory stays bounded because older entries
     * are evicted as new ones arrive.
     */
    public synchronized List<StereoResult> history() {
        return new ArrayList<>(history);
    }

    private synchronized void record(StereoResult result) {
        history.addLast(result);
        while (history.size() > maxHistory) {
            history.removeFirst();
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
