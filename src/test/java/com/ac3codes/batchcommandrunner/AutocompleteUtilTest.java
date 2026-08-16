package com.ac3codes.batchcommandrunner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutocompleteUtilTest {

    @Test
    void extractCurrentLineFindsMiddleLineOfMultilineBatch() {
        String value = "/time set day\n/fill 0 64 0 10 64 10 minec\n/weather clear";
        int cursor = value.indexOf("minec") + "minec".length();

        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, cursor);

        assertEquals("/fill 0 64 0 10 64 10 minec", ctx.line());
        assertEquals(14, ctx.lineStart());
        assertEquals("/fill 0 64 0 10 64 10 minec".length(), ctx.cursorInLine());
    }

    @Test
    void extractCurrentLineHandlesFirstLine() {
        String value = "/say hi\n/weather clear";
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, 3);
        assertEquals("/say hi", ctx.line());
        assertEquals(0, ctx.lineStart());
        assertEquals(3, ctx.cursorInLine());
    }

    @Test
    void extractCurrentLineHandlesLastLineWithNoTrailingNewline() {
        String value = "/time set day\n/weather clear";
        int cursor = value.length();
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, cursor);
        assertEquals("/weather clear", ctx.line());
        assertEquals(14, ctx.lineStart());
        assertEquals(value.length(), ctx.lineEnd());
    }

    @Test
    void extractCurrentLineHandlesEmptyValue() {
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine("", 0);
        assertEquals("", ctx.line());
        assertEquals(0, ctx.lineStart());
        assertEquals(0, ctx.cursorInLine());
    }

    @Test
    void extractCurrentLineHandlesCursorAtStartOfALeadingEmptyLine() {
        // Regression test: reproduces a live StringIndexOutOfBoundsException crash
        // ("Range [1, 0)") that occurred when the batch text starts with a blank line and the
        // cursor sits at position 0, on that empty first line.
        String value = "\n/fill 0 64 0 10 64 10 minecraft:stone";
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, 0);
        assertEquals("", ctx.line());
        assertEquals(0, ctx.lineStart());
        assertEquals(0, ctx.lineEnd());
        assertEquals(0, ctx.cursorInLine());
    }

    @Test
    void extractCurrentLineHandlesCursorAtStartOfANonEmptyFirstLine() {
        String value = "/say hi\n/weather clear";
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, 0);
        assertEquals("/say hi", ctx.line());
        assertEquals(0, ctx.lineStart());
        assertEquals(0, ctx.cursorInLine());
    }

    @Test
    void applySuggestionReplacesOnlyTheCurrentLineWithinFullBatch() {
        String value = "/time set day\n/fill 0 64 0 10 64 10 minec\n/weather clear";
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, value.indexOf("minec") + 5);

        // Suggestion replaces the partial "minec" (range [23, 28) within the line) with the full id.
        int rangeStart = ctx.line().indexOf("minec");
        int rangeEnd = ctx.line().length();
        AutocompleteUtil.Applied applied = AutocompleteUtil.applySuggestion(value, ctx, rangeStart, rangeEnd, "minecraft:snow_block");

        assertEquals("/time set day\n/fill 0 64 0 10 64 10 minecraft:snow_block\n/weather clear", applied.newValue());
        assertEquals(ctx.lineStart() + rangeStart + "minecraft:snow_block".length(), applied.newCursor());
    }

    @Test
    void applySuggestionLeavesOtherLinesUntouched() {
        String value = "/say a\n/say b\n/say c";
        AutocompleteUtil.LineContext ctx = AutocompleteUtil.extractCurrentLine(value, "/say a\n/say ".length());
        AutocompleteUtil.Applied applied = AutocompleteUtil.applySuggestion(value, ctx, 5, 5, "replaced");
        assertEquals("/say a\n/say replacedb\n/say c", applied.newValue());
    }

    @Test
    void applySuggestionClampsOutOfRangeIndicesInsteadOfThrowing() {
        AutocompleteUtil.LineContext ctx = new AutocompleteUtil.LineContext("short", 0, 5, 5);
        AutocompleteUtil.Applied applied = AutocompleteUtil.applySuggestion("short", ctx, 100, 200, "x");
        assertEquals("shortx", applied.newValue());
    }

    @Test
    void clampPopupPositionKeepsAnchorWhenItFits() {
        AutocompleteUtil.PopupPosition pos = AutocompleteUtil.clampPopupPosition(50, 50, 100, 80, 800, 600);
        assertEquals(50, pos.x());
        assertEquals(50, pos.y());
    }

    @Test
    void clampPopupPositionPullsBackInsideRightAndBottomEdges() {
        AutocompleteUtil.PopupPosition pos = AutocompleteUtil.clampPopupPosition(780, 580, 100, 80, 800, 600);
        assertEquals(700, pos.x());
        assertEquals(520, pos.y());
    }

    @Test
    void clampPopupPositionNeverGoesNegative() {
        AutocompleteUtil.PopupPosition pos = AutocompleteUtil.clampPopupPosition(-30, -10, 100, 80, 800, 600);
        assertEquals(0, pos.x());
        assertEquals(0, pos.y());
    }
}
