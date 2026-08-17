package com.ac3codes.batchcommandrunner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Builds the fixed set of {@link TutorialPage}s shown by {@link TutorialPopup}. Each page's
 * visual is drawn programmatically with plain GUI primitives (rectangles/text/arrows) via the
 * shared helpers below, rather than real texture assets - see {@link TutorialPage.VisualRenderer}
 * for how a page could draw a real PNG instead later without any change to {@link TutorialPopup}.
 */
final class TutorialPages {
    private TutorialPages() {
    }

    private static final int BOX_BG = 0xFF2B2B2B;
    private static final int BOX_BORDER = 0xFF555555;
    private static final int TEXT_COLOR = 0xFFDDDDDD;
    private static final int DIM_TEXT_COLOR = 0xFF999999;
    private static final int ACCENT_COLOR = 0xFFFFFF55;
    private static final int HIGHLIGHT_COLOR = 0x60FFFF55;
    private static final int PROTECTED_BG = 0xFF3A3320;
    private static final int PROTECTED_BORDER = 0xFFAA8800;

    static final List<TutorialPage> ALL = List.of(
            new TutorialPage("Paste Your Commands",
                    "Paste multiple commands, one per line. Blank lines are fine, and # lines are comments.",
                    TutorialPages::drawPasteCommands),
            new TutorialPage("Run Your Batch",
                    "Press Run Commands to send them one after another using the configured delay.",
                    TutorialPages::drawRunCommands),
            new TutorialPage("Control Execution",
                    "Pause keeps your progress. Resume continues. Stop (or editing while paused) resets the batch.",
                    TutorialPages::drawControlExecution),
            new TutorialPage("Track Your Progress",
                    "The highlighted line is the last command that ran. The editor follows along unless you scroll away.",
                    TutorialPages::drawCommandHighlight),
            new TutorialPage("Protect Minecraft",
                    "Heavy commands like /fill, /clone, /place and /summon can get extra delay automatically.",
                    TutorialPages::drawHeavyProtection),
            new TutorialPage("Open It Anytime",
                    "Open Batch Commander with your keybind or by typing /batch. Closing it never stops a running batch.",
                    TutorialPages::drawOpenAnytime)
    );

    // ---- shared drawing helpers ----

