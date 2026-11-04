package com.capy.client.module.modules.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A HUD surface that is more than one chip: a box that draws its own content.
 *
 * <p>HUD elements are single rows of text measured by {@link HudRenderer}. A
 * panel is not: it has rows of its own, icons, and a height that depends on who
 * is on a list. What it shares with a chip is the only thing the layout and the
 * editor care about - where it is anchored, how far it has been nudged, and how
 * big it is this frame - so that is what this interface declares. The drawing
 * is the panel's own business.</p>
 *
 * <p>Placement uses the same nine cell grid and the same nudge range as
 * {@link HudElementModule}, deliberately: a player dragging a box in the editor
 * should not have to learn a second set of rules for it, and the group of
 * panels and chips is laid out by one pass so the two kinds never overlap by
 * accident.</p>
 */
public interface HudPanel {
	/** Which grid cell the panel is anchored to. */
	int anchor();

	/** Horizontal nudge in pixels, applied after the anchor. */
	int offsetX();

	/** Vertical nudge in pixels, applied after the anchor and the stacking. */
	int offsetY();

	/** Anchors the panel to a grid cell. */
	void setAnchor(int cell);

	/** Moves the panel to a nudge, clamped by the implementation. */
	void moveTo(int x, int y);

	/** Puts the panel back on its cell. */
	void resetPosition();

	/** Width this panel wants this frame, in pixels. */
	int panelWidth(Minecraft mc);

	/** Height this panel wants this frame, in pixels. */
	int panelHeight(Minecraft mc);

	/**
	 * Draws the panel inside the box the layout gave it.
	 *
	 * <p>The box is passed in rather than computed here so the geometry the
	 * editor draws its handle around is the geometry that was actually painted.
	 */
	void renderPanel(GuiGraphics graphics, int x, int y, int w, int h);
}
