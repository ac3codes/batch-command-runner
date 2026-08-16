package com.ac3codes.batchcommandrunner;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.List;

/**
 * Thin Minecraft-facing wrapper around a single {@link BatchRunnerState}: supplies the one
 * genuinely game-dependent operation - actually sending a command, via the player's connection -
 * plus the player/connection null-checks for defensive disconnect handling, and all logging.
 * The dispatch/timing/pause/resume/stop state machine itself lives entirely in
 * {@link BatchRunnerState}, which is dependency-free specifically so it can be unit tested
 * without a live Minecraft client (see {@code BatchRunnerStateTest}).
 *
 * <p>All state here is static because exactly one batch can ever be running for the single
 * client player, and the runner needs to keep ticking (via {@link #tick}) whether or not the
 * {@code BatchCommandScreen} is currently open.
 */
public final class CommandBatchRunner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BatchRunnerState state = new BatchRunnerState();

    private CommandBatchRunner() {
    }

    public static void start(List<BatchEntry> entries, BatchSettings settings) {
        state.start(entries, settings);
        if (state.status() == BatchRunnerState.Status.RUNNING) {
            LOGGER.info("[BatchCommandRunner] Starting batch: {} commands", state.totalCount());
            if (settings.heavyCommandProtection()) {
                LOGGER.info("[BatchCommandRunner] Heavy Command Protection enabled");
                LOGGER.info("[BatchCommandRunner] Fill minimum={}, Clone minimum={}, Place minimum={}, Summon minimum={}",
                        settings.fillMinimum(), settings.cloneMinimum(), settings.placeMinimum(), settings.summonMinimum());
            }
        }
    }

    /** Pauses a running batch. The command that was about to send stays queued at the same
     * remaining delay - resuming does not lose or rush that wait. */
    public static void pause() {
        if (state.pause()) {
            LOGGER.info("[BatchCommandRunner] Batch paused at {}/{}", state.completedCount(), state.totalCount());
        }
    }

    public static void resume() {
        if (state.resume()) {
            LOGGER.info("[BatchCommandRunner] Batch resumed at {}/{}", state.completedCount(), state.totalCount());
        }
    }

    public static void stop() {
        if (state.stop()) {
            LOGGER.info("[BatchCommandRunner] Batch stopped at {}/{}", state.completedCount(), state.totalCount());
        }
    }

    public static void reset() {
        state.reset();
    }

    public static void tick(Minecraft client) {
        if (state.status() != BatchRunnerState.Status.RUNNING) {
            return;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            state.fail("No active player.");
            logFailure();
            return;
        }
        ClientPacketListener connection = player.connection;
        if (connection == null) {
            state.fail("No active server connection.");
            logFailure();
            return;
        }

        int completedBefore = state.completedCount();
        // ClientPacketListener#sendCommand expects the command WITHOUT a leading slash.
        state.tick(connection::sendCommand);

        if (state.completedCount() > completedBefore) {
            BatchEntry sent = state.currentEntry();
            LOGGER.debug("[BatchCommandRunner] Sent #{} ({}): {}", state.completedCount(), sent.type(), sent.command());
        }

        switch (state.status()) {
            case COMPLETED -> {
                LOGGER.info("[BatchCommandRunner] Batch complete: {} commands", state.totalCount());
                notifyCompletion(player);
            }
            case ERROR -> logFailure();
            default -> {
            }
        }
    }

    private static void logFailure() {
        LOGGER.warn("[BatchCommandRunner] Batch stopped with error at {}/{}: {}",
                state.completedCount(), state.totalCount(), state.lastError());
    }

    private static void notifyCompletion(LocalPlayer player) {
        player.sendSystemMessage(
                Component.literal("BCR --> You: List of Commands Completed")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
        );
    }

    public static boolean isRunning() {
        return state.isRunning();
    }

    public static boolean isActive() {
        return state.isActive();
    }

    public static BatchRunnerState.Status status() {
        return state.status();
    }

    public static int totalCount() {
        return state.totalCount();
    }

    public static int completedCount() {
        return state.completedCount();
    }

    /** The entry most recently sent to the server, kept for logging/debugging only - UI code
     * should use {@link #nextEntry()} instead. Null before the first command of a batch has
     * been sent. */
    public static BatchEntry currentEntry() {
        return state.currentEntry();
    }

    /** The index of the entry most recently sent, or -1 before anything has been sent. Kept for
     * logging/debugging only - see {@link #nextEntryIndex()} for the UI-facing equivalent. */
    public static int currentEntryIndex() {
        return state.currentEntryIndex();
    }

    /** The entry that will be sent next, once its delay elapses - what the UI should highlight
     * and display. Equal to entry #1 right after a batch starts, before anything has actually
     * been dispatched yet, and null once there's nothing left to send. */
    public static BatchEntry nextEntry() {
        return state.nextEntry();
    }

    /** The index of the entry that will be sent next, or -1 if there is none. */
    public static int nextEntryIndex() {
        return state.nextEntryIndex();
    }

    /** Whether the most recently sent command's delay was raised above the plain normal delay
     * by Heavy Command Protection. */
    public static boolean isCurrentCommandProtected() {
        return state.isCurrentCommandProtected();
    }

    /** The resolved effective delay (ticks) that was applied after the most recently
     * dispatched command - fixed for that command, unlike {@link #ticksUntilNext()} which
     * counts down from it. */
    public static int currentCommandDelay() {
        return state.currentCommandDelay();
    }

    public static BatchSettings settings() {
        return state.settings();
    }

    public static int ticksUntilNext() {
        return state.ticksUntilNext();
    }

    public static String lastError() {
        return state.lastError();
    }
}
