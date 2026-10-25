package com.capy.client.module.modules.antiadmin;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * Container slots that arrive for a container the client never opened.
 *
 * Container id 0 is the player's own inventory. Every other id is handed out by
 * an open screen packet, and the client only ever receives slot updates for a
 * menu it is currently showing. A slot update for an id that was never opened,
 * or one whose menu has already closed, means the server believes a container
 * screen exists on this client that does not.
 *
 * That is what a plugin pushing a hidden GUI produces, and it is a purely local
 * contradiction: no guessing about who is nearby, no false positive from a
 * legitimate chest, because a legitimate chest always has its open packet.
 */
public final class ContainerDetector extends AbstractDetector {
	private final Set<Integer> opened = new HashSet<>();

	public ContainerDetector(SignalBuffer buffer) {
		super(buffer);
	}

	@Override
	public String name() {
		return "headless_container";
	}

	@Override
	public DetectionKind kind() {
		return DetectionKind.HEADLESS_CONTAINER_ACCESS;
	}

	@Override
	public void reset() {
		opened.clear();
	}

	@Override
	public void onPacket(PacketSignals.Event event) {
		if (event instanceof PacketSignals.OpenScreen screen) {
			opened.add(screen.containerId());
			return;
		}
		if (!(event instanceof PacketSignals.SlotUpdate update)) {
			return;
		}
		int id = update.containerId();
		// Id 0 is the player's own inventory and -1 is the slot sync vanilla
		// uses outside any menu; neither ever came from an open screen.
		if (id <= 0 || opened.contains(id)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.containerMenu.containerId == id) {
			opened.add(id);
			return;
		}
		emitAt(mc.player == null ? null : mc.player.blockPosition(),
				"slots pushed for container " + id + " which this client never opened");
	}
}
