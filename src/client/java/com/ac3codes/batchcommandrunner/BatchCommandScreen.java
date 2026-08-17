package com.ac3codes.batchcommandrunner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BatchCommandScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] MINIMUM_LABELS = {"Fill:", "Clone:", "Place:", "Summon:"};
    private static final int MINIMUM_BOX_WIDTH = 34;
    private static final int MINIMUM_GROUP_GAP = 14;
    // Fixed (rather than grown/shrunk to the batch's actual current line count) so the gutter's
    // width - and by extension the editor's own X position and wrap width - never shifts under
    // the user while they're typing. 3 digits (up to 999 lines) comfortably covers realistic
    // batches without reserving space for commandBox.setLineLimit(20_000)'s full worst case,
    // which read as a wide, mostly-empty column for any normal-sized batch. A batch that grows
    // past 999 lines still runs and highlights correctly - only the gutter's own number label for
    // those rows may run out of room (see renderLineNumbers's relaxed left scissor bound, which
    // lets an overflowing label spill left into the margin rather than getting clipped).
    private static final int LINE_NUMBER_GUTTER_DIGITS = 3;
    // Padding inside the line-number gutter panel, matching AbstractTextAreaWidget's own fixed
    // 4px inner padding so the numbers sit as comfortably inside their panel as commandBox's own
    // text does inside its border, rather than crowding either edge.
    private static final int GUTTER_INNER_PADDING = 4;
    // The same two sprites AbstractTextAreaWidget itself uses for a text field's own border/
    // background (see its BACKGROUND_SPRITES) - reused by renderEditorPanel() to draw ONE such
    // border spanning both the line-number gutter and commandBox together (which is built with
    // showBackground(false) so it doesn't draw a second, narrower one over just its own portion),
    // so the two read as a single editor with an internal divider rather than two boxes glued
    // together. Focused/unfocused matches commandBox.isFocused(), same as any other text field.
    private static final Identifier GUTTER_PANEL_SPRITE = Identifier.withDefaultNamespace("widget/text_field");
    private static final Identifier GUTTER_PANEL_SPRITE_FOCUSED = Identifier.withDefaultNamespace("widget/text_field_highlighted");
    // Color of the vertical divider between the line-number gutter and the editor text - visible
    // (not just an implied edge from two abutting borders), but muted enough not to compete with
    // the command text itself.
    private static final int GUTTER_DIVIDER_COLOR = 0xFF5A5A5A;
    // AbstractTextAreaWidget#totalInnerPadding() is a fixed 4px-per-side constant (not exposed
    // publicly) that MultilineTextField wraps its text against - mirrored here so this screen's
    // own independently-computed word-wrap (see recomputeEditorLayout) exactly matches the wrap
    // width the editor itself actually uses.
    private static final int EDITOR_TOTAL_INNER_PADDING = 8;

    private static String savedText = "";
    private static BatchSettings savedSettings = BatchConfig.load();
    private static String savedDelayText = String.valueOf(savedSettings.normalDelay());
    private static String savedFillMinimumText = String.valueOf(savedSettings.fillMinimum());
    private static String savedCloneMinimumText = String.valueOf(savedSettings.cloneMinimum());
    private static String savedPlaceMinimumText = String.valueOf(savedSettings.placeMinimum());
    private static String savedSummonMinimumText = String.valueOf(savedSettings.summonMinimum());
    private static boolean heavyProtectionEnabled = savedSettings.heavyCommandProtection();

    private final Screen parent;

    private MultiLineEditBox commandBox;
    private EditBox delayBox;
    private EditBox fillMinimumBox;
    private EditBox cloneMinimumBox;
    private EditBox placeMinimumBox;
    private EditBox summonMinimumBox;
    private Button heavyProtectionButton;
    private Button expandMinimumsButton;
    private Button slashPriorityButton;
    // Collapsed by default: the four per-type minimum fields are secondary tuning, not
    // something that needs to be visible (and competing for space) every time the screen opens.
    private boolean minimumsExpanded;
    private Button runButton;
    private Button stopButton;
    private Button clearButton;
    // Footer button row geometry computed once in init(), reused by updateWidgetStates() to
    // reposition/resize runButton at runtime (see there).
    private int footerButtonX;
    private int footerButtonWidth;
    private int footerButtonGap;
    // Left margin computed in init(), reused by renderLineNumbers() as the left edge of its
    // gutter column (which otherwise draws relative only to commandBox's own X position).
    private int editorMargin;
    // Width of the line-number gutter panel, computed once in init() from LINE_NUMBER_GUTTER_DIGITS
    // so it never has to resize as a batch grows; stored (rather than kept local to init()) since
    // renderLineNumbers() needs it every frame to draw the gutter's own bordered panel.
    private int lineNumberGutterWidth;

    // For each raw (\n-delimited) editor line, the index of the first wrapped visual row
    // MultiLineEditBox actually renders it on, and how many visual rows it occupies (>1 once it
    // wraps). Recomputed once per tick from the editor's current text (see
    // recomputeEditorLayout()) using the same word-wrap MultilineTextField itself applies
    // internally, so every overlay this screen draws on top of the editor - highlights, line
    // numbers, the autocomplete/ghost-hint anchor, and scroll-into-view - follows real wrapping
    // instead of assuming one visual row per raw line, which drifted further out of alignment
    // with the actual rendered text below every wrapped line above it.
    private int[] rawLineFirstVisualRow = {0};
    private int[] rawLineVisualRowCount = {1};

    // Count of executable lines that would actually be queued if Run were pressed right now
    // (blank/comment/empty-after-slash lines excluded, same as CommandUtils.parseEntries), split
    // between what parses/executes and what doesn't - see refreshCommandCounts().
    private int validCommandCount;
    private int invalidCommandCount;
    // Set by the editor's value listener on every edit; the actual recompute (parseEntries +
    // Brigadier validation over every line) happens at most once per client tick rather than
    // once per keystroke, so pasting into or auto-repeat-deleting a large batch can't spike
    // per-frame cost - see tick().
    private boolean commandCountsDirty;
    // Maps a started batch's entry index to the raw (\n-delimited) line number it came from in
    // the editor text, since blank/comment lines are skipped when building entries and so don't
    // line up 1:1 with them. Computed once when Run is pressed; used to highlight/scroll to the
    // line currently executing.
    private int[] entryLineNumbers = new int[0];
    // Raw line numbers whose command isn't something that could actually run - only recomputed
    // on an Enter press or a Run press (never live while typing), and cleared immediately on any
    // edit; used both to highlight them red in the editor and to skip them when Run is pressed
    // (see keyPressed/runCommands/computeInvalidLineNumbers).
    private Set<Integer> invalidLineNumbers = Set.of();

    // Backing model of commandBox, unwrapped via reflection since MultiLineEditBox exposes no
    // public cursor/line API of its own. Extracted once here (not per-tick/per-frame) - see
    // extractTextField() for why this is still the least fragile option available.
    private MultilineTextField editModel;

    // Compact modal tutorial overlay - see its own doc for why this is a plain field rendered/
    // routed to manually by this screen rather than a separate Screen pushed on top of this one.
    private final TutorialPopup tutorialPopup = new TutorialPopup(TutorialPages.ALL);

    private final SuggestionPopup suggestionPopup = new SuggestionPopup();
    private AutocompleteUtil.LineContext currentLineContext;
    private String lastSuggestionValue;
    private int lastSuggestionCursor = -1;
    // Bumped every time suggestions are requested or invalidated; an async result is only
    // applied if this hasn't moved since its request went out, so a slow response for an old
    // keystroke can never clobber what's currently on screen.
    private long suggestionGeneration;
    // Faded "what comes next" preview (e.g. typing "minecraft:dirt_block" shows " <count>" right
    // after it) shown inline after the cursor, built from Brigadier's own getSmartUsage() off
    // the same parse used for the suggestion popup. Empty when there's nothing to show.
    private String ghostHintText = "";

    public BatchCommandScreen(Screen parent) {
        super(Component.literal("Batch Command Runner"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int margin = Math.max(16, this.width / 16);
        this.editorMargin = margin;
        int top = 38;
        // 12px shorter than the space a 4-row status block (Status/Commands/Completed/Next) used
        // to need - the status block below the editor is now only 3 rows (Status/Commands/Current
        // line), and this stays in step so that saved row goes to the editor instead of sitting as
        // unused whitespace above the footer buttons.
        int footerHeight = 146;
        // Reserves a gutter to the left of the editor for line numbers (see renderLineNumbers),
        // sized for up to LINE_NUMBER_GUTTER_DIGITS digits so it never has to resize as a normal
        // batch grows (see that constant's own doc). A small fixed 2px buffer on the outer/left
        // edge, plus GUTTER_INNER_PADDING on the seam/right side - matching the same gap
        // renderLineNumbers leaves between the numbers and the divider - keeps the digits from
        // crowding either edge without reserving more empty column than the digits need.
        this.lineNumberGutterWidth = this.font.width("0".repeat(LINE_NUMBER_GUTTER_DIGITS)) + 2 + GUTTER_INNER_PADDING;
        int editorX = margin + lineNumberGutterWidth;
        int editorWidth = Math.max(220, this.width - margin * 2 - lineNumberGutterWidth);
        int editorHeight = Math.max(90, this.height - top - footerHeight);

        commandBox = MultiLineEditBox.builder()
                .setX(editorX)
                .setY(top)
                .setPlaceholder(Component.literal("Paste commands here — one command per line"))
                // False rather than true: commandBox's own border would only wrap its own
                // portion, reading as two separate boxes touching edge-to-edge. Instead
                // renderEditorPanel() draws one border spanning the gutter and the editor
                // together, with just a plain divider line between the two - see its own doc.
                .setShowBackground(false)
                .setShowDecorations(true)
                .build(this.font, editorWidth, editorHeight, Component.literal("Commands"));
        commandBox.setCharacterLimit(1_000_000);
        commandBox.setLineLimit(20_000);
        commandBox.setValue(savedText, true);
        commandBox.setValueListener(value -> {
            savedText = value;
            // Deferred to the next tick rather than recomputed here - see commandCountsDirty's
            // doc for why.
            commandCountsDirty = true;
            // The editor is only ever editable while paused (see updateWidgetStates) - editing at
            // that point is how a paused batch gets fully terminated: not just halted in place,
            // but reset back to zero and no longer resumable, since the text underneath it may no
            // longer match what was queued.
            if (CommandBatchRunner.status() == BatchRunnerState.Status.PAUSED) {
                CommandBatchRunner.hardStop();
            }
        });
        // A one-time synchronous seed for the initial text, since the listener above wasn't
        // attached yet when setValue() ran a few lines up and so never fired for it.
        refreshCommandCounts(savedText);
        recomputeEditorLayout(savedText);
        this.addRenderableWidget(commandBox);
        this.editModel = extractTextField(commandBox);
        hideSuggestions();

        int settingsY = top + editorHeight + this.font.lineHeight + 9;
        int minimumsY = settingsY + 22;

        delayBox = new EditBox(this.font, editorX + 48, settingsY - 5, 50, 18, Component.literal("Delay ticks"));
        delayBox.setValue(savedDelayText);
        delayBox.setResponder(value -> {
            if (value.isEmpty() || value.matches("\\d{0,4}")) {
                savedDelayText = value;
            } else {
                delayBox.setValue(savedDelayText);
            }
        });
        this.addRenderableWidget(delayBox);

        // Sized from the longer of "ON"/"OFF" so the button never changes width when toggled,
        // and everything placed after it in this row (the expand arrow, slash-priority toggle)
        // has a stable anchor.
        int heavyButtonWidth = this.font.width("Heavy Protection: OFF") + 16;
        int heavyButtonX = editorX + 48 + 50 + 20;
        heavyProtectionButton = Button.builder(heavyProtectionLabel(), btn -> toggleHeavyProtection())
                .bounds(heavyButtonX, settingsY - 5, heavyButtonWidth, 18)
                .build();
        this.addRenderableWidget(heavyProtectionButton);

        expandMinimumsButton = Button.builder(expandMinimumsLabel(), btn -> toggleMinimumsExpanded())
                .bounds(heavyButtonX + heavyButtonWidth + 4, settingsY - 5, 18, 18)
                .build();
        this.addRenderableWidget(expandMinimumsButton);

        // Fixed slot reserved right after the expand button regardless of whether it's currently
        // visible (Heavy Protection off hides it) - simplest way to keep this button's position
        // stable without needing to reposition it at runtime.
        int slashButtonWidth = this.font.width("Slash: Vanilla") + 16;
        int slashButtonX = heavyButtonX + heavyButtonWidth + 4 + 18 + 4;
        slashPriorityButton = Button.builder(slashPriorityLabel(), btn -> toggleSlashPriority())
                .bounds(slashButtonX, settingsY - 5, slashButtonWidth, 18)
                .build();
        this.addRenderableWidget(slashPriorityButton);

        int[] minimumLabelX = minimumLabelX(editorX);
        fillMinimumBox = new EditBox(this.font, minimumLabelX[0] + this.font.width(MINIMUM_LABELS[0]) + 4, minimumsY - 5, MINIMUM_BOX_WIDTH, 18, Component.literal("Fill minimum ticks"));
        cloneMinimumBox = new EditBox(this.font, minimumLabelX[1] + this.font.width(MINIMUM_LABELS[1]) + 4, minimumsY - 5, MINIMUM_BOX_WIDTH, 18, Component.literal("Clone minimum ticks"));
        placeMinimumBox = new EditBox(this.font, minimumLabelX[2] + this.font.width(MINIMUM_LABELS[2]) + 4, minimumsY - 5, MINIMUM_BOX_WIDTH, 18, Component.literal("Place minimum ticks"));
        summonMinimumBox = new EditBox(this.font, minimumLabelX[3] + this.font.width(MINIMUM_LABELS[3]) + 4, minimumsY - 5, MINIMUM_BOX_WIDTH, 18, Component.literal("Summon minimum ticks"));

        fillMinimumBox.setValue(savedFillMinimumText);
        cloneMinimumBox.setValue(savedCloneMinimumText);
        placeMinimumBox.setValue(savedPlaceMinimumText);
        summonMinimumBox.setValue(savedSummonMinimumText);

        fillMinimumBox.setResponder(value -> savedFillMinimumText = sanitizeTicks(value, fillMinimumBox, savedFillMinimumText));
        cloneMinimumBox.setResponder(value -> savedCloneMinimumText = sanitizeTicks(value, cloneMinimumBox, savedCloneMinimumText));
        placeMinimumBox.setResponder(value -> savedPlaceMinimumText = sanitizeTicks(value, placeMinimumBox, savedPlaceMinimumText));
        summonMinimumBox.setResponder(value -> savedSummonMinimumText = sanitizeTicks(value, summonMinimumBox, savedSummonMinimumText));

        this.addRenderableWidget(fillMinimumBox);
        this.addRenderableWidget(cloneMinimumBox);
        this.addRenderableWidget(placeMinimumBox);
        this.addRenderableWidget(summonMinimumBox);

        int buttonY = this.height - 28;
        int buttonWidth = 84;
        int gap = 6;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int buttonX = (this.width - totalWidth) / 2;
        // Stored for updateWidgetStates() to reposition/resize runButton at: it's the one button
        // whose bounds change at runtime (see there), stretching to fill the Pause/Resume slot
        // too whenever that button is hidden (idle - no batch to pause or resume).
        this.footerButtonX = buttonX;
        this.footerButtonWidth = buttonWidth;
        this.footerButtonGap = gap;

        clearButton = Button.builder(Component.literal("Clear"), btn -> clearEditor())
                .bounds(buttonX, buttonY, buttonWidth, 20)
                .build();
        // Pause/Resume is a single button: pressing it while running pauses the batch (and the
        // label switches to "Resume"); pressing it again resumes. It never discards progress -
        // see runButton below for the separate hard-stop action.
        stopButton = Button.builder(Component.literal("Pause"), btn -> onStopResumePressed())
                .bounds(buttonX + (buttonWidth + gap), buttonY, buttonWidth, 20)
                .build();
        // Doubles as the hard-stop control: while a batch is running or paused its label becomes
        // "Stop" and pressing it fully resets progress back to zero (not resumable, unlike the
        // Pause/Resume button) - see onRunOrStopPressed.
        runButton = Button.builder(Component.literal("Run Commands"), btn -> onRunOrStopPressed())
                .bounds(buttonX + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20)
                .build();

        this.addRenderableWidget(clearButton);
        this.addRenderableWidget(stopButton);
        this.addRenderableWidget(runButton);

        // Small utility buttons in the upper-right corner: "?" opens the tutorial popup over
        // this same screen (see TutorialPopup), "X" closes Batch Command Runner exactly like
        // Escape/vanilla back-navigation would (onClose()) - it never touches the batch itself,
        // so a running or paused batch keeps going/stays paused after this screen closes.
        int utilButtonSize = 16;
        int utilGap = 4;
        Button closeScreenButton = Button.builder(Component.literal("X"), btn -> this.onClose())
                .bounds(this.width - 6 - utilButtonSize, 6, utilButtonSize, utilButtonSize)
                .build();
        Button helpButton = Button.builder(Component.literal("?"), btn -> tutorialPopup.open())
                .bounds(closeScreenButton.getX() - utilGap - utilButtonSize, 6, utilButtonSize, utilButtonSize)
                .build();
        this.addRenderableWidget(helpButton);
        this.addRenderableWidget(closeScreenButton);

        updateWidgetStates();
        this.setInitialFocus(commandBox);
    }

    /** Shared x-position layout for the four minimum-delay label+box groups (Fill/Clone/Place/
     * Summon), measured against the current font so labels of different widths never overlap.
     * Used from both {@link #init()} (to place the boxes) and the renderer (to draw the labels
     * at matching positions), so the two can't drift apart. */
    private int[] minimumLabelX(int startX) {
        int[] x = new int[MINIMUM_LABELS.length];
        int cursor = startX;
        for (int i = 0; i < MINIMUM_LABELS.length; i++) {
            x[i] = cursor;
            cursor += this.font.width(MINIMUM_LABELS[i]) + 4 + MINIMUM_BOX_WIDTH + MINIMUM_GROUP_GAP;
        }
        return x;
    }

    private static String sanitizeTicks(String value, EditBox box, String previous) {
        if (value.isEmpty() || value.matches("\\d{0,3}")) {
            return value;
        }
        box.setValue(previous);
        return previous;
    }

    /**
     * MultiLineEditBox stores its MultilineTextField (cursor/line/selection model) in a
     * private field with no accessor, and this Minecraft version exposes no public alternative
     * for cursor position or line data. A narrowly-scoped accessor mixin would work too, but
     * for a single field read once at screen-init time, reflection is the smaller footprint -
     * it needs no mixin plugin wiring and fails safely (see below) if a future Minecraft
     * update ever renames the field, instead of hard-crashing mod loading.
     */
    private static MultilineTextField extractTextField(MultiLineEditBox box) {
        try {
            Field field = MultiLineEditBox.class.getDeclaredField("textField");
            field.setAccessible(true);
            return (MultilineTextField) field.get(box);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[BatchCommandRunner] Could not access MultiLineEditBox's text field; autocomplete will be unavailable.", e);
            return null;
        }
    }

    /**
     * Recomputes {@link #rawLineFirstVisualRow}/{@link #rawLineVisualRowCount} for the given
     * text. Splits on the editor's own line separator first, then independently word-wraps each
     * resulting raw line with the same {@link net.minecraft.client.StringSplitter} and wrap
     * width {@link MultilineTextField#reflowDisplayLines} uses internally - equivalent to that
     * method's own reflow (it treats an explicit {@code \n} as a forced break exactly like a
     * wrapped one) without needing access to its private {@code displayLines} field. Called at
     * most once per tick (see {@link #tick()}), same as {@link #refreshCommandCounts}, rather
     * than once per keystroke.
     */
    private void recomputeEditorLayout(String text) {
        String[] lines = text.isEmpty() ? new String[]{""} : text.split("\n", -1);
        int wrapWidth = Math.max(1, commandBox.getWidth() - EDITOR_TOTAL_INNER_PADDING);
        int[] firstRow = new int[lines.length];
        int[] rowCount = new int[lines.length];
        int visualRow = 0;
        for (int i = 0; i < lines.length; i++) {
            firstRow[i] = visualRow;
            int rows = countWrappedRows(lines[i], wrapWidth);
            rowCount[i] = rows;
            visualRow += rows;
        }
        rawLineFirstVisualRow = firstRow;
        rawLineVisualRowCount = rowCount;
    }

    /** How many visual rows a single raw line (already free of any {@code \n}) wraps onto at
     * {@code wrapWidth} - at least 1, even for an empty line. */
    private int countWrappedRows(String rawLine, int wrapWidth) {
        if (rawLine.isEmpty()) {
            return 1;
        }
        return Math.max(1, this.font.getSplitter().splitLines(rawLine, wrapWidth, Style.EMPTY).size());
    }

    /**
     * Recomputes {@link #invalidLineNumbers} and the valid/invalid executable-command counts
     * from scratch, reusing the exact same parser ({@link CommandUtils#parseEntries}) and
     * Brigadier validation ({@link #computeInvalidLineNumbers}) that {@link #runCommands()}
     * itself relies on - so "Commands: N valid | M invalid" always matches exactly what pressing
     * Run would queue, rather than being computed by a separate counting pass that could drift
     * from it.
     */
    private void refreshCommandCounts(String text) {
        invalidLineNumbers = computeInvalidLineNumbers(text);
        int total = CommandUtils.parseEntries(text).size();
        invalidCommandCount = invalidLineNumbers.size();
        validCommandCount = total - invalidCommandCount;
    }

    private void runCommands() {
        String text = commandBox.getValue();
        // Recomputed fresh rather than trusting the live (tick-deferred) counts, so a stale
        // value can never sneak an invalid command into the batch.
        refreshCommandCounts(text);

        List<BatchEntry> allEntries = CommandUtils.parseEntries(text);
        int[] allLineNumbers = computeEntryLineNumbers(text);

        List<BatchEntry> entries = new ArrayList<>(allEntries.size());
        int[] validLineNumbers = new int[allEntries.size()];
        int validCount = 0;
        for (int i = 0; i < allEntries.size(); i++) {
            if (!invalidLineNumbers.contains(allLineNumbers[i])) {
                entries.add(allEntries.get(i));
                validLineNumbers[validCount++] = allLineNumbers[i];
            }
        }
        int skippedCount = allEntries.size() - entries.size();

        if (entries.isEmpty()) {
            CommandBatchRunner.reset();
            return;
        }
        entryLineNumbers = Arrays.copyOf(validLineNumbers, validCount);
        if (skippedCount > 0) {
            LOGGER.info("[BatchCommandRunner] Skipping {} invalid command(s) before starting batch", skippedCount);
        }

        BatchSettings settings = readSettingsFromUi();
        applySettingsToUi(settings);
        savedText = text;
        BatchConfig.save(settings);

        // Starting a run hides the minimums panel even if it was left open - it can be reopened
        // with the expand button at any time, including while the batch is active.
        minimumsExpanded = false;
        expandMinimumsButton.setMessage(expandMinimumsLabel());

        CommandBatchRunner.start(entries, settings);
        updateWidgetStates();
    }

    /** Mirrors {@link CommandUtils#parseEntries}'s blank/comment/empty-after-slash filtering, so
     * {@code entryLineNumbers[i]} always names the correct raw line for {@code entries.get(i)}. */
    private static int[] computeEntryLineNumbers(String text) {
        if (text == null || text.isBlank()) {
            return new int[0];
        }
        String[] rawLines = text.split("\\R", -1);
        int[] buffer = new int[rawLines.length];
        int count = 0;
        for (int i = 0; i < rawLines.length; i++) {
            String rawLine = rawLines[i];
            if (CommandUtils.isBlankOrComment(rawLine)) {
                continue;
            }
            if (!CommandUtils.stripLeadingSlash(rawLine).isEmpty()) {
                buffer[count++] = i;
            }
        }
        return Arrays.copyOf(buffer, count);
    }

    /**
     * Raw line numbers whose command is not something that could actually be run as-is: either
     * it doesn't fully parse against Minecraft's own command tree (unparsed leftover input -
     * the same signal vanilla's own chat input box uses to color invalid command text red, see
     * {@code CommandSuggestions.formatText}), or it parses but stops short of a node that's
     * actually executable (a required argument is still missing). The latter is checked via
     * {@code getContext().getLastChild().getCommand() == null}, which is safe even for commands
     * this mod doesn't implement locally: the client's own command tree attaches a dummy
     * executor to every server-marked-executable node when it's built from the server's
     * {@code ClientboundCommandsPacket} (see {@code ClientPacketListener.COMMAND_NODE_BUILDER}),
     * so this reflects the server's own notion of "complete", not just vanilla's built-ins.
     * Returns an empty set (rather than guessing) when there's no live connection to validate
     * against yet.
     */
    private Set<Integer> computeInvalidLineNumbers(String text) {
        if (text == null || text.isBlank() || this.minecraft == null || this.minecraft.player == null
                || this.minecraft.player.connection == null) {
            return Set.of();
        }

        CommandDispatcher<ClientSuggestionProvider> dispatcher = this.minecraft.player.connection.getCommands();
        ClientSuggestionProvider source = this.minecraft.player.connection.getSuggestionsProvider();

        Set<Integer> invalid = new HashSet<>();
        String[] rawLines = text.split("\\R", -1);
        for (int i = 0; i < rawLines.length; i++) {
            String rawLine = rawLines[i];
            if (CommandUtils.isBlankOrComment(rawLine)) {
                continue;
            }
            String normalized = CommandUtils.stripLeadingSlash(rawLine);
            if (normalized.isEmpty()) {
                continue;
            }
            ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(normalized, source);
            boolean unparsedLeftover = parse.getReader().canRead();
            boolean notExecutable = parse.getContext().getLastChild().getCommand() == null;
            if (unparsedLeftover || notExecutable) {
                invalid.add(i);
            }
        }
        return invalid;
    }

    private BatchSettings readSettingsFromUi() {
        int normalDelay = parseIntOrDefault(delayBox.getValue(), BatchSettings.DEFAULT.normalDelay());
        int fillMinimum = parseIntOrDefault(fillMinimumBox.getValue(), BatchSettings.DEFAULT.fillMinimum());
        int cloneMinimum = parseIntOrDefault(cloneMinimumBox.getValue(), BatchSettings.DEFAULT.cloneMinimum());
        int placeMinimum = parseIntOrDefault(placeMinimumBox.getValue(), BatchSettings.DEFAULT.placeMinimum());
        int summonMinimum = parseIntOrDefault(summonMinimumBox.getValue(), BatchSettings.DEFAULT.summonMinimum());
        return new BatchSettings(normalDelay, heavyProtectionEnabled, fillMinimum, cloneMinimum, placeMinimum, summonMinimum);
    }

    private static int parseIntOrDefault(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applySettingsToUi(BatchSettings settings) {
        savedSettings = settings;
        savedDelayText = String.valueOf(settings.normalDelay());
        savedFillMinimumText = String.valueOf(settings.fillMinimum());
        savedCloneMinimumText = String.valueOf(settings.cloneMinimum());
        savedPlaceMinimumText = String.valueOf(settings.placeMinimum());
        savedSummonMinimumText = String.valueOf(settings.summonMinimum());
        heavyProtectionEnabled = settings.heavyCommandProtection();
        delayBox.setValue(savedDelayText);
        fillMinimumBox.setValue(savedFillMinimumText);
        cloneMinimumBox.setValue(savedCloneMinimumText);
        placeMinimumBox.setValue(savedPlaceMinimumText);
        summonMinimumBox.setValue(savedSummonMinimumText);
        heavyProtectionButton.setMessage(heavyProtectionLabel());
    }

    private void toggleHeavyProtection() {
        heavyProtectionEnabled = !heavyProtectionEnabled;
        if (!heavyProtectionEnabled) {
            // The expand button is about to be hidden - don't leave the panel stuck open with
            // no way to close it.
            minimumsExpanded = false;
            expandMinimumsButton.setMessage(expandMinimumsLabel());
        }
        heavyProtectionButton.setMessage(heavyProtectionLabel());
        updateWidgetStates();
        BatchSettings settings = readSettingsFromUi();
        savedSettings = settings;
        BatchConfig.save(settings);
    }

    private static Component heavyProtectionLabel() {
        return Component.literal("Heavy Protection: " + (heavyProtectionEnabled ? "ON" : "OFF"));
    }

    /** Whether the batch UI's own "/" keybind wins over vanilla's "Open Command" keybind when
     * the two happen to be bound to the same key - see {@code BatchCommandRunnerClient} for
     * where this is actually applied. Persisted independently of {@link BatchSettings} since
     * it's an unrelated concern (keybind conflict resolution, not batch execution). */
    private void toggleSlashPriority() {
        BatchCommandRunnerClient.setBatchSlashPriority(!BatchCommandRunnerClient.isBatchSlashPriority());
        slashPriorityButton.setMessage(slashPriorityLabel());
    }

    private static Component slashPriorityLabel() {
        return Component.literal("Slash: " + (BatchCommandRunnerClient.isBatchSlashPriority() ? "Batch" : "Vanilla"));
    }

    private void toggleMinimumsExpanded() {
        minimumsExpanded = !minimumsExpanded;
        expandMinimumsButton.setMessage(expandMinimumsLabel());
        updateWidgetStates();
    }

    private Component expandMinimumsLabel() {
        return Component.literal(minimumsExpanded ? "^" : "v");
    }

    private void clearEditor() {
        if (CommandBatchRunner.isActive()) {
            return;
        }
        savedText = "";
        commandBox.setValue("");
        refreshCommandCounts("");
        CommandBatchRunner.reset();
        hideSuggestions();
    }

    private void updateWidgetStates() {
        if (commandBox == null) {
            return;
        }
        BatchRunnerState.Status status = CommandBatchRunner.status();
        boolean active = CommandBatchRunner.isActive();
        boolean running = status == BatchRunnerState.Status.RUNNING;
        boolean paused = status == BatchRunnerState.Status.PAUSED;

        // Only locked while actually RUNNING. While PAUSED, the editor stays scrollable and
        // editable - the queued batch is frozen either way, and any actual edit terminates it
        // (see the value listener) rather than risking a desync with the in-flight run.
        commandBox.active = !running;
        delayBox.active = !active;
        fillMinimumBox.active = !active;
        fillMinimumBox.visible = minimumsExpanded;
        cloneMinimumBox.active = !active;
        cloneMinimumBox.visible = minimumsExpanded;
        placeMinimumBox.active = !active;
        placeMinimumBox.visible = minimumsExpanded;
        summonMinimumBox.active = !active;
        summonMinimumBox.visible = minimumsExpanded;
        heavyProtectionButton.active = !active;
        // The expand toggle (and by extension the minimums panel it reveals) is only meaningful
        // while Heavy Command Protection is actually on.
        expandMinimumsButton.visible = heavyProtectionEnabled;
        // Only relevant - and only shown - while the batch UI's own "/" key and vanilla's "Open
        // Command" key actually collide; with different keys there's nothing to prioritize.
        slashPriorityButton.visible = BatchCommandRunnerClient.isSlashConflict();
        slashPriorityButton.active = !active;
        clearButton.active = !active;
        // RUNNING/PAUSED: [Pause or Resume] [Stop]. Every other state: [Run Commands] alone -
        // stopButton is fully hidden (not just disabled) rather than left visible-but-grayed-out,
        // since there is no resumable batch for it to act on at all in that case.
        runButton.setMessage(Component.literal(active ? "Stop" : "Run Commands"));
        runButton.active = active || validCommandCount > 0;
        stopButton.visible = active;
        stopButton.active = active;
        stopButton.setMessage(Component.literal(paused ? "Resume" : "Pause"));

        // Stretches runButton to fill the Pause/Resume slot too whenever that button is hidden,
        // so idle Run Commands reads as one wide primary action instead of a single narrow
        // button sitting off to the right with an empty gap where Pause/Resume normally is.
        if (active) {
            runButton.setWidth(footerButtonWidth);
            runButton.setX(footerButtonX + (footerButtonWidth + footerButtonGap) * 2);
        } else {
            runButton.setWidth(footerButtonWidth * 2 + footerButtonGap);
            runButton.setX(footerButtonX + (footerButtonWidth + footerButtonGap));
        }
    }

    /** The Pause/Resume button: pauses a running batch, or resumes a paused one, without ever
     * discarding progress. See {@link #onRunOrStopPressed} for the separate hard-stop control. */
    private void onStopResumePressed() {
        if (CommandBatchRunner.status() == BatchRunnerState.Status.PAUSED) {
            CommandBatchRunner.resume();
        } else {
            CommandBatchRunner.pause();
        }
    }

    /** The Run Commands / Stop button: starts a new batch while idle, or - while one is running
     * or paused - fully resets it back to zero instead. Unlike Pause, this is never resumable
     * afterward: the text stays exactly as typed, ready to edit and run again from the start. */
    private void onRunOrStopPressed() {
        if (CommandBatchRunner.isActive()) {
            CommandBatchRunner.hardStop();
            updateWidgetStates();
        } else {
            runCommands();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (commandCountsDirty) {
            String text = commandBox.getValue();
            refreshCommandCounts(text);
            recomputeEditorLayout(text);
            commandCountsDirty = false;
        }
        updateWidgetStates();
        // Skipped while the tutorial is open: input is already fully blocked from reaching the
        // editor at that point (see mouseClicked/keyPressed/charTyped), so there's nothing for a
        // freshly (re)computed suggestion popup to do but sit there wastefully recomputed every
        // tick behind the dimmed overlay.
        if (!tutorialPopup.isOpen()) {
            refreshSuggestionsIfNeeded();
        }
        scrollToCurrentLineIfNeeded();
    }

    /**
     * While a batch is running or paused, keeps the next pending line scrolled into view - the
     * editor is otherwise non-interactive during that time (locked while active; editing to
     * terminate requires stopping/resuming first), so the user has no other way to bring it back
     * on screen. Only scrolls when the line isn't already visible, so it doesn't fight a still-
     * scrollable view once the line is already on screen. Spans the full range of wrapped visual
     * rows the entry's raw line occupies (see {@link #rawLineVisualRowCount}), so a long wrapped
     * command scrolls entirely into view rather than just its first row.
     */
    private void scrollToCurrentLineIfNeeded() {
        if (!CommandBatchRunner.isActive()) {
            return;
        }
        int entryIndex = CommandBatchRunner.nextEntryIndex();
        if (entryIndex < 0 || entryIndex >= entryLineNumbers.length) {
            return;
        }
        int rawLine = entryLineNumbers[entryIndex];
        if (rawLine < 0 || rawLine >= rawLineFirstVisualRow.length) {
            return;
        }
        int rowHeight = rowHeight();
        int lineTop = rawLineFirstVisualRow[rawLine] * rowHeight;
        int lineBottom = lineTop + rawLineVisualRowCount[rawLine] * rowHeight;
        double scrollAmount = commandBox.scrollAmount();
        int viewportHeight = commandBox.getHeight();
        if (lineTop < scrollAmount) {
            commandBox.setScrollAmount(lineTop);
        } else if (lineBottom > scrollAmount + viewportHeight) {
            commandBox.setScrollAmount(lineBottom - viewportHeight);
        }
    }

    /** The vertical distance between successive visual rows in commandBox - matches
     * MultiLineEditBox's own fixed internal row pitch (font.lineHeight, 9px for the default font)
     * exactly, rather than padding it, so overlay math here can never drift from the real
     * rendered rows the way an extra pixel of assumed pitch compounds into visible misalignment
     * over many lines. Kept as one shared method so the highlight/scroll/gutter/popup positioning
     * below can never drift out of sync with each other either. */
    private int rowHeight() {
        return this.font.lineHeight;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Modal: while the tutorial is open, it alone decides what a keypress does (Escape
        // closes just the tutorial, not this whole screen - see its own doc) and every key is
        // consumed here rather than falling through, so nothing below (autocomplete navigation,
        // the Ctrl+Enter run shortcut, or Screen's own Escape-closes-screen handling) can ever
        // see it.
        if (tutorialPopup.isOpen()) {
            return tutorialPopup.keyPressed(event);
        }

        if (suggestionPopup.isVisible() && commandBox.isFocused() && !CommandBatchRunner.isActive()) {
            if (event.isUp()) {
                suggestionPopup.moveSelection(-1);
                return true;
            }
            if (event.isDown()) {
                suggestionPopup.moveSelection(1);
                return true;
            }
            if (event.isCycleFocus()) {
                acceptSuggestion();
                return true;
            }
            if (event.isEscape()) {
                // Hide the popup, but deliberately don't consume the key here - fall through so
                // the same Escape press also closes the screen (matching normal vanilla Escape
                // behavior), instead of requiring a separate second press just to leave the UI.
                hideSuggestions();
            }
        }

        // hasControlDownWithQuirk() resolves to Cmd on macOS and Ctrl elsewhere, matching
        // the modifier Minecraft already uses for its own copy/paste/select-all shortcuts.
        boolean isEnter = event.key() == 257 || event.key() == 335;
        if (isEnter && event.hasControlDownWithQuirk()) {
            if (runButton.active) {
                runCommands();
            }
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Modal: routes every click to the tutorial's own widgets while it's open, so a click
        // can never reach the editor, autocomplete popup, or any of Batch Commander's own
        // buttons underneath - see TutorialPopup's own doc.
        if (tutorialPopup.isOpen()) {
            return tutorialPopup.mouseClicked(event, doubleClick);
        }

        if (suggestionPopup.isVisible()) {
            if (suggestionPopup.isMouseOver(event.x(), event.y())) {
                Suggestion clicked = suggestionPopup.rowAt(event.x(), event.y());
                if (clicked != null) {
                    acceptSuggestion();
                }
                return true;
            }
            hideSuggestions();
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Consumed here rather than falling through - otherwise a wheel scroll anywhere over the
        // (still-rendered-but-dimmed) background would reach and scroll commandBox underneath.
        if (tutorialPopup.isOpen()) {
            return tutorialPopup.mouseScrolled();
        }
        if (suggestionPopup.isVisible() && suggestionPopup.isMouseOver(mouseX, mouseY)) {
            suggestionPopup.scroll(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // commandBox may still be focused from before the tutorial opened - without this, typed
        // characters would keep reaching (and silently editing) it underneath the dimmed overlay.
        if (tutorialPopup.isOpen()) {
            return true;
        }
        return super.charTyped(event);
    }

    /**
     * Refreshes suggestions only when the current line or cursor position actually changed
     * since the last check. {@code editModel.value()} returns the same String instance when
     * nothing has been edited, so the equality check below is a cheap reference comparison in
     * the overwhelmingly common case of "nothing changed this tick" - this is what keeps
     * suggestion lookups from re-running 20 times a second on an idle editor.
     */
    private void refreshSuggestionsIfNeeded() {
        if (editModel == null || this.minecraft == null || this.minecraft.player == null
                || this.minecraft.player.connection == null || CommandBatchRunner.isActive() || !commandBox.isFocused()) {
            hideSuggestions();
            return;
        }

        String value = editModel.value();
        int cursor = editModel.cursor();
        if (value.equals(lastSuggestionValue) && cursor == lastSuggestionCursor) {
            return;
        }
        // Deletions (backspace/delete, including deleting a selection) never (re)open the
        // popup - only typing new characters does. Without this, deleting back through an
        // accepted suggestion immediately reopens the popup for whatever's left, which reads
        // as the autocomplete fighting the user rather than helping them.
        boolean isDeletion = lastSuggestionValue != null && value.length() < lastSuggestionValue.length();
        lastSuggestionValue = value;
        lastSuggestionCursor = cursor;

        if (isDeletion) {
            invalidateSuggestions();
            return;
        }

        try {
            updateSuggestionsFor(value, cursor);
        } catch (RuntimeException e) {
            // Brigadier's suggestion machinery is fragile against a command line that keeps
            // changing shape faster than a request/response round-trip (e.g. Cmd+A then holding
            // Delete across many lines at once) - a stale cursor/parse combination here has
            // previously thrown (IllegalStateException from findSuggestionContext,
            // StringIndexOutOfBoundsException from a since-shrunk line) and, because exceptions
            // escaping Screen#tick are fatal, crashed the whole client. Losing the popup for one
            // keystroke is a fine trade for never taking the game down with it.
            LOGGER.warn("[BatchCommandRunner] Failed to refresh autocomplete suggestions.", e);
            invalidateSuggestions();
        }
    }

    private void updateSuggestionsFor(String value, int cursor) {
        AutocompleteUtil.LineContext context = AutocompleteUtil.extractCurrentLine(value, cursor);
        currentLineContext = context;

        if (context.line().isBlank()) {
            invalidateSuggestions();
            return;
        }

        StringReader reader = new StringReader(context.line());
        if (reader.canRead() && reader.peek() == '/') {
            reader.skip();
        }

        CommandDispatcher<ClientSuggestionProvider> dispatcher = this.minecraft.player.connection.getCommands();
        ClientSuggestionProvider source = this.minecraft.player.connection.getSuggestionsProvider();
        ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(reader, source);

        // Brigadier can't locate a suggestion context before the point it actually parsed to
        // (throws IllegalStateException) - only ask once the cursor has reached that point.
        if (context.cursorInLine() < reader.getCursor()) {
            invalidateSuggestions();
            return;
        }

        ghostHintText = computeGhostHint(dispatcher, parse, context.cursorInLine(), source);

        long generation = ++suggestionGeneration;
        dispatcher.getCompletionSuggestions(parse, context.cursorInLine()).thenAccept(result -> {
            if (generation != suggestionGeneration) {
                return;
            }
            showSuggestions(result, context, value);
        });
    }

    private void showSuggestions(Suggestions result, AutocompleteUtil.LineContext context, String value) {
        List<Suggestion> list = result.getList();
        if (list.isEmpty()) {
            suggestionPopup.hide();
            return;
        }
        int[] anchor = suggestionAnchor(context, value);
        suggestionPopup.show(list, anchor[0], anchor[1], this.font, this.width, this.height);
    }

    /**
     * Approximates where the cursor sits on screen so the popup can appear near it. The vertical
     * row is exact (see {@link #cursorScreenPos}, backed by {@link #rawLineFirstVisualRow}); the
     * horizontal offset still assumes the cursor's own line hasn't itself wrapped before reaching
     * the cursor, which is a reasonable approximation for a popup anchor - the popup always ends
     * up fully on screen either way thanks to the clamp below.
     */
    private int[] suggestionAnchor(AutocompleteUtil.LineContext context, String value) {
        int[] cursorPos = cursorScreenPos(context, value);
        if (cursorPos != null) {
            return new int[]{cursorPos[0], cursorPos[1] + rowHeight()};
        }
        return new int[]{commandBox.getX() + 4, commandBox.getBottom() + 4};
    }

    /**
     * Where the cursor currently sits on screen - or {@code null} if that line isn't currently
     * within the visible/scrolled viewport at all, or {@code context} is no longer valid against
     * {@code value} at all (see below). Shared by the suggestion popup's anchor and the inline
     * ghost-hint text, so the two can never drift apart. The vertical position uses
     * {@link #rawLineFirstVisualRow} so it stays correct even when earlier lines have wrapped
     * onto multiple visual rows; the horizontal position still assumes the current line's own
     * wrapping (if any) hasn't yet been reached by the cursor.
     *
     * <p>{@code context}/{@code ghostHintText} are only refreshed once per tick (see
     * {@link #refreshSuggestionsIfNeeded}), but this is called from every render frame in
     * between - so a fast edit (e.g. Cmd/Ctrl+A then Backspace, clearing the whole box) can
     * shrink {@code value} out from under an already-stale {@code context} before the next tick
     * catches up and invalidates it. Bailing out here once {@code context} no longer fits
     * {@code value} avoids indexing past the end of the now-shorter text.
     */
    private int[] cursorScreenPos(AutocompleteUtil.LineContext context, String value) {
        if (context.lineStart() > value.length()) {
            return null;
        }
        int rawLineNumber = 0;
        for (int i = 0; i < context.lineStart(); i++) {
            if (value.charAt(i) == '\n') {
                rawLineNumber++;
            }
        }

        int lineHeight = rowHeight();
        int innerPad = 4;
        double scrollAmount = commandBox.scrollAmount();
        int visualRow = rawLineNumber < rawLineFirstVisualRow.length ? rawLineFirstVisualRow[rawLineNumber] : rawLineNumber;
        int firstVisibleRow = (int) (scrollAmount / lineHeight);
        int relativeRow = visualRow - firstVisibleRow;
        int approxVisibleRows = Math.max(1, commandBox.getHeight() / lineHeight);
        if (relativeRow < 0 || relativeRow >= approxVisibleRows) {
            return null;
        }

        String textBeforeCursor = context.line().substring(0, Math.min(context.cursorInLine(), context.line().length()));
        int cursorX = Math.min(this.font.width(textBeforeCursor), Math.max(0, commandBox.getWidth() - innerPad * 2));
        return new int[]{commandBox.getX() + innerPad + cursorX, commandBox.getY() + innerPad + relativeRow * lineHeight};
    }

    /**
     * Draws the faded "what comes next" preview (see {@link #computeGhostHint}) inline right
     * after the cursor, scissored to the editor's own bounds. Hidden whenever the suggestion
     * popup is showing, so the two never visually compete for the same spot.
     */
    private void renderGhostHint(GuiGraphicsExtractor graphics) {
        if (ghostHintText.isEmpty() || currentLineContext == null || editModel == null
                || !commandBox.isFocused() || CommandBatchRunner.isActive() || suggestionPopup.isVisible()) {
            return;
        }
        int[] cursorPos = cursorScreenPos(currentLineContext, editModel.value());
        if (cursorPos == null) {
            return;
        }
        graphics.enableScissor(commandBox.getX(), commandBox.getY(), commandBox.getRight(), commandBox.getBottom());
        graphics.text(this.font, ghostHintText, cursorPos[0], cursorPos[1], 0xFF808080, false);
        graphics.disableScissor();
    }

    private void invalidateSuggestions() {
        suggestionGeneration++;
        suggestionPopup.hide();
        ghostHintText = "";
    }

    private void hideSuggestions() {
        suggestionGeneration++;
        suggestionPopup.hide();
        lastSuggestionValue = null;
        lastSuggestionCursor = -1;
        ghostHintText = "";
    }

    /**
     * Builds the faded "what comes next" preview text (e.g. "minecraft:dirt_block" -> " <count>")
     * shown right after the cursor once the token there is fully typed but not yet followed by a
     * separating space. Only returns something once the cursor sits exactly where Brigadier's
     * parse actually stopped (not mid-token), and uses {@link CommandDispatcher#getSmartUsage}
     * off the last matched node - the same node the suggestion popup's own parse arrived at - to
     * ask what a valid continuation from here would look like.
     */
    private String computeGhostHint(CommandDispatcher<ClientSuggestionProvider> dispatcher,
                                     ParseResults<ClientSuggestionProvider> parse, int cursorInLine,
                                     ClientSuggestionProvider source) {
        if (parse.getReader().getCursor() != cursorInLine) {
            return "";
        }
        CommandContextBuilder<ClientSuggestionProvider> ctx = parse.getContext().getLastChild();
        List<ParsedCommandNode<ClientSuggestionProvider>> nodes = ctx.getNodes();
        CommandNode<ClientSuggestionProvider> node = nodes.isEmpty() ? ctx.getRootNode() : nodes.get(nodes.size() - 1).getNode();
        Map<CommandNode<ClientSuggestionProvider>, String> usage = dispatcher.getSmartUsage(node, source);
        if (usage.isEmpty()) {
            return "";
        }
        // A node can have multiple children (e.g. a literal with several sibling arguments/
        // branches) - just showing the first is an acceptable simplification for a hint that's
        // only ever meant as a nudge, not a full breakdown of every possible continuation.
        String hint = usage.values().iterator().next();
        return hint.isBlank() ? "" : " " + hint;
    }

    private void acceptSuggestion() {
        Suggestion suggestion = suggestionPopup.selected();
        if (suggestion == null || editModel == null || currentLineContext == null) {
            return;
        }
        try {
            AutocompleteUtil.Applied applied = AutocompleteUtil.applySuggestion(
                    editModel.value(), currentLineContext,
                    suggestion.getRange().getStart(), suggestion.getRange().getEnd(), suggestion.getText());
            editModel.setValue(applied.newValue(), true);
            editModel.seekCursor(Whence.ABSOLUTE, applied.newCursor());
        } catch (RuntimeException e) {
            LOGGER.warn("[BatchCommandRunner] Failed to apply autocomplete suggestion.", e);
        } finally {
            hideSuggestions();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Must run before super's own widget rendering (which draws commandBox's text/cursor/
        // scrollbar) - this is the shared background the editor's own content then renders on
        // top of, not an overlay.
        renderEditorPanel(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        renderInvalidLineHighlights(graphics);
        renderCurrentLineHighlight(graphics);
        renderGhostHint(graphics);
        renderLineNumbers(graphics);

        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);

        int editorBottom = commandBox.getBottom();
        int settingsY = editorBottom + this.font.lineHeight + 9;
        int minimumsY = settingsY + 22;

        graphics.text(this.font, "Delay:", commandBox.getX(), settingsY, 0xFFFFFFFF, true);

        if (minimumsExpanded) {
            int[] minimumLabelX = minimumLabelX(commandBox.getX());
            for (int i = 0; i < MINIMUM_LABELS.length; i++) {
                graphics.text(this.font, MINIMUM_LABELS[i], minimumLabelX[i], minimumsY, 0xFFFFFFFF, true);
            }
        }

        // When the minimums panel is collapsed (the common case), start the status block right
        // under the settings row instead of leaving its reserved space empty - this is what
        // "moves the status area up" when there's nothing else occupying that row.
        int lineY = (minimumsExpanded ? minimumsY : settingsY) + 20;
        int left = commandBox.getX();
        int right = commandBox.getRight();

        BatchRunnerState.Status status = CommandBatchRunner.status();

        // Status / Commands / Current line are one visually grouped block, always rendered as the
        // same three rows in the same order regardless of state - only the text and color of each
        // row changes - so the layout never jumps as the batch moves between states.
        String statusText = "Status: " + status.label();
        if (status == BatchRunnerState.Status.ERROR && !CommandBatchRunner.lastError().isEmpty()) {
            statusText += " - " + CommandBatchRunner.lastError();
        }
        graphics.text(this.font, trimToWidth(statusText, right - left), left, lineY, statusColor(), true);
        lineY += 12;

        String commandsText = invalidCommandCount > 0
                ? "Commands: " + validCommandCount + " valid | " + invalidCommandCount + " invalid"
                : "Commands: " + validCommandCount;
        graphics.text(this.font, trimToWidth(commandsText, right - left), left, lineY, invalidCommandCount > 0 ? 0xFFFF5555 : 0xFFBBBBBB, true);
        lineY += 12;

        renderCurrentLineStatus(graphics, left, lineY);

        suggestionPopup.render(graphics, this.font);

        // Rendered last so it draws on top of everything above (including the dimmed-but-still-
        // visible Batch Commander UI) - a no-op when the tutorial isn't open.
        tutorialPopup.render(graphics, this.font, this.width, this.height, mouseX, mouseY, delta);
    }

    /** The raw (1-based) editor line number of the command currently highlighted in commandBox -
     * the same line {@link #renderCurrentLineHighlight} marks, and the same numbering
     * {@link #renderLineNumbers}'s gutter itself uses - so this row and the highlight it
     * describes can never drift out of sync. "none" whenever there's no active batch to highlight
     * a line for. */
    private void renderCurrentLineStatus(GuiGraphicsExtractor graphics, int left, int lineY) {
        int entryIndex = CommandBatchRunner.nextEntryIndex();
        String text = CommandBatchRunner.isActive() && entryIndex >= 0 && entryIndex < entryLineNumbers.length
                ? "Current line: " + (entryLineNumbers[entryIndex] + 1)
                : "Current line: none";
        graphics.text(this.font, text, left, lineY, 0xFFEEEEEE, false);
    }

    /**
     * Draws a light-yellow translucent highlight over the next pending line - not the one most
     * recently sent - on top of the editor's own already-rendered text (so it reads as a
     * highlighter mark rather than covering the text). Shown from the moment a batch starts
     * (entry #1, before anything has actually been sent) through its last wait; gone once the
     * batch is no longer active. Spans every wrapped visual row the line occupies - see
     * {@link #fillLineHighlight}.
     */
    private void renderCurrentLineHighlight(GuiGraphicsExtractor graphics) {
        if (!CommandBatchRunner.isActive()) {
            return;
        }
        int entryIndex = CommandBatchRunner.nextEntryIndex();
        if (entryIndex < 0 || entryIndex >= entryLineNumbers.length) {
            return;
        }
        fillLineHighlight(graphics, entryLineNumbers[entryIndex], 0x50FFFF55);
    }

    /**
     * Draws a light-red translucent highlight over every line whose command doesn't parse
     * against Minecraft's own command tree (see {@link #computeInvalidLineNumbers}) - shown at
     * all times, not just while a batch is running, since the whole point is to catch mistakes
     * before pressing Run.
     */
    private void renderInvalidLineHighlights(GuiGraphicsExtractor graphics) {
        for (int rawLine : invalidLineNumbers) {
            fillLineHighlight(graphics, rawLine, 0x50FF5555);
        }
    }

    /**
     * Draws the single bordered panel commandBox and its line-number gutter share (see
     * {@link #GUTTER_PANEL_SPRITE}), spanning from the gutter's left edge to commandBox's right
     * edge as one continuous box - commandBox itself is built with {@code showBackground(false)}
     * so it never draws a second, narrower border over just its own portion. Must run before
     * {@code super.extractRenderState()} (see {@link #extractRenderState}) so it sits behind the
     * editor's own text/cursor/scrollbar instead of covering them; the divider between the two
     * compartments is drawn separately, on top of the already-rendered text, by
     * {@link #renderLineNumbers}.
     */
    private void renderEditorPanel(GuiGraphicsExtractor graphics) {
        Identifier sprite = commandBox.isFocused() ? GUTTER_PANEL_SPRITE_FOCUSED : GUTTER_PANEL_SPRITE;
        int panelLeft = editorMargin;
        int panelTop = commandBox.getY();
        int panelRight = commandBox.getRight();
        int panelBottom = commandBox.getBottom();
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, panelLeft, panelTop, panelRight - panelLeft, panelBottom - panelTop);
    }

    /**
     * Draws IDE-style line numbers (1-based, one per raw {@code \n}-delimited line - blank and
     * comment lines included, same as any code editor's gutter) in the gutter compartment of the
     * shared panel {@link #renderEditorPanel} already drew, plus the visible divider line that
     * separates that compartment from the editor's own text - the same "line numbers on the left,
     * a dividing line, then the code" layout a typical code editor uses, just with the divider
     * actually drawn instead of implied by two boxes touching. A number is only drawn at its raw
     * line's first wrapped visual row (see {@link #rawLineFirstVisualRow}) - the same convention
     * most code editors use - so numbers stay aligned with their actual line no matter how much
     * wrapping happens above them, instead of drifting once any line wraps. Scrolls in lock step
     * with the editor since it shares the same scrollAmount and {@link #rowHeight()}. The right
     * scissor bound is strict, at the divider, so a very long batch never draws numbers over the
     * editor's own text; the left bound is left unclamped (screen edge only) so a line number
     * that overflows the gutter's reserved digit count (see {@link #LINE_NUMBER_GUTTER_DIGITS})
     * spills left into the margin instead of having its leading digit(s) clipped off.
     */
    private void renderLineNumbers(GuiGraphicsExtractor graphics) {
        int rowHeight = rowHeight();
        int innerPad = 4;
        double scrollAmount = commandBox.scrollAmount();
        int boxTop = commandBox.getY();
        int boxBottom = commandBox.getBottom();
        int dividerX = commandBox.getX();
        int gutterRight = dividerX - GUTTER_INNER_PADDING;

        // Inset from the panel's own top/bottom edges so the divider doesn't poke past the
        // sprite border's rounded corners.
        graphics.fill(dividerX, boxTop + 2, dividerX + 1, boxBottom - 2, GUTTER_DIVIDER_COLOR);

        graphics.enableScissor(0, boxTop, dividerX, boxBottom);
        for (int lineIndex = 0; lineIndex < rawLineFirstVisualRow.length; lineIndex++) {
            int visualRow = rawLineFirstVisualRow[lineIndex];
            int lineTop = boxTop + innerPad + (int) Math.round(visualRow * rowHeight - scrollAmount);
            if (lineTop >= boxBottom) {
                break;
            }
            if (lineTop + rowHeight > boxTop) {
                String label = String.valueOf(lineIndex + 1);
                graphics.text(this.font, label, gutterRight - this.font.width(label), lineTop, 0xFF888888, false);
            }
        }
        graphics.disableScissor();
    }

    /** Fills a translucent highlight rectangle over one raw editor line, spanning every wrapped
     * visual row it occupies (see {@link #rawLineVisualRowCount}) as a single contiguous block,
     * scissored to the editor's own bounds so it can never bleed past it, and skipped entirely
     * when that line isn't currently within the visible/scrolled viewport at all. */
    private void fillLineHighlight(GuiGraphicsExtractor graphics, int rawLine, int color) {
        if (rawLine < 0 || rawLine >= rawLineFirstVisualRow.length) {
            return;
        }
        int rowHeight = rowHeight();
        int lineTop = rawLineFirstVisualRow[rawLine] * rowHeight;
        int lineRows = rawLineVisualRowCount[rawLine];
        int innerPad = 4;
        int highlightTop = commandBox.getY() + innerPad + (int) Math.round(lineTop - commandBox.scrollAmount());
        int highlightBottom = highlightTop + lineRows * rowHeight;

        int boxTop = commandBox.getY();
        int boxBottom = commandBox.getBottom();
        if (highlightBottom <= boxTop || highlightTop >= boxBottom) {
            return;
        }

        graphics.enableScissor(commandBox.getX(), boxTop, commandBox.getRight(), boxBottom);
        graphics.fill(commandBox.getX() + 1, highlightTop, commandBox.getRight() - 1, highlightBottom, color);
        graphics.disableScissor();
    }

    private int statusColor() {
        return switch (CommandBatchRunner.status()) {
            case RUNNING -> 0xFFFFFF55;
            case PAUSED -> 0xFF55CCFF;
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
        BatchSettings settings = readSettingsFromUi();
        applySettingsToUi(settings);
        BatchConfig.save(settings);
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
