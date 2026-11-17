package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuiFrameLayoutTest {
    @Test
    void commonGuiScaleUsesTheTwoColumnGrid() {
        // 1920x1080 at Minecraft GUI scale 3 is a 640x360 virtual viewport.
        int window = GuiFrame.windowWidthFor(640);
        assertEquals(624, window);
        assertEquals(410, GuiFrame.contentWidthFor(window));
        assertEquals(2, GuiFrame.cardColumnsFor(GuiFrame.contentWidthFor(window)));
    }

    @Test
    void wideScreensStillGetTwoColumns() {
        // 1920x1080 at GUI scale 2.
        int window = GuiFrame.windowWidthFor(960);
        assertEquals(680, window);
        assertEquals(2, GuiFrame.cardColumnsFor(GuiFrame.contentWidthFor(window)));
    }

    @Test
    void narrowScreensKeepOneFullWidthColumn() {
        int window = GuiFrame.windowWidthFor(426);
        assertEquals(410, window);
        assertEquals(196, GuiFrame.contentWidthFor(window));
        assertEquals(1, GuiFrame.cardColumnsFor(GuiFrame.contentWidthFor(window)));
    }

    @Test
    void windowNeverExceedsTheViewport() {
        for (int width = 320; width <= 2560; width += 8) {
            int window = GuiFrame.windowWidthFor(width);
            assertTrue(window > 0);
            assertTrue(window <= Math.max(1, width - 16), "viewport " + width);
        }
    }
}
