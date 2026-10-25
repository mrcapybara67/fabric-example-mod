package com.capy.client.module.modules.antiadmin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A screen the server opened for the local player that vanilla would not.
 *
 * Vanilla opens a sign editor only for a sign the player is standing next to,
 * and a book only while that player is holding one. Anything else was opened by
 * a plugin, and a plugin opening an editor on this client is either a menu
 * being forced onto it or a packet aimed at the wrong player — in both cases
 * the client is being made to interact with a world it cannot see.
 *
 * The reach is checked at six blocks rather than vanilla's 4.5 so a laggy
 * player clicking a sign is never reported.
 */
public final class GhostUiDetector extends AbstractDetector {
	/** Six blocks, squared. */
	private static final double MAX_SIGN_REACH = 36.0;

	public GhostUiDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "ghost_ui";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.GHOST_UI_OPEN;
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (event instanceof PacketSignals.OpenSign sign) {
			BlockPos pos = sign.pos();
			if (pos != null && mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
					> MAX_SIGN_REACH) {
				emitAt(pos, "sign editor opened for a block out of the player's reach");
			}
			return;
		}
		if (event instanceof PacketSignals.OpenBook) {
			if (!holdingBook(mc.player.getMainHandItem()) && !holdingBook(mc.player.getOffhandItem())) {
				emitAt(mc.player.blockPosition(), "book opened with no book in either hand");
			}
		}
	}

	private static boolean holdingBook(ItemStack stack) {
		return stack != null && (stack.is(Items.WRITTEN_BOOK) || stack.is(Items.WRITABLE_BOOK));
	}
}
