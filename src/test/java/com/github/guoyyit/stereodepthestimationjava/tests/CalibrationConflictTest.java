package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.CalibrationVersionException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalibrationConflictTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    private static CalibrationParams sameContentAs(CalibrationParams base) {
        return CalibrationParams.create(base.version(), base.imageWidth(), base.imageHeight(),
                base.k(), base.d(), base.r(), base.t());
    }

    private static CalibrationParams mutatedContent(CalibrationParams base) {
        double[] k = base.k();
        k[0] = k[0] + 1.0;
        return CalibrationParams.create(base.version(), base.imageWidth(), base.imageHeight(),
                k, base.d(), base.r(), base.t());
    }

    @Test
    void sameVersionWithDifferentContentIsRejectedAsContentConflict() {
        CalibrationRegistry registry = new CalibrationRegistry();
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        registry.register(v1);

        CalibrationParams conflicting = mutatedContent(TestCalibrations.v1(WIDTH, HEIGHT));
        CalibrationVersionException error = assertThrows(CalibrationVersionException.class,
                () -> registry.register(conflicting));
        assertEquals(CalibrationVersionException.Kind.CONTENT_CONFLICT, error.kind(),
                "conflicting content must be reported with the CONTENT_CONFLICT kind");

        // The rejected registration must not mutate the registry: original content stays.
        CalibrationParams resolved = registry.resolve(v1.version());
        assertTrue(resolved.contentEquals(v1),
                "rejected registration must not replace the previously registered content");
    }

    @Test
    void identicalContentReregistrationIsIdempotent() {
        CalibrationRegistry registry = new CalibrationRegistry();
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        registry.register(v1);
        registry.register(TestCalibrations.v2(WIDTH, HEIGHT));

        CalibrationParams identical = sameContentAs(v1);
        assertDoesNotThrow(() -> registry.register(identical));
        assertEquals(v1.version(), registry.activeVersion(),
                "idempotent re-registration keeps the latest-registered version active");
        assertTrue(registry.resolve(v1.version()).contentEquals(v1));
    }

    @Test
    void failureKindsAreDistinguishable() {
        CalibrationRegistry registry = new CalibrationRegistry();
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        registry.register(v1);

        CalibrationVersionException missing = assertThrows(CalibrationVersionException.class,
                () -> registry.resolve("  "));
        assertEquals(CalibrationVersionException.Kind.VERSION_MISSING, missing.kind());

        CalibrationVersionException unknown = assertThrows(CalibrationVersionException.class,
                () -> registry.resolve("no-such-version"));
        assertEquals(CalibrationVersionException.Kind.VERSION_UNKNOWN, unknown.kind());

        CalibrationVersionException conflict = assertThrows(CalibrationVersionException.class,
                () -> registry.register(mutatedContent(TestCalibrations.v1(WIDTH, HEIGHT))));
        assertEquals(CalibrationVersionException.Kind.CONTENT_CONFLICT, conflict.kind());

        assertTrue(missing.kind() != unknown.kind()
                && unknown.kind() != conflict.kind()
                && missing.kind() != conflict.kind());
    }

    @Test
    void contentFingerprintDistinguishesContent() {
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        CalibrationParams identical = sameContentAs(v1);
        CalibrationParams mutated = mutatedContent(v1);
        assertEquals(v1.contentFingerprint(), identical.contentFingerprint());
        assertTrue(!v1.contentFingerprint().equals(mutated.contentFingerprint()));
        assertTrue(v1.contentEquals(identical));
        assertTrue(!v1.contentEquals(mutated));
    }
}
