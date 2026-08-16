package com.ac3codes.batchcommandrunner;

/**
 * Pure precedence/migration logic for turning a generic key-value settings lookup into a
 * {@link BatchSettings}: the current {@code heavyCommandProtection}/{@code fillMinimum} keys
 * win when present, falling back to the pre-1.1.1 Safe Fill Mode keys ({@code safeFillMode}/
 * {@code fillDelay}), and finally to {@link BatchSettings#DEFAULT}. Kept dependency-free (no
 * Gson, no Fabric) so this can be unit tested directly - {@code BatchConfig} (client source
 * set) adapts its parsed JSON into a {@link SettingsLookup} and calls {@link #decode}; the
 * actual file I/O and JSON parsing stay there.
 */
public final class BatchSettingsCodec {

    private BatchSettingsCodec() {
    }

    /** A generic "get this key, or a fallback if it's absent/unusable" source - deliberately
     * narrower than a full JSON object so it doesn't pull in a JSON library dependency here. */
    public interface SettingsLookup {
        int getInt(String key, int fallback);

        boolean getBoolean(String key, boolean fallback);
    }

    public static BatchSettings decode(SettingsLookup lookup) {
        int normalDelay = lookup.getInt("normalDelay", BatchSettings.DEFAULT.normalDelay());
        // heavyCommandProtection is the current key; safeFillMode is the pre-1.1.1 name for the
        // same on/off switch, kept as a fallback so old configs still behave correctly.
        boolean heavyCommandProtection = lookup.getBoolean("heavyCommandProtection",
                lookup.getBoolean("safeFillMode", BatchSettings.DEFAULT.heavyCommandProtection()));
        // Likewise fillMinimum replaces the old fillDelay key.
        int fillMinimum = lookup.getInt("fillMinimum",
                lookup.getInt("fillDelay", BatchSettings.DEFAULT.fillMinimum()));
        int cloneMinimum = lookup.getInt("cloneMinimum", BatchSettings.DEFAULT.cloneMinimum());
        int placeMinimum = lookup.getInt("placeMinimum", BatchSettings.DEFAULT.placeMinimum());
        int summonMinimum = lookup.getInt("summonMinimum", BatchSettings.DEFAULT.summonMinimum());

        return new BatchSettings(normalDelay, heavyCommandProtection, fillMinimum, cloneMinimum, placeMinimum, summonMinimum);
    }
}
