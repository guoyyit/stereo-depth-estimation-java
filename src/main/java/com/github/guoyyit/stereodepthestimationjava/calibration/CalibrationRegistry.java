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
     * Registers calibration content under its version identifier.
     *
     * <p>Immutability contract: once a version identifier has been registered, the content
     * bound to it can never change. Re-registering the identical content is an idempotent
     * no-op (the version simply becomes active again). Registering different content under
     * an already-used version is rejected with a
     * {@link CalibrationVersionException} of kind
     * {@link CalibrationVersionException.Kind#CONTENT_CONFLICT}.</p>
     */
    public void register(CalibrationParams params) {
        if (params == null) {
            throw new CalibrationVersionException(CalibrationVersionException.Kind.VERSION_MISSING,
                    "calibration params must not be null");
        }
        CalibrationParams existing = versions.putIfAbsent(params.version(), params);
        if (existing != null && !existing.contentEquals(params)) {
            throw new CalibrationVersionException(CalibrationVersionException.Kind.CONTENT_CONFLICT,
                    "calibration version '" + params.version()
                            + "' is already registered with different content; "
                            + "calibration content is immutable per version identifier");
        }
        activeVersion = params.version();
    }

    public CalibrationParams resolve(String version) {
        if (version == null || version.isBlank()) {
            throw new CalibrationVersionException(CalibrationVersionException.Kind.VERSION_MISSING,
                    "calibration version must be specified and must not be blank");
        }
        CalibrationParams params = versions.get(version);
        if (params == null) {
            throw new CalibrationVersionException(CalibrationVersionException.Kind.VERSION_UNKNOWN,
                    "unknown calibration version: " + version);
        }
        return params;
    }

    public CalibrationParams resolveActive() {
        String version = activeVersion;
        if (version == null) {
            throw new CalibrationVersionException(CalibrationVersionException.Kind.VERSION_MISSING,
                    "no calibration version has been registered yet");
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
