package com.ac3codes.batchcommandrunner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Compact, page-based tutorial popup rendered as an overlay on top of {@link BatchCommandScreen}
 * - deliberately NOT its own {@code Screen}, so the batch UI underneath stays mounted (and
 * visible, just dimmed) the entire time this is open, rather than being replaced by it. Batch
 * execution itself is driven by {@link CommandBatchRunner#tick} from the client's own end-tick
 * event, independently of whatever screen is open, so nothing here needs to know or care whether
 * a batch is running while the tutorial is up.
 *
 * <p>This class owns its own small widget set (Prev/Next/Close {@link Button}s) but never
 * registers them with {@code BatchCommandScreen}'s widget list - they're rendered and clicked
 * directly by this class instead, only while {@link #isOpen()}. That's what makes the popup
 * modal: {@code BatchCommandScreen} checks {@link #isOpen()} first in every one of its own input
 * handlers and, while open, routes the event here instead of to its own widgets - see its own
 * mouseClicked/keyPressed/mouseScrolled/charTyped overrides.
 *
 * <p>Two different "X" buttons exist in this feature and must not be confused: the main screen's
 * own close button (added by {@code BatchCommandScreen#init}) closes Batch Command Runner
 * entirely, exactly like Escape normally would. {@link #closeButton} here - this class's own X -
 * only calls {@link #close()}, dismissing the tutorial and returning to Batch Commander exactly
 * as it was.
 */
public final class TutorialPopup {
    private static final int OVERLAY_COLOR = 0x90000000;
    private static final int PANEL_BACKGROUND = 0xF0101010;
    private static final int PANEL_BORDER = 0xFF3F3F3F;
    private static final int VISUAL_BACKGROUND = 0xFF1E1E1E;
    private static final int VISUAL_BORDER = 0xFF555555;
    private static final int TITLE_COLOR = 0xFFFFFF55;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int PAGE_COUNTER_COLOR = 0xFFAAAAAA;

    private static final int PADDING = 10;
    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int NAV_BUTTON_WIDTH = 40;
    private static final int NAV_ROW_HEIGHT = 20;

    private final List<TutorialPage> pages;
    private boolean open;
    private int pageIndex;

    private final Button prevButton;
    private final Button nextButton;
    private final Button closeButton;

    // Popup panel bounds, recomputed every render() call from the current screen size (see
    // layout()) rather than cached from init() - so a window resize while the tutorial is open
    // reflows it immediately instead of leaving it positioned for the old screen size.
    private int panelX, panelY, panelWidth, panelHeight;

    public TutorialPopup(List<TutorialPage> pages) {
        this.pages = pages;
        this.prevButton = Button.builder(Component.literal("<"), btn -> previousPage()).bounds(0, 0, NAV_BUTTON_WIDTH, NAV_ROW_HEIGHT).build();
        this.nextButton = Button.builder(Component.literal(">"), btn -> nextPage()).bounds(0, 0, NAV_BUTTON_WIDTH, NAV_ROW_HEIGHT).build();
        this.closeButton = Button.builder(Component.literal("X"), btn -> close()).bounds(0, 0, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE).build();
    }

    public void open() {
        open = true;
        pageIndex = 0;
        updateNavButtons();
    }

    /** Closes only the tutorial - returns immediately to Batch Commander in whatever state it
     * was already in. Never touches {@link CommandBatchRunner} in any way. */
    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    private void nextPage() {
        if (pageIndex < pages.size() - 1) {
            pageIndex++;
        }
        updateNavButtons();
    }

    private void previousPage() {
        if (pageIndex > 0) {
            pageIndex--;
        }
        updateNavButtons();
    }

    private void updateNavButtons() {
        prevButton.active = pageIndex > 0;
        nextButton.active = pageIndex < pages.size() - 1;
    }

    /** Every mouse click while the tutorial is open is swallowed here - whether or not it lands
     * on one of this popup's own buttons - so a click that misses everything can never fall
     * through to Batch Commander's own controls underneath. */
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!open) {
            return false;
        }
        if (!closeButton.mouseClicked(event, doubleClick) && !prevButton.mouseClicked(event, doubleClick)) {
            nextButton.mouseClicked(event, doubleClick);
        }
        return true;
    }

    /** Escape closes only the tutorial (consumed here, never reaching {@code Screen}'s own
     * Escape-closes-screen handling); Left/Right mirror the Prev/Next buttons. Every other key is
     * still consumed rather than falling through, so keyboard shortcuts (Ctrl+A, Ctrl+Enter,
     * plain typing, ...) can never reach the command editor while this is open. */
    public boolean keyPressed(KeyEvent event) {
        if (!open) {
            return false;
        }
        if (event.isEscape()) {
            close();
        } else if (event.isLeft()) {
            previousPage();
        } else if (event.isRight()) {
            nextPage();
        }
        return true;
    }

    /** Consumes every scroll while open so it can never reach (and scroll) the command editor
     * behind the popup - the visual/diagram area itself has nothing to scroll. */
    public boolean mouseScrolled() {
        return open;
    }

    public boolean charTyped(CharacterEvent event) {
        return open;
    }

    private void layout(int screenWidth, int screenHeight) {
        panelWidth = Math.clamp(Math.round(screenWidth * 0.62F), Math.min(260, screenWidth), Math.min(520, screenWidth));
        // Floor raised from an earlier 200 - at that height the visual area left for a page's own
        // diagram (see TutorialPage.VisualRenderer) was too short for a taller page's content
        // (e.g. TutorialPages' 4-box heavy-protection stack) to fit without visibly overflowing
        // past its scissor at the default Minecraft window size.
        panelHeight = Math.clamp(Math.round(screenHeight * 0.62F), Math.min(230, screenHeight), Math.min(360, screenHeight));
        panelX = (screenWidth - panelWidth) / 2;
        panelY = (screenHeight - panelHeight) / 2;

        closeButton.setX(panelX + panelWidth - PADDING - CLOSE_BUTTON_SIZE);
        closeButton.setY(panelY + PADDING - 2);

        int navY = panelY + panelHeight - PADDING - NAV_ROW_HEIGHT;
        prevButton.setX(panelX + PADDING);
        prevButton.setY(navY);
        nextButton.setX(panelX + panelWidth - PADDING - NAV_BUTTON_WIDTH);
        nextButton.setY(navY);
    }

    public void render(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY, float delta) {
        if (!open || pages.isEmpty()) {
            return;
        }
        layout(screenWidth, screenHeight);
        updateNavButtons();

        // Dims the whole screen (Batch Commander included) behind the popup, without hiding it -
        // it stays fully rendered underneath, just darkened.
        graphics.fill(0, 0, screenWidth, screenHeight, OVERLAY_COLOR);

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BACKGROUND);
        graphics.outline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER);

        TutorialPage page = pages.get(pageIndex);
        int centerX = panelX + panelWidth / 2;
        int textWidth = panelWidth - PADDING * 2;
        List<FormattedCharSequence> descriptionLines = font.split(FormattedText.of(page.description()), textWidth);

        int navTop = panelY + panelHeight - PADDING - NAV_ROW_HEIGHT;
        int textBlockHeight = font.lineHeight + 3 + descriptionLines.size() * font.lineHeight;
        int textTop = navTop - PADDING - textBlockHeight;

        int visualLeft = panelX + PADDING;
        int visualRight = panelX + panelWidth - PADDING;
        int visualTop = panelY + PADDING + CLOSE_BUTTON_SIZE + 4;
        int visualBottom = textTop - PADDING;

        if (visualBottom > visualTop) {
            graphics.fill(visualLeft, visualTop, visualRight, visualBottom, VISUAL_BACKGROUND);
            graphics.outline(visualLeft, visualTop, visualRight - visualLeft, visualBottom - visualTop, VISUAL_BORDER);
            graphics.enableScissor(visualLeft, visualTop, visualRight, visualBottom);
            page.visual().render(graphics, font, visualLeft + 6, visualTop + 6, visualRight - visualLeft - 12, visualBottom - visualTop - 12);
            graphics.disableScissor();
        }

        graphics.centeredText(font, page.title(), centerX, textTop, TITLE_COLOR);
        int lineY = textTop + font.lineHeight + 3;
        for (FormattedCharSequence line : descriptionLines) {
            graphics.centeredText(font, line, centerX, lineY, TEXT_COLOR);
            lineY += font.lineHeight;
        }

        String pageCounter = (pageIndex + 1) + " / " + pages.size();
        graphics.centeredText(font, pageCounter, centerX, navTop + (NAV_ROW_HEIGHT - font.lineHeight) / 2, PAGE_COUNTER_COLOR);

        closeButton.extractRenderState(graphics, mouseX, mouseY, delta);
        prevButton.extractRenderState(graphics, mouseX, mouseY, delta);
        nextButton.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
