package com.github.guoyyit.stereodepthestimationjava.stereo;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe store of versioned calibration parameters.
 *
 * <p>Publishing a new version never mutates previously published
 * versions, so frames already in flight keep observing the exact
 * parameter set they started with. Looking up an unknown version is
 * rejected with a {@link CalibrationException}.</p>
 */
public final class CalibrationRegistry {
    private final Map<String, CalibrationParams> byVersion = new ConcurrentHashMap<>();
    private final AtomicReference<CalibrationParams> current = new AtomicReference<>();

    /**
     * Publishes a parameter set and makes it the current version.
     *
     * @throws CalibrationException if the version was already published
     *                              with different content
     */
    public void publish(CalibrationParams params) {
        if (params == null) {
            throw new CalibrationException("cannot publish null calibration");
        }
        CalibrationParams existing = byVersion.putIfAbsent(params.version(), params);
        if (existing != null && !existing.equals(params)) {
            throw new CalibrationException("calibration version '" + params.version()
                    + "' already published with different content; version mixing is rejected");
        }
        current.set(params);
    }

    /**
     * Returns the version to be used by frames starting now.
     *
     * @throws CalibrationException if nothing was published yet
     */
    public CalibrationParams current() {
        CalibrationParams params = current.get();
        if (params == null) {
            throw new CalibrationException("no calibration version published");
        }
        return params;
    }

    /**
     * Returns the parameter set for an explicit version.
     *
     * @throws CalibrationException if the version is unknown
     */
    public CalibrationParams require(String version) {
        return find(version).orElseThrow(() ->
                new CalibrationException("unknown calibration version '" + version + "'"));
    }

    public Optional<CalibrationParams> find(String version) {
        return Optional.ofNullable(byVersion.get(version));
    }
}
