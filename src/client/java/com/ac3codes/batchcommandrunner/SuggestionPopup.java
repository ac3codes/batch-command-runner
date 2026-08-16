package com.ac3codes.batchcommandrunner;

import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * A compact, vanilla-style scrollable suggestion popup: a small floating box near the cursor
 * showing a handful of rows, rather than a permanent panel. Owns its own selection/scroll
 * model ({@link #selectedIndex}, {@link #scrollOffset}) and renders only the visible rows
 * directly as plain strings - no Component trees are built, and nothing here recomputes
 * anything on its own; the screen calls {@link #show} only when the underlying suggestion
 * list actually changes.
 */
public final class SuggestionPopup {
    /** Vanilla shows a handful of rows at a time and scrolls for the rest; 7 sits in the requested 5-8 range. */
    public static final int VISIBLE_ROWS = 7;
    private static final int ROW_HEIGHT = 11;
    private static final int PADDING = 3;
    private static final int MIN_WIDTH = 70;

    private static final int BACKGROUND_COLOR = 0xF0101010;
    private static final int BORDER_COLOR = 0xFF3F3F3F;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    // Matches vanilla's ChatFormatting.YELLOW - the selected row is distinguished by text color
    // alone (no background fill), which reads as more "vanilla" than a solid highlight box.
    private static final int SELECTED_TEXT_COLOR = 0xFFFFFF55;
    private static final int SCROLL_HINT_COLOR = 0xFF808080;

    private List<Suggestion> suggestions = List.of();
    private int selectedIndex;
    private int scrollOffset;
    private int x, y, width, height;
    private boolean visible;

    /** (Re)shows the popup for a fresh suggestion list, positioned near ({@code anchorX}, {@code anchorY})
     * and clamped so it never extends past the screen edges. */
    public void show(List<Suggestion> newSuggestions, int anchorX, int anchorY, Font font, int screenWidth, int screenHeight) {
        if (newSuggestions.isEmpty()) {
            hide();
            return;
        }

        this.suggestions = newSuggestions;
        this.selectedIndex = 0;
        this.scrollOffset = 0;
        this.visible = true;

        int rows = Math.min(VISIBLE_ROWS, suggestions.size());
        int maxTextWidth = 0;
        for (Suggestion suggestion : suggestions) {
            maxTextWidth = Math.max(maxTextWidth, font.width(suggestion.getText()));
        }
        this.width = Math.max(MIN_WIDTH, maxTextWidth + PADDING * 2);
        this.height = rows * ROW_HEIGHT + PADDING * 2;

        AutocompleteUtil.PopupPosition pos = AutocompleteUtil.clampPopupPosition(anchorX, anchorY, width, height, screenWidth, screenHeight);
        this.x = pos.x();
        this.y = pos.y();
    }

    public void hide() {
        visible = false;
        suggestions = List.of();
        selectedIndex = 0;
        scrollOffset = 0;
    }

    public boolean isVisible() {
        return visible;
    }

    public Suggestion selected() {
        return visible && selectedIndex < suggestions.size() ? suggestions.get(selectedIndex) : null;
    }

    public void moveSelection(int delta) {
        if (!visible || suggestions.isEmpty()) {
            return;
        }
        selectedIndex = Math.floorMod(selectedIndex + delta, suggestions.size());
        int rows = Math.min(VISIBLE_ROWS, suggestions.size());
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + rows) {
            scrollOffset = selectedIndex - rows + 1;
        }
    }

    public void scroll(double wheelAmount) {
        if (!visible || wheelAmount == 0) {
            return;
        }
        int rows = Math.min(VISIBLE_ROWS, suggestions.size());
        int maxOffset = Math.max(0, suggestions.size() - rows);
        int direction = wheelAmount > 0 ? -1 : 1;
        scrollOffset = Math.clamp(scrollOffset + direction, 0, maxOffset);
    }

    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** The suggestion under the given screen point, selecting it as a side effect, or null if the point misses every row. */
    public Suggestion rowAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return null;
        }
        int row = (int) ((mouseY - y - PADDING) / ROW_HEIGHT);
        int index = scrollOffset + row;
        if (index < 0 || index >= suggestions.size()) {
            return null;
        }
        selectedIndex = index;
        return suggestions.get(index);
    }

    public void render(GuiGraphicsExtractor graphics, Font font) {
        if (!visible) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, BACKGROUND_COLOR);
        graphics.outline(x, y, width, height, BORDER_COLOR);

        int rows = Math.min(VISIBLE_ROWS, suggestions.size());
        for (int row = 0; row < rows; row++) {
            int index = scrollOffset + row;
            if (index >= suggestions.size()) {
                break;
            }
            int rowTop = y + PADDING + row * ROW_HEIGHT;
            String text = suggestions.get(index).getText();
            graphics.text(font, text, x + PADDING, rowTop, index == selectedIndex ? SELECTED_TEXT_COLOR : TEXT_COLOR, false);
        }

        if (scrollOffset > 0) {
            graphics.text(font, "^", x + width - PADDING - font.width("^"), y + PADDING - 1, SCROLL_HINT_COLOR, false);
        }
        if (scrollOffset + rows < suggestions.size()) {
            graphics.text(font, "v", x + width - PADDING - font.width("v"), y + height - PADDING - 9, SCROLL_HINT_COLOR, false);
        }
    }
}
