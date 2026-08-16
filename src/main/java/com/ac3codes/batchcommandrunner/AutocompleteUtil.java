package com.ac3codes.batchcommandrunner;

/**
 * Pure text-manipulation helpers for scoping Brigadier autocomplete to the single line the
 * cursor is on, inside an editor that holds an entire multiline batch. Deliberately has no
 * dependency on Minecraft or Brigadier types so it's trivial to unit test - the screen adapts
 * a real {@code com.mojang.brigadier.suggestion.Suggestion} into the plain ints/String used
 * here.
 */
public final class AutocompleteUtil {

    private AutocompleteUtil() {
    }

    /**
     * The line containing {@code cursor} within a multiline {@code value}, plus the cursor's
     * offset within that line. Line boundaries are found via {@code \n} only, which is fine
     * here since the editor already normalizes input to LF-separated lines.
     */
    public record LineContext(String line, int lineStart, int lineEnd, int cursorInLine) {
    }

    public static LineContext extractCurrentLine(String value, int cursor) {
        int cursorClamped = Math.clamp(cursor, 0, value.length());
        // Deliberately NOT clamped to 0: String.lastIndexOf already treats a negative fromIndex
        // as "found nothing" (-1), which is exactly right when the cursor is at position 0 -
        // there's nothing before it to search. Clamping this to 0 used to make the search
        // inspect position 0 itself, and if that character was '\n' (an empty first line with
        // the cursor on it), it got misread as a newline *before* the cursor, producing a
        // lineStart past lineEnd and crashing the subsequent substring call.
        int lineStart = value.lastIndexOf('\n', cursorClamped - 1) + 1;
        int newline = value.indexOf('\n', cursorClamped);
        int lineEnd = newline < 0 ? value.length() : newline;
        String line = value.substring(lineStart, lineEnd);
        return new LineContext(line, lineStart, lineEnd, cursorClamped - lineStart);
    }

    /**
     * Result of splicing a suggestion into the full editor text: the new full value, and
     * where the cursor should land afterward (end of the inserted suggestion text).
     */
    public record Applied(String newValue, int newCursor) {
    }

    /**
     * Applies a Brigadier suggestion - given as a {@code [rangeStart, rangeEnd)} within the
     * current line plus its replacement text - back into the full multiline editor value.
     * Only the addressed range of the current line is replaced; every other line, and the
     * rest of the current line outside the range, is left untouched.
     */
    public static Applied applySuggestion(String fullValue, LineContext context, int rangeStart, int rangeEnd, String suggestionText) {
        String line = context.line();
        // Defensive clamp: the suggestion's range was computed against the line as it was when
        // requested. If the cursor moved to a different line in the meantime, lineStart/lineEnd
        // (and thus the surrounding splice) are no longer meaningful, so callers should avoid
        // applying in that case - but a same-line edit shrinking the line is cheap to tolerate here.
        rangeStart = Math.clamp(rangeStart, 0, line.length());
        rangeEnd = Math.clamp(rangeEnd, rangeStart, line.length());
        String newLine = line.substring(0, rangeStart) + suggestionText + line.substring(rangeEnd);
        String newValue = fullValue.substring(0, context.lineStart()) + newLine + fullValue.substring(context.lineEnd());
        int newCursor = context.lineStart() + rangeStart + suggestionText.length();
        return new Applied(newValue, newCursor);
    }

    public record PopupPosition(int x, int y) {
    }

    /**
     * Clamps a popup anchored at ({@code anchorX}, {@code anchorY}) so it stays fully within
     * the {@code screenWidth} x {@code screenHeight} bounds, preferring to keep its top-left
     * at the anchor when there's room.
     */
    public static PopupPosition clampPopupPosition(int anchorX, int anchorY, int popupWidth, int popupHeight, int screenWidth, int screenHeight) {
        int maxX = Math.max(0, screenWidth - popupWidth);
        int maxY = Math.max(0, screenHeight - popupHeight);
        int x = Math.clamp(anchorX, 0, maxX);
        int y = Math.clamp(anchorY, 0, maxY);
        return new PopupPosition(x, y);
    }
}
