package com.ac3codes.batchcommandrunner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the sequential-batch state machine directly - no Minecraft client, player, or
 * connection needed, since {@link BatchRunnerState} only depends on {@link BatchRunnerState.CommandSender}
 * for the one operation that would otherwise touch live game state. This is what actually
 * verifies dispatch/wait timing, pause/resume/stop transitions, and disconnect-style failure
 * handling - the things CommandBatchRunner itself (client source set) can't be unit tested for.
 */
class BatchRunnerStateTest {

    private static BatchEntry normal(String command) {
        return new BatchEntry(command, CommandType.NORMAL, -1);
    }

    private static final BatchSettings NO_PROTECTION = new BatchSettings(2, false, 10, 10, 20, 2);

    /** Records every command handed to it, in order; never throws. */
    private static final class RecordingSender implements BatchRunnerState.CommandSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(String command) {
            sent.add(command);
        }
    }

    // ---- empty / single-command batches ------------------------------------------------------

    @Test
    void emptyBatchNeverEntersRunningState() {
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(), NO_PROTECTION);
        assertEquals(BatchRunnerState.Status.IDLE, state.status());
        assertFalse(state.isActive());

        // Ticking an idle/empty batch must be a safe no-op.
        RecordingSender sender = new RecordingSender();
        state.tick(sender);
        assertTrue(sender.sent.isEmpty());
        assertEquals(BatchRunnerState.Status.IDLE, state.status());
    }

    @Test
    void oneCommandBatchCompletesOnTheFirstTickWithNoWait() {
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say hi")), NO_PROTECTION);
        assertEquals(BatchRunnerState.Status.RUNNING, state.status());

        RecordingSender sender = new RecordingSender();
        state.tick(sender);

        assertEquals(List.of("say hi"), sender.sent);
        assertEquals(BatchRunnerState.Status.COMPLETED, state.status());
        assertEquals(1, state.completedCount());
        assertEquals(1, state.totalCount());
    }

    // ---- dispatch timing -----------------------------------------------------------------------

    @Test
    void firstCommandDispatchesImmediatelyRegardlessOfConfiguredDelay() {
        BatchSettings settings = new BatchSettings(50, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender);

        // The delay only applies BETWEEN commands, never before the first one.
        assertEquals(List.of("say a"), sender.sent);
    }

    @Test
    void exactlyDelayTicksElapseBetweenCommandsForVariousDelays() {
        for (int delay : new int[]{0, 1, 3}) {
            BatchSettings settings = new BatchSettings(delay, false, 10, 10, 20, 2);
            BatchRunnerState state = new BatchRunnerState();
            state.start(List.of(normal("say a"), normal("say b")), settings);

            RecordingSender sender = new RecordingSender();
            state.tick(sender); // dispatches "say a" immediately
            assertEquals(1, sender.sent.size(), "delay=" + delay);

            // Exactly `delay` ticks must pass with nothing sent before the next dispatch.
            for (int i = 0; i < delay; i++) {
                state.tick(sender);
                assertEquals(1, sender.sent.size(), "delay=" + delay + " tick " + i + " must not send yet");
            }
            state.tick(sender);
            assertEquals(List.of("say a", "say b"), sender.sent, "delay=" + delay);
        }
    }

    @Test
    void batchCompletesImmediatelyAfterTheLastCommandWithNoTrailingWait() {
        BatchSettings settings = new BatchSettings(5, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say only")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender);

        assertEquals(BatchRunnerState.Status.COMPLETED, state.status());
        assertEquals(0, state.ticksUntilNext());
    }

    @Test
    void multiCommandBatchDispatchesInOrderAndCompletes() {
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        List<BatchEntry> entries = List.of(normal("say a"), normal("say b"), normal("say c"));
        state.start(entries, settings);

        RecordingSender sender = new RecordingSender();
        for (int i = 0; i < entries.size(); i++) {
            state.tick(sender);
        }

        assertEquals(List.of("say a", "say b", "say c"), sender.sent);
        assertEquals(BatchRunnerState.Status.COMPLETED, state.status());
        assertEquals(3, state.completedCount());
    }

    // ---- pause / resume / stop -----------------------------------------------------------------

    @Test
    void pauseFreezesTheCountdownInsteadOfLettingItRunInTheBackground() {
        BatchSettings settings = new BatchSettings(5, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a", ticksUntilNext = 5
        state.tick(sender); // decrements to 4
        assertEquals(4, state.ticksUntilNext());

        assertTrue(state.pause());
        assertEquals(BatchRunnerState.Status.PAUSED, state.status());

        // Many ticks while paused must not move the countdown or send anything.
        for (int i = 0; i < 10; i++) {
            state.tick(sender);
        }
        assertEquals(4, state.ticksUntilNext(), "paused countdown must not decrement");
        assertEquals(1, sender.sent.size(), "nothing may be sent while paused");

        assertTrue(state.resume());
        assertEquals(BatchRunnerState.Status.RUNNING, state.status());
        // Countdown continues from exactly where it was frozen: 4 decrementing ticks (4,3,2,1)
        // followed by the dispatch tick once it reaches 0 - same "N delay ticks then dispatch"
        // shape verified in exactlyDelayTicksElapseBetweenCommandsForVariousDelays.
        for (int i = 0; i < 5; i++) {
            state.tick(sender);
        }
        assertEquals(List.of("say a", "say b"), sender.sent);
    }

    @Test
    void pauseImmediatelyAfterDispatchFreezesTheFreshlySetDelay() {
        BatchSettings settings = new BatchSettings(7, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a", ticksUntilNext = 7
        assertTrue(state.pause());

        for (int i = 0; i < 20; i++) {
            state.tick(sender);
        }
        assertEquals(7, state.ticksUntilNext());
        assertEquals(1, sender.sent.size());
    }

    @Test
    void stopDuringCountdownPreventsAllRemainingCommands() {
        BatchSettings settings = new BatchSettings(5, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b"), normal("say c")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a"
        state.tick(sender); // waiting

        assertTrue(state.stop());
        assertEquals(BatchRunnerState.Status.STOPPED, state.status());

        for (int i = 0; i < 20; i++) {
            state.tick(sender);
        }
        assertEquals(List.of("say a"), sender.sent, "nothing after Stop may ever be sent");
    }

    @Test
    void stopImmediatelyAfterDispatchPreventsTheNextCommand() {
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a"
        assertTrue(state.stop());

        state.tick(sender);
        assertEquals(List.of("say a"), sender.sent);
    }

    @Test
    void rapidPauseResumeCyclesNeverLoseOrDuplicateProgress() {
        BatchSettings settings = new BatchSettings(3, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a"

        // Several pause/resume calls back to back, as if the button were double/triple-clicked,
        // with no ticks in between - only the last transition should matter.
        assertTrue(state.pause());
        assertFalse(state.pause(), "pausing an already-paused batch is a no-op");
        assertTrue(state.resume());
        assertTrue(state.pause());
        assertTrue(state.resume());

        assertEquals(BatchRunnerState.Status.RUNNING, state.status());
        assertEquals(1, state.completedCount(), "rapid toggling must not duplicate or skip dispatches");
        assertEquals(3, state.ticksUntilNext(), "rapid toggling must not perturb the countdown");
    }

    @Test
    void stopFollowedByStartBeginsACleanBatch() {
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a"
        state.stop();

        // A fresh Run press with different text must not carry over any old progress/entries.
        state.start(List.of(normal("say new")), settings);
        assertEquals(BatchRunnerState.Status.RUNNING, state.status());
        assertEquals(0, state.completedCount());
        assertEquals(1, state.totalCount());

        state.tick(sender);
        assertEquals(List.of("say a", "say new"), sender.sent);
        assertEquals(BatchRunnerState.Status.COMPLETED, state.status());
    }

    // ---- failure handling -----------------------------------------------------------------------

    @Test
    void senderThrowingTransitionsToErrorAndStopsTheBatch() {
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), settings);

        BatchRunnerState.CommandSender failingSender = command -> {
            throw new IllegalStateException("connection closed");
        };
        state.tick(failingSender);

        assertEquals(BatchRunnerState.Status.ERROR, state.status());
        assertTrue(state.lastError().contains("IllegalStateException"));
        assertEquals(0, state.completedCount(), "the failed command must not count as completed");

        // Further ticks after an error must be safe no-ops, not repeated failures.
        state.tick(failingSender);
        assertEquals(BatchRunnerState.Status.ERROR, state.status());
    }

    @Test
    void explicitFailReportsDisconnectStyleFailureWithoutTicking() {
        // Mirrors what CommandBatchRunner does when client.player or player.connection is null.
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a")), settings);

        state.fail("No active player.");

        assertEquals(BatchRunnerState.Status.ERROR, state.status());
        assertEquals("No active player.", state.lastError());

        RecordingSender sender = new RecordingSender();
        state.tick(sender);
        assertTrue(sender.sent.isEmpty(), "a batch that failed to disconnect must never resume sending");
    }

    // ---- current-entry / protection status accessors -------------------------------------------

    @Test
    void currentEntryIsNullBeforeAnythingHasBeenSent() {
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a")), NO_PROTECTION);
        assertNull(state.currentEntry());
        assertEquals(-1, state.currentEntryIndex());
        assertFalse(state.isCurrentCommandProtected());
    }

    @Test
    void currentEntryIndexTracksTheJustDispatchedPosition() {
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b"), normal("say c")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender);
        assertEquals(0, state.currentEntryIndex());
        state.tick(sender);
        assertEquals(1, state.currentEntryIndex());
        state.tick(sender);
        assertEquals(2, state.currentEntryIndex());
    }

    @Test
    void currentEntryAndProtectionFlagReflectTheJustDispatchedCommand() {
        BatchSettings settings = new BatchSettings(0, true, 10, 10, 20, 2);
        BatchEntry fill = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, 8);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(fill), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender);

        assertEquals(fill, state.currentEntry());
        assertTrue(state.isCurrentCommandProtected());
        assertEquals(10, state.currentCommandDelay());
    }

    @Test
    void protectionFlagIsFalseWhenNormalDelayAlreadyExceedsTheMinimum() {
        BatchSettings settings = new BatchSettings(50, true, 10, 10, 20, 2);
        BatchEntry fill = new BatchEntry("fill 0 0 0 1 1 1 stone", CommandType.FILL, 8);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(fill, normal("say done")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender);

        assertEquals(50, state.currentCommandDelay());
        assertFalse(state.isCurrentCommandProtected(), "delay came from normalDelay, not protection");
    }

    // ---- next/completed progress numbering (UI's "Next #i / n" and "Completed: i / n") --------

    @Test
    void nextAndCompletedProgressionMatchesExecutableIndexNotDispatchCount() {
        // Mirrors the "all valid" worked example: three normal commands, checking the exact
        // (nextEntryIndex()+1, totalCount()) and (completedCount(), totalCount()) pairs the UI
        // renders as "Next #i / n" and "Completed: i / n" at each step.
        BatchSettings settings = new BatchSettings(0, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say one"), normal("say two"), normal("say three")), settings);

        assertEquals(0, state.completedCount());
        assertEquals(1, state.nextEntryIndex() + 1, "before execution, command #1 is next");
        assertEquals(3, state.totalCount());

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say one"
        assertEquals(1, state.completedCount());
        assertEquals(2, state.nextEntryIndex() + 1);

        state.tick(sender); // dispatches "say two"
        assertEquals(2, state.completedCount());
        assertEquals(3, state.nextEntryIndex() + 1);

        state.tick(sender); // dispatches "say three" - batch completes
        assertEquals(3, state.completedCount());
        assertEquals(BatchRunnerState.Status.COMPLETED, state.status());
        assertNull(state.nextEntry(), "no pending entry once completed - UI shows \"Next: none\"");
    }

    @Test
    void nextEntryIsNullAfterStopMidBatch() {
        BatchSettings settings = new BatchSettings(5, false, 10, 10, 20, 2);
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b"), normal("say c")), settings);

        RecordingSender sender = new RecordingSender();
        state.tick(sender); // dispatches "say a"
        state.stop();

        assertNull(state.nextEntry(), "stopping mid-batch must clear the pending entry - UI shows \"Next: none\"");
        assertEquals(-1, state.nextEntryIndex());
        assertEquals(1, state.completedCount());
    }

    @Test
    void resetReturnsToIdleAndClearsProgress() {
        BatchRunnerState state = new BatchRunnerState();
        state.start(List.of(normal("say a"), normal("say b")), NO_PROTECTION);
        state.tick(new RecordingSender());

        state.reset();

        assertEquals(BatchRunnerState.Status.IDLE, state.status());
        assertEquals(0, state.totalCount());
        assertEquals(0, state.completedCount());
        assertNull(state.currentEntry());
    }
}
