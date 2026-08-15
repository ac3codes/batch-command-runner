package com.ac3codes.batchcommandrunner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BatchCommandScreen extends Screen {
    private static final int MAX_SUGGESTIONS_SHOWN = 6;

    private static String savedText = "";
    private static String savedDelay = "1";

    private final Screen parent;

    private MultiLineEditBox commandBox;
    private EditBox delayBox;
    private Button runButton;
    private Button stopButton;
    private Button clearButton;

    private int parsedCommandCount;

    // Backing model of commandBox, unwrapped via reflection since MultiLineEditBox exposes
    // no public cursor/line API of its own — needed to drive command-suggestion lookups.
    private MultilineTextField editModel;
    private List<Suggestion> currentSuggestions = List.of();
    private int suggestionIndex;
    private int currentLineStart;
    private CompletableFuture<Suggestions> pendingSuggestions;
    private String lastSuggestionValue;
    private int lastSuggestionCursor = -1;
    private boolean cyclingActive;
    private int cycleAbsoluteStart;
    private int cycleInsertedLength;
    private boolean suppressNextAutoRefresh;

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
            if (!CommandBatchRunner.isRunning() && CommandBatchRunner.status() != CommandBatchRunner.Status.IDLE) {
                CommandBatchRunner.reset();
            }
        });
        parsedCommandCount = CommandBatchRunner.parseCommands(savedText).size();
        this.addRenderableWidget(commandBox);
        this.editModel = extractTextField(commandBox);
        clearSuggestions();

        // Extra clearance below the box's own built-in "X / Y characters" decoration
        // (rendered by MultiLineEditBox itself just under its bottom edge).
        int infoY = top + editorHeight + this.font.lineHeight + 9;
        delayBox = new EditBox(this.font, margin + 48, infoY - 5, 55, 18, Component.literal("Delay ticks"));
        delayBox.setValue(savedDelay);
        delayBox.setResponder(value -> {
            if (value.isEmpty() || value.matches("\\d{0,6}")) {
                savedDelay = value;
            } else {
                delayBox.setValue(savedDelay);
            }
        });
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

    private static MultilineTextField extractTextField(MultiLineEditBox box) {
        try {
            Field field = MultiLineEditBox.class.getDeclaredField("textField");
            field.setAccessible(true);
            return (MultilineTextField) field.get(box);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
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
        clearSuggestions();
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
        updateSuggestions();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isCycleFocus() && commandBox.isFocused() && !CommandBatchRunner.isRunning() && !currentSuggestions.isEmpty()) {
            acceptSuggestion();
            return true;
        }
        // hasControlDownWithQuirk() resolves to Cmd on macOS and Ctrl elsewhere, matching
        // the modifier Minecraft already uses for its own copy/paste/select-all shortcuts.
        if ((event.key() == 257 || event.key() == 335) && event.hasControlDownWithQuirk()) {
            if (runButton.active) {
                runCommands();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private void updateSuggestions() {
        if (editModel == null || this.minecraft == null || this.minecraft.player == null
                || CommandBatchRunner.isRunning() || !commandBox.isFocused()) {
            clearSuggestions();
            return;
        }

        String value = editModel.value();
        int cursor = editModel.cursor();
        if (value.equals(lastSuggestionValue) && cursor == lastSuggestionCursor) {
            return;
        }
        if (suppressNextAutoRefresh) {
            suppressNextAutoRefresh = false;
            lastSuggestionValue = value;
            lastSuggestionCursor = cursor;
            return;
        }

        lastSuggestionValue = value;
        lastSuggestionCursor = cursor;
        cyclingActive = false;

        int lineStart = value.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int lineEndSearch = value.indexOf('\n', cursor);
        int lineEnd = lineEndSearch < 0 ? value.length() : lineEndSearch;
        String line = value.substring(lineStart, lineEnd);
        int cursorInLine = cursor - lineStart;
        currentLineStart = lineStart;

        if (line.isBlank()) {
            pendingSuggestions = null;
            currentSuggestions = List.of();
            suggestionIndex = 0;
            return;
        }

        StringReader reader = new StringReader(line);
        if (reader.canRead() && reader.peek() == '/') {
            reader.skip();
        }

        CommandDispatcher<ClientSuggestionProvider> dispatcher = this.minecraft.player.connection.getCommands();
        ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(reader, this.minecraft.player.connection.getSuggestionsProvider());

        // Brigadier can't locate a suggestion context before the point it actually parsed to
        // (throws IllegalStateException) — only ask once the cursor has reached that point.
        if (cursorInLine < reader.getCursor()) {
            pendingSuggestions = null;
            currentSuggestions = List.of();
            suggestionIndex = 0;
            return;
        }

        CompletableFuture<Suggestions> future = dispatcher.getCompletionSuggestions(parse, cursorInLine);
        pendingSuggestions = future;
        future.thenAccept(result -> {
            if (pendingSuggestions == future) {
                currentSuggestions = result.getList();
                suggestionIndex = 0;
            }
        });
    }

    private void clearSuggestions() {
        pendingSuggestions = null;
        currentSuggestions = List.of();
        suggestionIndex = 0;
        cyclingActive = false;
        lastSuggestionValue = null;
        lastSuggestionCursor = -1;
    }

    private void acceptSuggestion() {
        int index = cyclingActive ? (suggestionIndex + 1) % currentSuggestions.size() : 0;
        Suggestion suggestion = currentSuggestions.get(index);

        int absoluteStart = cyclingActive ? cycleAbsoluteStart : currentLineStart + suggestion.getRange().getStart();
        int absoluteEnd = cyclingActive ? cycleAbsoluteStart + cycleInsertedLength : currentLineStart + suggestion.getRange().getEnd();

        editModel.seekCursor(Whence.ABSOLUTE, absoluteStart);
        editModel.setSelecting(true);
        editModel.seekCursor(Whence.ABSOLUTE, absoluteEnd);
        editModel.insertText(suggestion.getText());
        editModel.setSelecting(false);

        suggestionIndex = index;
        cyclingActive = true;
        cycleAbsoluteStart = absoluteStart;
        cycleInsertedLength = suggestion.getText().length();
        suppressNextAutoRefresh = true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        int editorBottom = commandBox.getBottom();
        int infoY = editorBottom + this.font.lineHeight + 9;

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
        } else if (!currentSuggestions.isEmpty()) {
            renderSuggestions(graphics, nextY);
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

    private void renderSuggestions(GuiGraphicsExtractor graphics, int nextY) {
        MutableComponent line = Component.empty();
        int shown = Math.min(currentSuggestions.size(), MAX_SUGGESTIONS_SHOWN);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                line.append(Component.literal("  "));
            }
            ChatFormatting color = i == suggestionIndex ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
            line.append(Component.literal(currentSuggestions.get(i).getText()).withStyle(color));
        }
        if (currentSuggestions.size() > shown) {
            line.append(Component.literal(" …").withStyle(ChatFormatting.DARK_GRAY));
        }
        line.append(Component.literal("  [Tab]").withStyle(ChatFormatting.DARK_GRAY));

        int left = commandBox.getX();
        int right = commandBox.getRight();
        graphics.fill(left, nextY - 3, right, nextY + this.font.lineHeight + 3, 0xA0304050);
        graphics.text(this.font, line, left + 4, nextY, 0xFFFFFFFF, true);
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
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
