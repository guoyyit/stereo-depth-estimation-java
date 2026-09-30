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

    public void register(CalibrationParams params) {
        if (params == null) {
            throw new CalibrationVersionException("calibration params must not be null");
        }
        versions.put(params.version(), params);
        activeVersion = params.version();
    }

    public CalibrationParams resolve(String version) {
        if (version == null || version.isBlank()) {
            throw new CalibrationVersionException(
                    "calibration version must be specified and must not be blank");
        }
        CalibrationParams params = versions.get(version);
        if (params == null) {
            throw new CalibrationVersionException("unknown calibration version: " + version);
        }
        return params;
    }

    public CalibrationParams resolveActive() {
        String version = activeVersion;
        if (version == null) {
            throw new CalibrationVersionException("no calibration version has been registered yet");
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
