package com.github.guoyyit.stereodepthestimationjava.tests;

import com.github.guoyyit.stereodepthestimationjava.calibration.CalibrationRegistry;
import com.github.guoyyit.stereodepthestimationjava.error.CalibrationVersionException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;
import com.github.guoyyit.stereodepthestimationjava.synthetic.TestCalibrations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calibration content is immutable per version: identical re-registration is an
 * idempotent no-op, conflicting content under an already-used version is rejected
 * with a reason that is distinguishable from missing/unknown versions.
 */
class CalibrationImmutabilityTest {

    private static final int WIDTH = 120;
    private static final int HEIGHT = 100;

    @Test
    void identicalContentReregistrationIsIdempotent() {
        CalibrationRegistry registry = new CalibrationRegistry();
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        registry.register(v1);
        registry.register(TestCalibrations.v2(WIDTH, HEIGHT));
        // Re-registering byte-identical content must succeed and resolve to the
        // same content (idempotent), and the version becomes active again.
        registry.register(TestCalibrations.v1(WIDTH, HEIGHT));
        CalibrationParams resolved = registry.resolve("calib-v1");
        assertTrue(v1.contentEquals(resolved));
        assertEquals(v1.contentFingerprint(), resolved.contentFingerprint());
        assertEquals("calib-v1", registry.activeVersion());
        assertEquals(2, registry.registeredVersions().size());
    }

    @Test
    void conflictingContentUnderSameVersionIsRejected() {
        CalibrationRegistry registry = new CalibrationRegistry();
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        registry.register(v1);

        // Same version label, different content (different focal length).
        double[] k = v1.k();
        k[0] += 1.0;
        CalibrationParams hijack = CalibrationParams.create(v1.version(), WIDTH, HEIGHT,
                k, v1.d(), v1.r(), v1.t());
        CalibrationVersionException ex = assertThrows(CalibrationVersionException.class,
                () -> registry.register(hijack));
        assertEquals(CalibrationVersionException.Reason.CONTENT_CONFLICT, ex.reason());

        // Registry state must be untouched: original content still resolves.
        assertTrue(v1.contentEquals(registry.resolve(v1.version())));
        assertEquals(v1.contentFingerprint(), registry.resolve(v1.version()).contentFingerprint());
        assertEquals("calib-v1", registry.activeVersion());
    }

    @Test
    void failureReasonsAreDistinguishable() {
        CalibrationRegistry registry = new CalibrationRegistry();

        CalibrationVersionException noRegistered = assertThrows(
                CalibrationVersionException.class, registry::resolveActive);
        assertEquals(CalibrationVersionException.Reason.NO_REGISTERED_VERSION,
                noRegistered.reason());

        CalibrationVersionException missing = assertThrows(
                CalibrationVersionException.class, () -> registry.resolve("  "));
        assertEquals(CalibrationVersionException.Reason.MISSING_VERSION, missing.reason());

        registry.register(TestCalibrations.v1(WIDTH, HEIGHT));
        CalibrationVersionException unknown = assertThrows(
                CalibrationVersionException.class, () -> registry.resolve("nope"));
        assertEquals(CalibrationVersionException.Reason.UNKNOWN_VERSION, unknown.reason());

        double[] d = TestCalibrations.v1(WIDTH, HEIGHT).d();
        d[0] += 0.001;
        CalibrationParams conflict = CalibrationParams.create("calib-v1", WIDTH, HEIGHT,
                TestCalibrations.v1(WIDTH, HEIGHT).k(), d,
                TestCalibrations.v1(WIDTH, HEIGHT).r(), TestCalibrations.v1(WIDTH, HEIGHT).t());
        CalibrationVersionException conflictEx = assertThrows(
                CalibrationVersionException.class, () -> registry.register(conflict));
        assertEquals(CalibrationVersionException.Reason.CONTENT_CONFLICT, conflictEx.reason());

        // All four kinds are pairwise distinct.
        assertNotEquals(noRegistered.reason(), missing.reason());
        assertNotEquals(noRegistered.reason(), unknown.reason());
        assertNotEquals(noRegistered.reason(), conflictEx.reason());
        assertNotEquals(missing.reason(), unknown.reason());
        assertNotEquals(missing.reason(), conflictEx.reason());
        assertNotEquals(unknown.reason(), conflictEx.reason());
    }

    @Test
    void conflictingRegistrationDoesNotChangeInFlightSemantics() {
        CalibrationRegistry registry = new CalibrationRegistry();
        CalibrationParams v1 = TestCalibrations.v1(WIDTH, HEIGHT);
        registry.register(v1);
        // A failed conflicting registration must not create or alter any version.
        CalibrationParams mutated = CalibrationParams.create("calib-v1", WIDTH, HEIGHT,
                v1.k(), new double[] {0.1, 0.2, 0.0, 0.0, 0.0}, v1.r(), v1.t());
        assertThrows(CalibrationVersionException.class, () -> registry.register(mutated));
        assertEquals(1, registry.registeredVersions().size());
        assertTrue(v1.contentEquals(registry.resolveActive()));
    }
}
