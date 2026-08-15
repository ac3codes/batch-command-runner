package com.ac3codes.batchcommandrunner;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class CommandBatchRunner {
    public enum Status {
        IDLE("Idle"),
        RUNNING("Running"),
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

    private static List<String> commands = List.of();
    private static int nextIndex = -1;
    private static int completedCount = 0;
    private static int delayTicks = 1;
    private static int ticksUntilNext = 0;
    private static Status status = Status.IDLE;
    private static String lastError = "";

    private CommandBatchRunner() {
    }

    public static List<String> parseCommands(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            while (line.startsWith("/")) {
                line = line.substring(1).trim();
            }
            if (!line.isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    public static void start(List<String> newCommands, int newDelayTicks) {
        commands = List.copyOf(newCommands);
        delayTicks = Math.max(0, newDelayTicks);
        completedCount = 0;
        ticksUntilNext = 0;
        lastError = "";

        if (commands.isEmpty()) {
            nextIndex = -1;
            status = Status.IDLE;
            return;
        }

        nextIndex = 0;
        status = Status.RUNNING;
    }

    public static void stop() {
        if (status == Status.RUNNING) {
            status = Status.STOPPED;
        }
        nextIndex = -1;
        ticksUntilNext = 0;
    }

    public static void reset() {
        commands = List.of();
        nextIndex = -1;
        completedCount = 0;
        ticksUntilNext = 0;
        status = Status.IDLE;
        lastError = "";
    }

    public static void tick(Minecraft client) {
        if (status != Status.RUNNING) {
            return;
        }

        if (client.player == null || client.player.connection == null) {
            status = Status.ERROR;
            lastError = "No active player/server connection.";
            nextIndex = -1;
            return;
        }

        if (nextIndex < 0 || nextIndex >= commands.size()) {
            finish();
            return;
        }

        if (ticksUntilNext > 0) {
            ticksUntilNext--;
            return;
        }

        String command = commands.get(nextIndex);
        try {
            // ClientPacketListener#sendCommand expects the command WITHOUT a leading slash.
            client.player.connection.sendCommand(command);
        } catch (Exception e) {
            status = Status.ERROR;
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            nextIndex = -1;
            return;
        }

        completedCount++;
        nextIndex++;

        if (nextIndex >= commands.size()) {
            finish();
        } else {
            // A value of 1 inserts one full client tick between command sends.
            ticksUntilNext = delayTicks;
        }
    }

    private static void finish() {
        status = Status.COMPLETED;
        nextIndex = -1;
        ticksUntilNext = 0;
    }

    public static boolean isRunning() {
        return status == Status.RUNNING;
    }

    public static Status status() {
        return status;
    }

    public static int totalCount() {
        return commands.size();
    }

    public static int completedCount() {
        return completedCount;
    }

    public static int nextCommandNumber() {
        return nextIndex >= 0 ? nextIndex + 1 : -1;
    }

    public static String nextCommand() {
        return nextIndex >= 0 && nextIndex < commands.size() ? commands.get(nextIndex) : null;
    }

    public static int delayTicks() {
        return delayTicks;
    }

    public static int ticksUntilNext() {
        return ticksUntilNext;
    }

    public static String lastError() {
        return lastError;
    }
}
