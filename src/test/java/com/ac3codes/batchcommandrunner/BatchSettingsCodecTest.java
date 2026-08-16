package com.ac3codes.batchcommandrunner;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the config key-precedence/migration rules directly, without Gson or Fabric's
 * FabricLoader - both of which BatchConfig (client source set) needs for real file I/O, but
 * which aren't necessary to verify the actual migration logic itself.
 */
class BatchSettingsCodecTest {

    private static BatchSettingsCodec.SettingsLookup lookup(Map<String, Object> data) {
        return new BatchSettingsCodec.SettingsLookup() {
            @Override
            public int getInt(String key, int fallback) {
                Object value = data.get(key);
                return value instanceof Number number ? number.intValue() : fallback;
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                Object value = data.get(key);
                return value instanceof Boolean bool ? bool : fallback;
            }
        };
    }

    @Test
    void decodesTheCurrentSchemaDirectly() {
        BatchSettings settings = BatchSettingsCodec.decode(lookup(Map.of(
                "normalDelay", 5,
                "heavyCommandProtection", false,
                "fillMinimum", 11,
                "cloneMinimum", 12,
                "placeMinimum", 21,
                "summonMinimum", 3
        )));
        assertEquals(new BatchSettings(5, false, 11, 12, 21, 3), settings);
    }

    @Test
    void migratesOldSafeFillModeConfigWhenNewKeysAreAbsent() {
        // The exact example from the spec: {"safeFillMode": true, "fillDelay": 15}.
        BatchSettings settings = BatchSettingsCodec.decode(lookup(Map.of(
                "safeFillMode", true,
                "fillDelay", 15
        )));
        assertEquals(BatchSettings.DEFAULT.normalDelay(), settings.normalDelay());
        assertTrue(settings.heavyCommandProtection());
        assertEquals(15, settings.fillMinimum());
        assertEquals(BatchSettings.DEFAULT.cloneMinimum(), settings.cloneMinimum());
        assertEquals(BatchSettings.DEFAULT.placeMinimum(), settings.placeMinimum());
        assertEquals(BatchSettings.DEFAULT.summonMinimum(), settings.summonMinimum());
    }

    @Test
    void migratesOldSafeFillModeDisabled() {
        BatchSettings settings = BatchSettingsCodec.decode(lookup(Map.of("safeFillMode", false)));
        assertFalse(settings.heavyCommandProtection());
    }

    @Test
    void newKeyWinsWhenBothOldAndNewKeysArePresent() {
        BatchSettings settings = BatchSettingsCodec.decode(lookup(Map.of(
                "heavyCommandProtection", false,
                "safeFillMode", true,
                "fillMinimum", 99,
                "fillDelay", 1
        )));
        assertFalse(settings.heavyCommandProtection(), "the new heavyCommandProtection key must win over the old safeFillMode key");
        assertEquals(99, settings.fillMinimum(), "the new fillMinimum key must win over the old fillDelay key");
    }

    @Test
    void missingKeysFallBackToDefaults() {
        BatchSettings settings = BatchSettingsCodec.decode(lookup(Map.of()));
        assertEquals(BatchSettings.DEFAULT, settings);
    }

    @Test
    void unusableValueTypesFallBackToDefaultsRatherThanThrowing() {
        // Simulates a config file where a field somehow holds the wrong JSON type.
        BatchSettings settings = BatchSettingsCodec.decode(lookup(Map.of(
                "normalDelay", "not a number",
                "heavyCommandProtection", "not a boolean"
        )));
        assertEquals(BatchSettings.DEFAULT.normalDelay(), settings.normalDelay());
        assertEquals(BatchSettings.DEFAULT.heavyCommandProtection(), settings.heavyCommandProtection());
    }
}
