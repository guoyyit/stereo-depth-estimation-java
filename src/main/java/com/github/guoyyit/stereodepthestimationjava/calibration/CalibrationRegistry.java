package com.github.guoyyit.stereodepthestimationjava.calibration;

import com.github.guoyyit.stereodepthestimationjava.error.CalibrationVersionException;
import com.github.guoyyit.stereodepthestimationjava.model.CalibrationParams;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CalibrationRegistry {

    private final ConcurrentHashMap<String, CalibrationParams> versions = new ConcurrentHashMap<>();
    private volatile String activeVersion;

    public CalibrationRegistry() {
    }

    /**
     * Registers calibration content under its version.
     *
     * <p>Calibration content is immutable per version: once a version has been
     * registered, registering the same version with different content is rejected with
     * {@link CalibrationVersionException} of reason
     * {@link CalibrationVersionException.Reason#CONTENT_CONFLICT}. Re-registering the
     * same version with identical content is an idempotent no-op (the version simply
     * becomes active again).</p>
     */
    public void register(CalibrationParams params) {
        if (params == null) {
            throw new CalibrationVersionException("calibration params must not be null",
                    CalibrationVersionException.Reason.MISSING_VERSION);
        }
        CalibrationParams existing = versions.get(params.version());
        if (existing != null && !existing.contentEquals(params)) {
            throw new CalibrationVersionException(
                    "calibration version '" + params.version()
                            + "' is already registered with different content;"
                            + " calibration content is immutable per version",
                    CalibrationVersionException.Reason.CONTENT_CONFLICT);
        }
        versions.put(params.version(), params);
        activeVersion = params.version();
    }

    public CalibrationParams resolve(String version) {
        if (version == null || version.isBlank()) {
            throw new CalibrationVersionException(
                    "calibration version must be specified and must not be blank",
                    CalibrationVersionException.Reason.MISSING_VERSION);
        }
        CalibrationParams params = versions.get(version);
        if (params == null) {
            throw new CalibrationVersionException("unknown calibration version: " + version,
                    CalibrationVersionException.Reason.UNKNOWN_VERSION);
        }
        return params;
    }

    public CalibrationParams resolveActive() {
        String version = activeVersion;
        if (version == null) {
            throw new CalibrationVersionException("no calibration version has been registered yet",
                    CalibrationVersionException.Reason.NO_REGISTERED_VERSION);
        }
        return resolve(version);
    }

    public String activeVersion() {
        return activeVersion;
    }

    public Set<String> registeredVersions() {
        return Set.copyOf(versions.keySet());
    }

    public Map<String, CalibrationParams> snapshot() {
        return Map.copyOf(versions);
    }
}
