package com.ac3codes.batchcommandrunner;

import java.util.List;

/**
 * Pure sequential-batch-execution state machine: dispatch/wait timing, pause/resume/stop
 * transitions, and status bookkeeping. Deliberately has no dependency on Minecraft - the one
 * genuinely game-dependent operation, actually sending a command, is abstracted behind
 * {@link CommandSender} and supplied by the caller - so this class can be unit tested directly,
 * the same way {@link CommandUtils} is.
 *
 * <p>{@code CommandBatchRunner} (in the client source set) is a thin wrapper around a single
 * instance of this class: it supplies the real {@code CommandSender} (via the player's
 * connection), performs the player/connection null-checks for disconnect handling, and owns all
 * logging. This class only knows about the state machine itself.
 */
public final class BatchRunnerState {

    public enum Status {
        IDLE("Not active"),
        RUNNING("Running"),
        PAUSED("Paused"),
        COMPLETED("Completed"),
        STOPPED("Stopped"),
        ERROR("Error");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Sends exactly one command. May throw for any reason (connection rejected it, etc.) -
     * {@link #tick} catches this and transitions to {@link Status#ERROR} rather than propagating. */
    @FunctionalInterface
    public interface CommandSender {
        void send(String command) throws Exception;
    }

    private List<BatchEntry> entries = List.of();
    private BatchSettings settings = BatchSettings.DEFAULT;
    private int nextIndex = -1;
    private int completedCount = 0;
    private int ticksUntilNext = 0;
    private Status status = Status.IDLE;
    private String lastError = "";

    // Set when a command is dispatched, purely for status display - tick() itself only ever
    // needs nextIndex/ticksUntilNext to decide what to do.
    private int lastDispatchedIndex = -1;
    private boolean lastDispatchWasProtected = false;
    private int lastDispatchedEffectiveDelay = 0;

    public void start(List<BatchEntry> newEntries, BatchSettings newSettings) {
        entries = List.copyOf(newEntries);
        settings = newSettings;
        completedCount = 0;
        ticksUntilNext = 0;
        lastError = "";
        lastDispatchedIndex = -1;
        lastDispatchWasProtected = false;
        lastDispatchedEffectiveDelay = 0;

        if (entries.isEmpty()) {
            nextIndex = -1;
            status = Status.IDLE;
            return;
        }

        nextIndex = 0;
        status = Status.RUNNING;
    }

    /** Pauses a running batch. The command that was about to send stays queued at the same
     * remaining delay - resuming does not lose or rush that wait.
     * @return true if this call actually paused a running batch. */
    public boolean pause() {
        if (status == Status.RUNNING) {
            status = Status.PAUSED;
            return true;
        }
        return false;
    }

    /** @return true if this call actually resumed a paused batch. */
    public boolean resume() {
        if (status == Status.PAUSED) {
            status = Status.RUNNING;
            return true;
        }
        return false;
    }

    /** @return true if this call actually stopped an active (running or paused) batch. */
    public boolean stop() {
        boolean wasActive = status == Status.RUNNING || status == Status.PAUSED;
        if (wasActive) {
            status = Status.STOPPED;
        }
        nextIndex = -1;
        ticksUntilNext = 0;
        return wasActive;
    }

    public void reset() {
        entries = List.of();
        nextIndex = -1;
        completedCount = 0;
        ticksUntilNext = 0;
        status = Status.IDLE;
        lastError = "";
        lastDispatchedIndex = -1;
        lastDispatchWasProtected = false;
        lastDispatchedEffectiveDelay = 0;
    }

    /**
     * Advances the state machine by one tick. Does nothing unless {@link Status#RUNNING} -
     * which is what keeps a paused batch's remaining delay frozen instead of counting down in
     * the background. Otherwise either decrements the current wait, or dispatches the next
     * command via {@code sender} and computes its effective delay.
     */
    public void tick(CommandSender sender) {
        if (status != Status.RUNNING) {
            return;
        }

        if (nextIndex < 0 || nextIndex >= entries.size()) {
            finish();
            return;
        }

        if (ticksUntilNext > 0) {
            ticksUntilNext--;
            return;
        }

        BatchEntry entry = entries.get(nextIndex);
        try {
            sender.send(entry.command());
        } catch (Exception e) {
            fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        int effectiveDelay = CommandUtils.calculateEffectiveDelay(entry, settings);
        lastDispatchedIndex = nextIndex;
        lastDispatchedEffectiveDelay = effectiveDelay;
        lastDispatchWasProtected = settings.heavyCommandProtection() && effectiveDelay > settings.normalDelay();

        completedCount++;
        nextIndex++;

        if (nextIndex >= entries.size()) {
            finish();
        } else {
            ticksUntilNext = effectiveDelay;
        }
    }

    /** Transitions to {@link Status#ERROR}. Public so the Minecraft-facing wrapper can report a
     * failure that happens outside of {@link #tick} (no player/connection) using the same
     * state, without duplicating the error-state fields here. */
    public void fail(String message) {
        status = Status.ERROR;
        lastError = message;
        nextIndex = -1;
        ticksUntilNext = 0;
    }

    private void finish() {
        status = Status.COMPLETED;
        nextIndex = -1;
        ticksUntilNext = 0;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public boolean isActive() {
        return status == Status.RUNNING || status == Status.PAUSED;
    }

    public Status status() {
        return status;
    }

    public int totalCount() {
        return entries.size();
    }

    public int completedCount() {
        return completedCount;
    }

    /** The entry most recently sent, kept for logging/debugging only - UI code should use
     * {@link #nextEntry()} instead (see its doc for why). Null before the first command of a
     * batch has been sent. */
    public BatchEntry currentEntry() {
        return lastDispatchedIndex >= 0 && lastDispatchedIndex < entries.size() ? entries.get(lastDispatchedIndex) : null;
    }

    /** The index (into the started batch) of the entry most recently sent, or -1 before
     * anything has been sent. Kept for logging/debugging only - see {@link #nextEntryIndex()}
     * for the UI-facing equivalent. Callers that need to relate this back to a raw line number
     * in the original editor text (blank/comment lines aren't entries) must build that mapping
     * themselves - this class only knows about the classified entries. */
    public int currentEntryIndex() {
        return lastDispatchedIndex;
    }

    /** The entry that will be sent next, once its delay elapses - what the UI should highlight
     * and display, rather than {@link #currentEntry()} (the last one already sent). Equal to
     * entry #1 right after {@link #start}, before anything has actually been dispatched yet, and
     * null once there is nothing left to send (batch not active, or {@link Status#COMPLETED}). */
    public BatchEntry nextEntry() {
        return nextIndex >= 0 && nextIndex < entries.size() ? entries.get(nextIndex) : null;
    }

    /** The index (into the started batch) of the entry that will be sent next, or -1 if there is
     * none. UI-facing equivalent of {@link #currentEntryIndex()} - see {@link #nextEntry()}. */
    public int nextEntryIndex() {
        return nextIndex;
    }

    /** Whether the most recently sent command's delay was raised above the plain normal delay
     * by Heavy Command Protection. */
    public boolean isCurrentCommandProtected() {
        return lastDispatchWasProtected;
    }

    /** The resolved effective delay (ticks) applied after the most recently dispatched command -
     * fixed for that command, unlike {@link #ticksUntilNext()} which counts down from it. */
    public int currentCommandDelay() {
        return lastDispatchedEffectiveDelay;
    }

    public BatchSettings settings() {
        return settings;
    }

    public int ticksUntilNext() {
        return ticksUntilNext;
    }

    public String lastError() {
        return lastError;
    }
}
