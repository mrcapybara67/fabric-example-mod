package com.capy.client.antivanish;

import com.capy.client.module.modules.antiadmin.PacketSignals;
import com.capy.client.module.modules.antiadmin.TabList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The passive packet path for Anti Vanish.
 *
 * <p>Anti Admin's {@link PacketSignals} queue is drained once per tick by a
 * single consumer. Anti Vanish must not compete with it for the same events,
 * so this bridge records its own stream while reusing the player-info and
 * system-chat records that already describe those packets.</p>
 *
 * <p>Sound and particle events keep their full ids and double positions
 * because the ghost gates need more than PacketSignals' narrowed tags. The
 * bridge never sends anything.</p>
 */
public final class VanishPackets {
	private static final int MAX_EVENTS = 256;
	private static final ConcurrentLinkedDeque<Event> EVENTS = new ConcurrentLinkedDeque<>();

	private VanishPackets() {
	}

	public sealed interface Event permits InfoBatch, Chat, BodyData, Sound, EntitySound, Particle, Explosion,
			Container, Action, PlaceAttempt, BlockUpdate, Suggestions {
		long timeMs();
	}

	/** One player-info packet, grouped so the consumer knows the packet size. */
	public record InfoBatch(List<PacketSignals.InfoUpdate> updates, List<PacketSignals.InfoRemove> removals,
			int entries, long timeMs) implements Event {
		public InfoBatch {
			updates = List.copyOf(updates);
			removals = List.copyOf(removals);
		}
	}

	/** A system chat line, reusing the record Anti Admin already parses. */
	public record Chat(PacketSignals.SystemChat line) implements Event {
		@Override
		public long timeMs() {
			return line.timeMs();
		}
	}

	/** A signal to rescan loaded bodies after entity metadata changed. */
	public record BodyData(long timeMs) implements Event {
	}

	/** A positional sound with its full namespaced id. */
	public record Sound(String id, double x, double y, double z, long timeMs) implements Event {
	}

	/** A sound attached to an entity, used for invisible player bodies. */
	public record EntitySound(String id, int entityId, long timeMs) implements Event {
	}

	/** A particle with its full namespaced id. */
	public record Particle(String id, double x, double y, double z, long timeMs) implements Event {
	}

	/** An explosion centre and its radius. */
	public record Explosion(double x, double y, double z, double radius, long timeMs) implements Event {
	}

	/** A container screen opened or closed. */
	public record Container(boolean open, long timeMs) implements Event {
	}

	/** Any local use, dig, swing or interaction. */
	public record Action(long timeMs) implements Event {
	}

	/** The block an item use is about to place into. */
	public record PlaceAttempt(String blockId, int x, int y, int z, long timeMs) implements Event {
	}

	/** A block update, used to confirm placements and remember powered doors. */
	public record BlockUpdate(String blockId, int x, int y, int z, boolean mechanism, long timeMs) implements Event {
	}

	/** A command-suggestion answer, used only by the optional probe. */
	public record Suggestions(int id, List<String> names, long timeMs) implements Event {
		public Suggestions {
			names = List.copyOf(names);
		}
	}

