package com.capy.client.gui;

/** Pure viewport math used by the card list and its tests. */
public final class VirtualList {
	private VirtualList() {
	}

	/** True when an item intersects the visible span. */
	public static boolean intersects(int itemTop, int itemHeight, int viewTop, int viewBottom) {
		return itemHeight > 0 && itemTop < viewBottom && itemTop + itemHeight > viewTop;
	}

	/**
	 * First and exclusive last item index for a scroll offset. Heights include
	 * the gap between items, so callers can keep one flat cursor.
	 */
	public static int[] range(int scroll, int viewport, int[] heights, int gap) {
		if (heights.length == 0 || viewport <= 0) {
			return new int[]{0, 0};
		}
		int top = Math.max(0, scroll);
		int bottom = top + viewport;
		int cursor = 0;
		int first = -1;
		int last = 0;
		for (int i = 0; i < heights.length; i++) {
			int h = Math.max(0, heights[i]);
			int itemTop = cursor;
			int itemBottom = cursor + h;
			if (itemBottom > top && itemTop < bottom) {
				if (first < 0) {
					first = i;
				}
				last = i + 1;
			} else if (first >= 0 && itemTop >= bottom) {
				break;
			}
			cursor = itemBottom + gap;
		}
		return new int[]{Math.max(0, first), last};
	}
}
