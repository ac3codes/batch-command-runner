package com.ac3codes.batchcommandrunner;

/**
 * User-configurable batch execution settings. Immutable so a snapshot can be safely handed to
 * the runner when a batch starts without worrying about the UI mutating it mid-run.
 *
 * @param normalDelay             client ticks to wait after an ordinary command, before sending the next one
 * @param heavyCommandProtection  when enabled, commands classified as FILL/CLONE/PLACE/SUMMON
 *                                wait at least their own configured minimum (see below)
 * @param fillMinimum             minimum ticks after a /fill command when protection is on
 * @param cloneMinimum            minimum ticks after a /clone command when protection is on
 * @param placeMinimum            minimum ticks after a /place command when protection is on
 * @param summonMinimum           minimum ticks after a /summon command when protection is on
 */
public record BatchSettings(
        int normalDelay,
        boolean heavyCommandProtection,
        int fillMinimum,
        int cloneMinimum,
        int placeMinimum,
        int summonMinimum
) {

    /** Upper bound for {@link #normalDelay}. 1200 ticks = one minute at 20 TPS, well past any practical need. */
    public static final int MAX_NORMAL_DELAY = 1200;
    /** Upper bound for each protected-type minimum, per the requested 0-200 tick range. */
    public static final int MAX_MINIMUM_DELAY = 200;

    public static final BatchSettings DEFAULT = new BatchSettings(1, true, 10, 10, 20, 2);

    public BatchSettings {
        normalDelay = Math.clamp(normalDelay, 0, MAX_NORMAL_DELAY);
        fillMinimum = Math.clamp(fillMinimum, 0, MAX_MINIMUM_DELAY);
        cloneMinimum = Math.clamp(cloneMinimum, 0, MAX_MINIMUM_DELAY);
        placeMinimum = Math.clamp(placeMinimum, 0, MAX_MINIMUM_DELAY);
        summonMinimum = Math.clamp(summonMinimum, 0, MAX_MINIMUM_DELAY);
    }

    /** The configured minimum delay for a command of the given type, or {@code 0} for NORMAL
     * (which never has a protection minimum - it always just uses {@link #normalDelay}). */
    public int minimumFor(CommandType type) {
        return switch (type) {
            case FILL -> fillMinimum;
            case CLONE -> cloneMinimum;
            case PLACE -> placeMinimum;
            case SUMMON -> summonMinimum;
            case NORMAL -> 0;
        };
    }
}
