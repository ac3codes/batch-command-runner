package com.ac3codes.batchcommandrunner;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class BatchCommandScreen extends Screen {
    private static String savedText = "";
    private static String savedDelay = "1";

    private final Screen parent;

    private MultiLineEditBox commandBox;
    private EditBox delayBox;
    private Button runButton;
    private Button stopButton;
    private Button clearButton;

    private int parsedCommandCount;

    public BatchCommandScreen(Screen parent) {
        super(Component.literal("Batch Command Runner"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int margin = Math.max(16, this.width / 16);
        int top = 38;
        int footerHeight = 94;
        int editorWidth = Math.max(220, this.width - margin * 2);
        int editorHeight = Math.max(90, this.height - top - footerHeight);

        commandBox = MultiLineEditBox.builder()
                .setX(margin)
                .setY(top)
                .setPlaceholder(Component.literal("Paste commands here — one command per line"))
                .setShowBackground(true)
                .setShowDecorations(true)
                .build(this.font, editorWidth, editorHeight, Component.literal("Commands"));
        commandBox.setCharacterLimit(1_000_000);
        commandBox.setLineLimit(20_000);
        commandBox.setValue(savedText, true);
        commandBox.setValueListener(value -> {
            savedText = value;
            parsedCommandCount = CommandBatchRunner.parseCommands(value).size();
        });
        parsedCommandCount = CommandBatchRunner.parseCommands(savedText).size();
        this.addRenderableWidget(commandBox);

        int infoY = top + editorHeight + 9;
        delayBox = new EditBox(this.font, margin + 48, infoY - 5, 55, 18, Component.literal("Delay ticks"));
        delayBox.setValue(savedDelay);
        delayBox.setFilter(value -> value.isEmpty() || value.matches("\\d{0,6}"));
        delayBox.setResponder(value -> savedDelay = value);
        this.addRenderableWidget(delayBox);

        int buttonY = this.height - 28;
        int buttonWidth = 92;
        int gap = 8;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int buttonX = (this.width - totalWidth) / 2;

        clearButton = Button.builder(Component.literal("Clear"), btn -> clearEditor())
                .bounds(buttonX, buttonY, buttonWidth, 20)
                .build();
        stopButton = Button.builder(Component.literal("Stop"), btn -> CommandBatchRunner.stop())
                .bounds(buttonX + buttonWidth + gap, buttonY, buttonWidth, 20)
                .build();
        runButton = Button.builder(Component.literal("Run Commands"), btn -> runCommands())
                .bounds(buttonX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20)
                .build();

        this.addRenderableWidget(clearButton);
        this.addRenderableWidget(stopButton);
        this.addRenderableWidget(runButton);

        updateWidgetStates();
        this.setInitialFocus(commandBox);
    }

    private void runCommands() {
        List<String> commands = CommandBatchRunner.parseCommands(commandBox.getValue());
        if (commands.isEmpty()) {
            CommandBatchRunner.reset();
            return;
        }

        int delay = 1;
        try {
            if (!delayBox.getValue().isBlank()) {
                delay = Integer.parseInt(delayBox.getValue());
            }
        } catch (NumberFormatException ignored) {
            delay = 1;
        }
        delay = Math.max(0, Math.min(1_000_000, delay));
        savedDelay = Integer.toString(delay);
        delayBox.setValue(savedDelay);

        savedText = commandBox.getValue();
        CommandBatchRunner.start(commands, delay);
        updateWidgetStates();
    }

    private void clearEditor() {
        if (CommandBatchRunner.isRunning()) {
            return;
        }
        savedText = "";
        commandBox.setValue("");
        parsedCommandCount = 0;
        CommandBatchRunner.reset();
    }

    private void updateWidgetStates() {
        if (commandBox == null) {
            return;
        }
        boolean running = CommandBatchRunner.isRunning();
        commandBox.active = !running;
        delayBox.active = !running;
        clearButton.active = !running;
        runButton.active = !running && parsedCommandCount > 0;
        stopButton.active = running;
    }

    @Override
    public void tick() {
        super.tick();
        parsedCommandCount = CommandBatchRunner.parseCommands(commandBox.getValue()).size();
        updateWidgetStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF, true);

        int editorBottom = commandBox.getBottom();
        int infoY = editorBottom + 9;

        graphics.text(this.font, "Delay:", commandBox.getX(), infoY, 0xFFFFFFFF, true);
        graphics.text(this.font, "ticks", commandBox.getX() + 108, infoY, 0xFFBBBBBB, false);

        int countX = commandBox.getX() + 155;
        String countText = "Commands: " + parsedCommandCount;
        graphics.text(this.font, countText, countX, infoY, 0xFFFFFFFF, true);

        String statusText = "Status: " + CommandBatchRunner.status().label();
        int statusX = this.width - commandBox.getX() - this.font.width(statusText);
        graphics.text(this.font, statusText, statusX, infoY, statusColor(), true);

        int nextY = infoY + 19;
        String nextCommand = CommandBatchRunner.nextCommand();
        if (nextCommand != null) {
            int nextNumber = CommandBatchRunner.nextCommandNumber();
            int total = CommandBatchRunner.totalCount();
            String prefix = "NEXT #" + nextNumber + " / " + total + ": ";
            String full = prefix + nextCommand;

            int left = commandBox.getX();
            int right = commandBox.getRight();
            graphics.fill(left, nextY - 3, right, nextY + this.font.lineHeight + 3, 0xA0604A00);
            graphics.text(this.font, trimToWidth(full, right - left - 8), left + 4, nextY, 0xFFFFFF55, true);
        } else {
            graphics.text(this.font, "Next: none", commandBox.getX(), nextY, 0xFFAAAAAA, false);
        }

        if (CommandBatchRunner.status() == CommandBatchRunner.Status.RUNNING) {
            String progress = "Completed: " + CommandBatchRunner.completedCount() + " / " + CommandBatchRunner.totalCount();
            graphics.text(this.font, progress, commandBox.getX(), nextY + 16, 0xFFBBBBBB, false);
        } else if (CommandBatchRunner.status() == CommandBatchRunner.Status.ERROR) {
            String error = "Error: " + CommandBatchRunner.lastError();
            graphics.text(this.font, trimToWidth(error, commandBox.getWidth()), commandBox.getX(), nextY + 16, 0xFFFF7777, true);
        }
    }

    private int statusColor() {
        return switch (CommandBatchRunner.status()) {
            case RUNNING -> 0xFFFFFF55;
            case COMPLETED -> 0xFF55FF55;
            case STOPPED -> 0xFFFFAA55;
            case ERROR -> 0xFFFF5555;
            case IDLE -> 0xFFBBBBBB;
        };
    }

    private String trimToWidth(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int target = Math.max(0, maxWidth - this.font.width(ellipsis));
        return this.font.plainSubstrByWidth(text, target) + ellipsis;
    }

    @Override
    public void onClose() {
        savedText = commandBox.getValue();
        savedDelay = delayBox.getValue();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