	public static void onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
		if (packet == null || !playersReady()) {
			return;
		}
		var actions = packet.actions();
		boolean listedAction = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
		boolean modeAction = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
		if (!listedAction && !modeAction) {
			return;
		}
		long time = System.currentTimeMillis();
		List<PacketSignals.InfoUpdate> updates = new ArrayList<>(packet.entries().size());
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			String name = entry.profile() == null ? null : entry.profile().name();
			Integer mode = modeAction && entry.gameMode() != null ? entry.gameMode().getId() : null;
			updates.add(new PacketSignals.InfoUpdate(entry.profileId(), name,
					listedAction ? entry.listed() : null, mode, null, time));
		}
		push(new InfoBatch(updates, List.of(), packet.entries().size(), time));
	}

	public static void onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet) {
		if (packet == null || !playersReady()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		long time = System.currentTimeMillis();
		List<PacketSignals.InfoRemove> removals = new ArrayList<>(packet.profileIds().size());
		for (UUID id : packet.profileIds()) {
			// Vanilla has not applied the removal yet, so the name is readable.
			removals.add(new PacketSignals.InfoRemove(id, TabList.nameOf(mc, id), time));
		}
		push(new InfoBatch(List.of(), removals, packet.profileIds().size(), time));
	}

	public static void onSystemChat(ClientboundSystemChatPacket packet) {
		if (packet == null || packet.content() == null || !clientThread()) {
			return;
		}
		push(new Chat(new PacketSignals.SystemChat(packet.content().getString(), System.currentTimeMillis())));
	}

	/** Records one command-suggestion answer for the optional probe. */
	public static void onCommandSuggestions(ClientboundCommandSuggestionsPacket packet) {
		if (packet == null || !clientThread()) {
			return;
		}
		List<String> names = new ArrayList<>(packet.suggestions().size());
		for (ClientboundCommandSuggestionsPacket.Entry entry : packet.suggestions()) {
			if (entry != null && entry.text() != null) {
				names.add(entry.text());
			}
		}
		push(new Suggestions(packet.id(), names, System.currentTimeMillis()));
	}

	public static void onSound(ClientboundSoundPacket packet) {
		if (packet == null || packet.getSound() == null || packet.getSound().value() == null || !clientThread()) {
			return;
		}
		Identifier id = packet.getSound().value().location();
		if (id != null) {
			push(new Sound(id.toString(), packet.getX(), packet.getY(), packet.getZ(), System.currentTimeMillis()));
		}
	}

	public static void onSoundEntity(ClientboundSoundEntityPacket packet) {
		if (packet == null || packet.getSound() == null || packet.getSound().value() == null || !clientThread()) {
			return;
		}
		Identifier id = packet.getSound().value().location();
		if (id != null) {
			push(new EntitySound(id.toString(), packet.getId(), System.currentTimeMillis()));
		}
	}

	public static void onParticle(ClientboundLevelParticlesPacket packet) {
		if (packet == null || packet.getParticle() == null || !clientThread()) {
			return;
		}
		Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType());
		if (id != null) {
			push(new Particle(id.toString(), packet.getX(), packet.getY(), packet.getZ(), System.currentTimeMillis()));
		}
	}

	public static void onExplosion(ClientboundExplodePacket packet) {
		if (packet == null || packet.center() == null || !clientThread()) {
			return;
		}
		push(new Explosion(packet.center().x, packet.center().y, packet.center().z, packet.radius(),
				System.currentTimeMillis()));
	}

	public static void onContainer(boolean open) {
		if (clientThread()) {
			push(new Container(open, System.currentTimeMillis()));
		}
	}

	public static void onAction() {
		if (clientThread()) {
			push(new Action(System.currentTimeMillis()));
		}
	}

	public static void onPlaceAttempt(BlockPos pos, Block block) {
		if (pos == null || block == null || !clientThread()) {
			return;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		if (id != null) {
			push(new PlaceAttempt(id.toString(), pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis()));
		}
	}

	public static void onBlockUpdate(BlockPos pos, BlockState state) {
		if (pos == null || state == null || !clientThread()) {
			return;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id != null) {
			boolean mechanism = state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS);
			push(new BlockUpdate(id.toString(), pos.getX(), pos.getY(), pos.getZ(), mechanism,
					System.currentTimeMillis()));
		}
	}

	public static void onEntityData() {
		if (clientThread()) {
			push(new BodyData(System.currentTimeMillis()));
		}
	}

	/** Everything queued since the last call, oldest first. */
	public static List<Event> drain() {
		if (EVENTS.isEmpty()) {
			return List.of();
		}
		List<Event> out = new ArrayList<>(EVENTS.size());
		Event event;
		while ((event = EVENTS.pollFirst()) != null) {
			out.add(event);
		}
		return out;
	}

	public static void clear() {
		EVENTS.clear();
	}

	private static void push(Event event) {
		while (EVENTS.size() >= MAX_EVENTS) {
			EVENTS.pollFirst();
		}
		EVENTS.addLast(event);
	}

	private static boolean clientThread() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread();
	}

	private static boolean playersReady() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread() && mc.getConnection() != null && mc.player != null;
	}
}
