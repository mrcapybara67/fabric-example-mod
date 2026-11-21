package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
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
    void globalSettingsSheetFitsTheShortestSupportedViewport() {
        int viewport = 240;
        int panel = GuiFrame.settingsPanelHeightFor(viewport);
        int panelY = GuiFrame.settingsPanelYFor(8, viewport);
        assertTrue(panel <= viewport - 16);
        assertTrue(panelY + panel <= viewport - 8);
        // Every row, including the final Edit HUD button, must remain on-screen.
        int lastRow = GuiFrame.settingsRowTopOffset(6, panel);
        assertTrue(lastRow + 22 <= panel, "last settings row overflows the compact sheet");
    }

    @Test
    void globalSettingsSheetKeepsItsDesignedHeightOnTallerScreens() {
        assertEquals(272, GuiFrame.settingsPanelHeightFor(360));
        assertEquals(58, GuiFrame.settingsRowTopOffset(0, 272));
    }

    @Test
    void closeButtonStaysClickableAboveTheGlobalSettingsSheet() throws Exception {
        GuiFrame frame = new GuiFrame();
        frame.resize(640, 360);
        // The sheet used to be checked first, so this click only dismissed the
        // sheet and the window's own Close X appeared broken while it was open.
        Field open = GuiFrame.class.getDeclaredField("guiSettingsOpen");
        open.setAccessible(true);
        open.setBoolean(frame, true);

        Method windowX = GuiFrame.class.getDeclaredMethod("windowX");
        Method windowY = GuiFrame.class.getDeclaredMethod("windowY");
        Method windowWidth = GuiFrame.class.getDeclaredMethod("windowWidth");
        windowX.setAccessible(true);
        windowY.setAccessible(true);
        windowWidth.setAccessible(true);
        int closeX = (int) windowX.invoke(frame) + (int) windowWidth.invoke(frame)
                - UiTheme.SP_6 - UiTheme.CLOSE_BTN;
        int closeY = (int) windowY.invoke(frame) + (UiTheme.HEADER_H - UiTheme.CLOSE_BTN) / 2;
        MouseButtonEvent click = new MouseButtonEvent(closeX, closeY, new MouseButtonInfo(0, 0));

        assertTrue(frame.mouseClicked(click, closeX + 1, closeY + 1));
        assertTrue(frame.closeRequested(), "Close must win over the open settings sheet");
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
