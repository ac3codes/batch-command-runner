package com.ac3codes.batchcommandrunner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One page of {@link TutorialPopup}'s in-game tutorial: a short title, a one-line explanation,
 * and a visual/diagram drawn into a fixed rectangle. {@link VisualRenderer} is a separate
 * indirection specifically so a page's visual can later be swapped for a real texture/PNG asset
 * (via {@code graphics.blit}/{@code blitSprite}) without touching {@link TutorialPopup} or this
 * record - see {@link TutorialPages} for the initial, programmatically-drawn renderers.
 */
public record TutorialPage(String title, String description, VisualRenderer visual) {
    @FunctionalInterface
    public interface VisualRenderer {
        /** Draws this page's visual inside the given rectangle, already scissored to it by the
         * caller - implementations don't need to worry about overflowing its bounds. */
        void render(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height);
    }
}
