package com.capy.client.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.capy.client.module.Category;
import com.capy.client.module.Module;
import com.capy.client.module.ModuleManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Toggling a module must change only its enabled state and its own switch. The
 * card list, the retained row layout and the culling result must stay the same
 * objects and values across the next frame.
 */
class GuiToggleStabilityTest {
	@Test
	void everyToggleKeepsTheCardListAndLayoutStable() {
		List<Module> testModules = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Module module = new FakeModule("toggle_stability_" + i);
			testModules.add(module);
			ModuleManager.get().register(module);
		}
		try {
			GuiFrame frame = new GuiFrame();
			frame.resize(640, 360);
			frame.setCategory(Category.MISC.ordinal());
			FakeCanvas canvas = new FakeCanvas();
			FakeType type = new FakeType();
			render(frame, canvas, type);

			List<ModuleCard> list = frame.visibleCardsForTest();
			int[] tops = frame.layoutTopsForTest();
			int visible = frame.visibleCardCountForTest();
			assertEquals(testModules.size(), list.size());

			for (Module module : List.copyOf(ModuleManager.get().modules())) {
				module.toggle();
				render(frame, canvas, type);
				assertSame(list, frame.visibleCardsForTest(), "list replaced after " + module.id());
				assertArrayEquals(tops, frame.layoutTopsForTest(), "layout moved after " + module.id());
				assertEquals(visible, frame.visibleCardCountForTest(),
						"visible card count changed after " + module.id());
			}
		} finally {
			for (Module module : testModules) {
				ModuleManager.get().unregister(module);
			}
		}
	}

	private static void render(GuiFrame frame, UiCanvas canvas, UiType type) {
		GuiPerf.begin();
		frame.render(canvas, type, -20, -20, 20f, 1f);
		GuiPerf.end(canvas.drawCalls());
	}

	private static final class FakeModule extends Module {
		FakeModule(String id) {
			super(Category.MISC, id);
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
