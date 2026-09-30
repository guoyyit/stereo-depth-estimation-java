package com.github.guoyyit.stereodepthestimationjava.stereo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sequence processing: bounded history, failure isolation, cancellation
 * isolation and deterministic results under concurrency.
 */
class FrameProcessorTest {
    private static final int W = 64;
    private static final int H = 48;

    private static CalibrationRegistry registry() {
        CalibrationRegistry registry = new CalibrationRegistry();
        registry.publish(CalibrationParams.ideal("v1", 100.0, W / 2.0, H / 2.0, 0.1));
        return registry;
    }

    private static StereoConfig config() {
        return StereoConfig.builder().numDisparities(32).blockRadius(2).build();
    }

    private static StereoImagePair frame(long seed) {
        return SyntheticDataGenerator.generate(W, H, seed).pair();
    }

    @Test
    void failedFrameLeavesNoPartialStateAndDoesNotAffectLaterFrames() throws Exception {
        try (FrameProcessor processor = new FrameProcessor(
                new StereoPipeline(registry(), StereoConfig.builder()
                        .numDisparities(32).blockRadius(2).downsampleFactor(3).build()),
                4, 2)) {
            // 64x48 is not divisible by 3: this frame must fail.
            CompletableFuture<StereoResult> bad = processor.submit(frame(1L), false);
            assertThrows(ExecutionException.class, bad::get);
            assertTrue(bad.isCompletedExceptionally());

            CompletableFuture<StereoResult> good = processor.submit(
                    SyntheticDataGenerator.generate(96, 48, 1L).pair(), false);
            // 96x48 is divisible by 3: processing recovers immediately.
            StereoResult result = good.get(30, TimeUnit.SECONDS);
            assertEquals("v1", result.calibrationVersion());
            assertEquals(List.of(result), processor.history());
        }
    }

    @Test
    void cancelledFrameLeavesNoPartialState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger frames = new AtomicInteger();
        StereoPipeline pipeline = new StereoPipeline(registry(), config(),
                pinned -> {
                    if (frames.getAndIncrement() == 0) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        try (FrameProcessor processor = new FrameProcessor(pipeline, 4, 1)) {
            CompletableFuture<StereoResult> first = processor.submit(frame(1L), false);
            assertTrue(entered.await(30, TimeUnit.SECONDS));

            CompletableFuture<StereoResult> second = processor.submit(frame(2L), false);
            second.cancel(true);
            release.countDown();

            StereoResult firstResult = first.get(30, TimeUnit.SECONDS);
            assertTrue(second.isCancelled());

            CompletableFuture<StereoResult> third = processor.submit(frame(3L), false);
            StereoResult thirdResult = third.get(30, TimeUnit.SECONDS);

            // The cancelled frame recorded nothing; history holds only
            // the frames that completed successfully.
            assertEquals(List.of(firstResult, thirdResult), processor.history());
        }
    }

    @Test
    void historyIsBounded() throws Exception {
        try (FrameProcessor processor = new FrameProcessor(
                new StereoPipeline(registry(), config()), 3, 2)) {
            StereoResult last = null;
            StereoResult secondLast = null;
            StereoResult thirdLast = null;
            for (int i = 0; i < 6; i++) {
                thirdLast = secondLast;
                secondLast = last;
                last = processor.submit(frame(10L + i), false).get(30, TimeUnit.SECONDS);
            }
            assertEquals(3, processor.history().size());
            assertEquals(List.of(thirdLast, secondLast, last), processor.history());
        }
    }

    @Test
    void concurrentResultsMatchSequentialResults() throws Exception {
        int frames = 6;
        StereoResult[] expected = new StereoResult[frames];
        StereoPipeline sequential = new StereoPipeline(registry(), config());
        for (int i = 0; i < frames; i++) {
            expected[i] = sequential.process(frame(20L + i), false);
        }

        try (FrameProcessor processor = new FrameProcessor(
                new StereoPipeline(registry(), config()), 8, 4)) {
            @SuppressWarnings("unchecked")
            CompletableFuture<StereoResult>[] futures = new CompletableFuture[frames];
            for (int i = 0; i < frames; i++) {
                futures[i] = processor.submit(frame(20L + i), false);
            }
            for (int i = 0; i < frames; i++) {
                StereoResult actual = futures[i].get(30, TimeUnit.SECONDS);
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        assertEquals(expected[i].disparity().reason(x, y),
                                actual.disparity().reason(x, y));
                        float e = expected[i].disparity().disparity(x, y);
                        float a = actual.disparity().disparity(x, y);
                        if (Float.isNaN(e) && Float.isNaN(a)) {
                            continue;
                        }
                        assertEquals(Float.floatToIntBits(e), Float.floatToIntBits(a));
                    }
                }
            }
        }
    }
}
