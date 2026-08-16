package com.ac3codes.batchcommandrunner;

/**
 * A single classified command in a batch, produced once (when the batch is started)
 * so the runner never has to re-parse or re-classify a command while it executes.
 *
 * @param command        the command text with a single leading slash stripped (never blank);
 *                        this is always the full original text as typed - if it's an
 *                        {@code execute ... run <command>} chain, that whole chain is kept
 *                        here so it's sent to the server unchanged, even though {@link #type}
 *                        reflects the nested command actually being run
 * @param type            the kind of command, used to look up which Heavy Command Protection
 *                        minimum delay (if any) applies
 * @param estimatedWork   best-effort size estimate (block count) for absolute-coordinate FILL/
 *                        CLONE commands, or {@code -1} if the type has no such estimate, or its
 *                        size couldn't be reliably determined (relative/local coordinates,
 *                        selectors, malformed input, etc.)
 */
public record BatchEntry(String command, CommandType type, long estimatedWork) {

    public boolean hasEstimatedWork() {
        return estimatedWork >= 0;
    }
}