    private static void mockButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h, String label, int bg, int border) {
        graphics.fill(x, y, x + w, y + h, bg);
        graphics.outline(x, y, w, h, border);
        graphics.centeredText(font, label, x + w / 2, y + (h - font.lineHeight) / 2, TEXT_COLOR);
    }

    private static void downArrow(GuiGraphicsExtractor graphics, Font font, int centerX, int y) {
        graphics.centeredText(font, "v", centerX, y, DIM_TEXT_COLOR);
    }

    // ---- page 1: paste commands ----

    private static void drawPasteCommands(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h) {
        String[] lines = {"1  /fill ~ ~ ~ ~10 ~ ~10 stone", "2  /fill ~ ~ ~ ~10 ~5 ~10 air", "3  # optional comment", "4  /say Finished"};
        int rowHeight = font.lineHeight + 3;
        int boxWidth = Math.min(w, 190);
        int boxHeight = lines.length * rowHeight + 8;
        int boxX = x + (w - boxWidth) / 2;
        int boxY = y + Math.max(0, (h - boxHeight) / 2);

        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, BOX_BG);
        graphics.outline(boxX, boxY, boxWidth, boxHeight, BOX_BORDER);
        for (int i = 0; i < lines.length; i++) {
            int color = lines[i].trim().startsWith("#") ? DIM_TEXT_COLOR : TEXT_COLOR;
            graphics.text(font, lines[i], boxX + 6, boxY + 4 + i * rowHeight, color, false);
        }
    }

    // ---- page 2: run commands ----

    private static void drawRunCommands(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h) {
        int buttonWidth = Math.min(w, 100);
        int buttonHeight = 16;
        int centerX = x + w / 2;
        int stepHeight = (font.lineHeight + 2) * 2;
        int totalHeight = buttonHeight + 6 + stepHeight * 3 - 2;
        int top = y + Math.max(0, (h - totalHeight) / 2);

        mockButton(graphics, font, centerX - buttonWidth / 2, top, buttonWidth, buttonHeight, "Run Commands", BOX_BG, ACCENT_COLOR);

        int rowY = top + buttonHeight + 6;
        String[] labels = {"Command 1", "Command 2", "Command 3"};
        for (String label : labels) {
            downArrow(graphics, font, centerX, rowY);
            rowY += font.lineHeight + 2;
            graphics.centeredText(font, label, centerX, rowY, TEXT_COLOR);
            rowY += font.lineHeight + 2;
        }
    }

    // ---- page 3: pause/resume/stop ----

    private static void drawControlExecution(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h) {
        int buttonWidth = Math.min(70, Math.max(30, (w - 30) / 2));
        int buttonHeight = 16;
        int centerX = x + w / 2;
        int gap = 10;
        int groupHeight = font.lineHeight + 4 + buttonHeight;
        int totalHeight = groupHeight * 2 + 12;
        int top = y + Math.max(0, (h - totalHeight) / 2);

        graphics.centeredText(font, "RUNNING", centerX, top, ACCENT_COLOR);
        int rowY = top + font.lineHeight + 4;
        mockButton(graphics, font, centerX - buttonWidth - gap / 2, rowY, buttonWidth, buttonHeight, "Pause", BOX_BG, BOX_BORDER);
        mockButton(graphics, font, centerX + gap / 2, rowY, buttonWidth, buttonHeight, "Stop", BOX_BG, BOX_BORDER);

        int secondTop = top + groupHeight + 12;
        graphics.centeredText(font, "PAUSED", centerX, secondTop, ACCENT_COLOR);
        int secondRowY = secondTop + font.lineHeight + 4;
        mockButton(graphics, font, centerX - buttonWidth - gap / 2, secondRowY, buttonWidth, buttonHeight, "Resume", BOX_BG, BOX_BORDER);
        mockButton(graphics, font, centerX + gap / 2, secondRowY, buttonWidth, buttonHeight, "Stop", BOX_BG, BOX_BORDER);
    }

    // ---- page 4: command highlight ----

    private static void drawCommandHighlight(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h) {
        String[] lines = {"82  /fill ~ ~ ~ ~5 ~ ~5 stone", "83  /fill ~ ~ ~ ~5 ~5 ~5 air", "84  /fill ~ ~ ~ ~5 ~10 ~5 glass", "85  /say Layer done"};
        int highlightedRow = 2;
        int rowHeight = font.lineHeight + 3;
        int boxWidth = Math.min(w, 200);
        int boxHeight = lines.length * rowHeight + 8;
        int boxX = x + (w - boxWidth) / 2;
        int boxY = y + Math.max(0, (h - boxHeight - font.lineHeight - 4) / 2);

        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, BOX_BG);
        graphics.outline(boxX, boxY, boxWidth, boxHeight, BOX_BORDER);
        for (int i = 0; i < lines.length; i++) {
            int rowTop = boxY + 4 + i * rowHeight;
            if (i == highlightedRow) {
                graphics.fill(boxX + 1, rowTop - 1, boxX + boxWidth - 1, rowTop + rowHeight - 2, HIGHLIGHT_COLOR);
            }
            graphics.text(font, lines[i], boxX + 6, rowTop, TEXT_COLOR, false);
        }
        String caption = "^ most recently executed";
        graphics.centeredText(font, caption, boxX + boxWidth / 2, boxY + boxHeight + 4, ACCENT_COLOR);
    }

    // ---- page 5: delay & heavy protection ----

    private static void drawHeavyProtection(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h) {
        int centerX = x + w / 2;
        String[] labels = {"Normal command", "Delay", "Large /fill", "Extra delay"};
        int[] backgrounds = {BOX_BG, BOX_BG, BOX_BG, PROTECTED_BG};
        int[] borders = {BOX_BORDER, BOX_BORDER, BOX_BORDER, PROTECTED_BORDER};
        int n = labels.length;
        int boxWidth = Math.min(w, 130);
        int boxHeight = font.lineHeight + 6;
        int baseArrowGap = font.lineHeight + 4;

        // Shrinks the gap between boxes (down to just enough room for the arrow glyph), and if
        // that alone still isn't enough, the boxes themselves (down to just enough for their
        // label), so this 4-box stack always fits within h instead of silently overflowing past
        // the visual area's scissor - which is what previously cut the bottom of this page off at
        // the default Minecraft window size.
        int minArrowGap = font.lineHeight;
        int arrowGap = n > 1 ? Math.max(minArrowGap, Math.min(baseArrowGap, (h - n * boxHeight) / (n - 1))) : baseArrowGap;
        int totalHeight = n * boxHeight + (n - 1) * arrowGap;
        if (totalHeight > h) {
            int minBoxHeight = font.lineHeight + 2;
            int deficit = totalHeight - h;
            int shrink = Math.min(boxHeight - minBoxHeight, (deficit + n - 1) / n);
            boxHeight -= Math.max(0, shrink);
            totalHeight = n * boxHeight + (n - 1) * arrowGap;
        }
        int top = y + Math.max(0, (h - totalHeight) / 2);

        int rowY = top;
        for (int i = 0; i < n; i++) {
            mockButton(graphics, font, centerX - boxWidth / 2, rowY, boxWidth, boxHeight, labels[i], backgrounds[i], borders[i]);
            rowY += boxHeight;
            if (i < n - 1) {
                downArrow(graphics, font, centerX, rowY + 1);
                rowY += arrowGap;
            }
        }
    }

    // ---- page 6: open anytime ----

    private static void drawOpenAnytime(GuiGraphicsExtractor graphics, Font font, int x, int y, int w, int h) {
        int centerX = x + w / 2;
        String keyLabel = BatchCommandRunnerClient.getOpenKeyDisplay().getString();
        int keyBoxWidth = Math.min(w, Math.max(50, font.width(keyLabel) + 16));
        int keyBoxHeight = 18;
        int gap = 16;
        int totalHeight = keyBoxHeight + gap + keyBoxHeight;
        int top = y + Math.max(0, (h - totalHeight) / 2);

        mockButton(graphics, font, centerX - keyBoxWidth / 2, top, keyBoxWidth, keyBoxHeight, keyLabel, BOX_BG, ACCENT_COLOR);
        int orY = top + keyBoxHeight + (gap - font.lineHeight) / 2;
        graphics.centeredText(font, "or", centerX, orY, DIM_TEXT_COLOR);
        int slashY = top + keyBoxHeight + gap;
        int slashBoxWidth = Math.min(w, Math.max(60, font.width("/batch") + 16));
        mockButton(graphics, font, centerX - slashBoxWidth / 2, slashY, slashBoxWidth, keyBoxHeight, "/batch", BOX_BG, ACCENT_COLOR);
    }
}
