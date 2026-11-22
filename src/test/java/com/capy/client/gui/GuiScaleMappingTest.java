package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The size presets, the mouse mapping and the device snapping share one path. */
class GuiScaleMappingTest {
	private static final float[] SCALES = {0.86f, 1.0f, 1.16f};

	@Test
	void oneFloatMappingIsUsedAtEveryScale() {
		for (float scale : SCALES) {
			assertEquals(0f, ClickGuiScreen.guiMouse(0, scale), 1.0e-6f);
			assertEquals(scale, ClickGuiScreen.guiMouse(scale * scale, scale), 1.0e-6f);
			// Near the far edge, not just at the origin, and off a whole pixel
			// even at the 1.0 preset so the no-rounding check is meaningful.
			double raw = 1919.5;
			float mapped = ClickGuiScreen.guiMouse(raw, scale);
			assertEquals((float) (raw / scale), mapped, 1.0e-6f);
			assertNotEquals(Math.round(mapped), mapped,
					"mapping at " + scale + " must not round to a whole GUI pixel");
		}
	}

	@Test
	void geometrySnapsInDeviceSpace() {
		for (float scale : SCALES) {
			for (int logical : new int[]{0, 1, 7, 13, 101, 639}) {
				int device = McCanvas.devicePixel(logical, scale);
				assertEquals(Math.round(logical * scale), device, "scale " + scale);
				assertTrue(Math.abs(device - logical * scale) <= 0.5f,
						"device pixel must be the nearest one");
			}
		}
	}

	@Test
	void hoverAndClickAgreeOnTheSameCardAtEveryScale() {
		for (float scale : SCALES) {
			Module module = new FakeModule("scale_agreement_" + scale);
			ModuleManager.get().register(module);
			try {
				GuiFrame frame = new GuiFrame();
				frame.resize(640, 360);
				frame.setCategory(Category.BASE_FINDER.ordinal());
				FakeCanvas canvas = new FakeCanvas();
				render(frame, canvas);
				List<ModuleCard> cards = frame.visibleCardsForTest();
				assertFalse(cards.isEmpty());
				ModuleCard card = cards.get(0);

				float guiX = card.x() + 20;
				float guiY = card.y() + 20;
				float mappedX = ClickGuiScreen.guiMouse(guiX * (double) scale, scale);
				float mappedY = ClickGuiScreen.guiMouse(guiY * (double) scale, scale);
				assertEquals(guiX, mappedX, 1.0e-4f, "x at scale " + scale);
				assertEquals(guiY, mappedY, 1.0e-4f, "y at scale " + scale);
				assertTrue(card.contains(mappedX, mappedY), "hover at scale " + scale);
				assertTrue(card.mouseClicked(mappedX, mappedY, 0), "click at scale " + scale);
			} finally {
				ModuleManager.get().unregister(module);
			}
		}
	}

	@Test
	void resizeRelayoutsOnceForTheNewWidth() {
		List<Module> modules = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Module module = new FakeModule("scale_resize_" + i);
			modules.add(module);
			ModuleManager.get().register(module);
		}
		try {
			GuiFrame frame = new GuiFrame();
			frame.resize(640, 360);
			frame.setCategory(Category.BASE_FINDER.ordinal());
			FakeCanvas canvas = new FakeCanvas();
			render(frame, canvas);
			int[] wide = frame.layoutTopsForTest();

			frame.resize(426, 360);
			render(frame, canvas);
			int[] narrow = frame.layoutTopsForTest();
			assertNotEquals(wide.length, 0);
			assertNotEquals(narrow.length, 0);
			assertTrue(!java.util.Arrays.equals(wide, narrow),
					"a narrower viewport must rebuild the card rows");
		} finally {
			for (Module module : modules) {
				ModuleManager.get().unregister(module);
			}
		}
	}

	private static void render(GuiFrame frame, UiCanvas canvas) {
		GuiPerf.begin();
		frame.render(canvas, new FakeType(), -20, -20, 20f, 1f);
		GuiPerf.end(canvas.drawCalls());
	}

	private static final class FakeModule extends Module {
		FakeModule(String id) {
			super(Category.BASE_FINDER, id);
		}
	}

	private static final class FakeCanvas implements UiCanvas {
		private int draws;

		@Override
		public void fill(int x1, int y1, int x2, int y2, int argb) {
			draws++;
		}

		@Override
		public void pushClip(int x, int y, int width, int height) {
		}

		@Override
		public void popClip() {
		}

		@Override
		public int drawCalls() {
			return draws;
		}
	}

	private static final class FakeType implements UiType {
		private final UiText text = new UiText() {
			@Override
			public int width(String text) {
				return text == null ? 0 : text.length() * 6;
			}

			@Override
			public int lineHeight() {
				return 10;
			}

			@Override
			public void draw(UiCanvas canvas, String text, int x, int y, int argb) {
			}
		};

		@Override
		public UiText caption() {
			return text;
		}

		@Override
		public UiText regular() {
			return text;
		}

		@Override
		public UiText medium() {
			return text;
		}

		@Override
		public UiText bold() {
			return text;
		}

		@Override
		public UiText display() {
			return text;
		}

		@Override
		public UiText headline() {
			return text;
		}
	}
}
