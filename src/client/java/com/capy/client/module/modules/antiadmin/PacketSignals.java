package com.capy.client.module.modules.antiadmin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one place packets enter the module.
 *
 * The mixin calls exactly one method per hooked packet, and everything after
 * that is normalised into an {@link Event} and queued for the detectors. Doing
 * the translation here rather than in fifteen detectors means the mixin stays
 * three lines per hook, nothing but this class imports a packet type, and the
 * detector code reads like the game it is describing instead of like protocol
 * plumbing.
 *
 * <p>Threading: vanilla runs these handlers on the client thread — the initial
 * read happens on Netty, then the work is re-scheduled onto the client thread —
 * and every entry point checks that before touching anything. The queue is a
 * {@link ConcurrentLinkedDeque} anyway, because it is drained from the tick and
 * a future version of the game is free to change that assumption.</p>
 */
public final class PacketSignals {
	/** The queue is capped; a flood of packets must not become a leak. */
	private static final int MAX_EVENTS = 256;
	/** Probe ids start far above vanilla's, so a reply can never be misread. */
	private static final AtomicInteger PROBE_IDS = new AtomicInteger(Integer.MAX_VALUE - 1024);

	private static final ConcurrentLinkedDeque<Event> EVENTS = new ConcurrentLinkedDeque<>();

	/**
	 * Every chat and system line, in arrival order.
	 *
	 * <p>Separate from {@link #EVENTS} on purpose: the detectors drain that queue
	 * once per tick, so a second reader would steal their input. This one is
	 * append only and read by sequence number, which lets the anticheat notifier
	 * read the same lines without consuming anything. It is the only evidence
	 * about the server's own plugins a client is ever handed - a message
	 * prefixed {@code "Vulcan »"} is the anticheat naming itself.
	 */
	private static final ConcurrentLinkedDeque<TextLine> TEXT = new ConcurrentLinkedDeque<>();
	private static final java.util.concurrent.atomic.AtomicLong TEXT_SEQ = new java.util.concurrent.atomic.AtomicLong();
	private static final int MAX_TEXT = 64;

	private PacketSignals() {
	}

	// ------------------------------------------------------------------ events

	/** Everything a detector can be handed. Sealed so the set stays closed. */
	public sealed interface Event permits Particle, SoundAt, Spawn, BlockChange, InfoUpdate, InfoRemove,
			TeamRemove, Chat, SystemChat, OpenScreen, OpenSign, OpenBook, SlotUpdate, Suggestions, EntitiesRemoved {
		long timeMs();
	}

	/** A particle the client was told to draw. */
	public record Particle(BlockPos pos, String tag, long timeMs) implements Event {
	}

	/** A sound the client was told to play. */
	public record SoundAt(BlockPos pos, String tag, long timeMs) implements Event {
	}

	/** An entity the server spawned. */
	public record Spawn(EntityType<?> type, BlockPos pos, long timeMs) implements Event {
	}

	/** A block whose state changed. */
	public record BlockChange(BlockPos pos, boolean interactive, long timeMs) implements Event {
	}

	/**
	 * One entry of a player info update.
	 *
	 * The nullable fields say which actions the packet carried: a latency
	 * packet has no game mode, and pretending otherwise would make every
	 * update look like a spectator cycle.
	 */
	public record InfoUpdate(UUID uuid, String name, Boolean listed, Integer gameMode, Integer latency,
			long timeMs) implements Event {
	}

	/** A player info entry the server removed. */
	public record InfoRemove(UUID uuid, String name, long timeMs) implements Event {
	}

	/** Players the server kicked out of a scoreboard team. */
	public record TeamRemove(String team, List<String> names, long timeMs) implements Event {
	}

	/** A signed chat message and its sender. */
	public record Chat(UUID sender, String text, long timeMs) implements Event {
	}

	/** A server message with no sender. */
	public record SystemChat(String text, long timeMs) implements Event {
	}

	/** A container screen the server opened for the local player. */
	public record OpenScreen(int containerId, long timeMs) implements Event {
	}

	/** A sign editor the server opened. */
	public record OpenSign(BlockPos pos, long timeMs) implements Event {
	}

	/** A book the server opened. */
	public record OpenBook(long timeMs) implements Event {
	}

	/** One slot of a container the client has open (or should have). */
	public record SlotUpdate(int containerId, int slot, long timeMs) implements Event {
	}

	/** The reply to a tab completion request. */
	public record Suggestions(int id, List<String> names, long timeMs) implements Event {
	}

	/** Entities the server removed, resolved to player UUIDs where possible. */
	public record EntitiesRemoved(List<UUID> uuids, long timeMs) implements Event {
	}

	/** One chat line, with the sequence number a second reader resumes from. */
	public record TextLine(long seq, String text, boolean system) {
	}

	// --------------------------------------------------------------- recording

	public static void onParticle(ClientboundLevelParticlesPacket packet) {
		if (!clientThread()) {
			return;
		}
		String tag = particleTag(packet);
		if (tag != null) {
			push(new Particle(BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()), tag, now()));
		}
	}

	public static void onSound(ClientboundSoundPacket packet) {
		if (!clientThread() || packet.getSound() == null) {
			return;
		}
		String tag = soundTag(packet.getSound().value().location());
		if (tag != null) {
			push(new SoundAt(BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()), tag, now()));
		}
	}

	public static void onEntitySpawn(ClientboundAddEntityPacket packet) {
		if (!clientThread()) {
			return;
		}
		EntityType<?> type = packet.getType();
		if (type == EntityType.TNT || type == EntityType.TNT_MINECART || type == EntityType.END_CRYSTAL) {
			push(new Spawn(type, BlockPos.containing(packet.getX(), packet.getY(), packet.getZ()), now()));
		}
	}

	public static void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
		if (!clientThread()) {
			return;
		}
		BlockState state = packet.getBlockState();
		if (state == null) {
			return;
		}
		boolean interactive = state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
				|| state.is(BlockTags.BUTTONS) || state.is(BlockTags.FENCE_GATES)
				|| state.getBlock() instanceof LeverBlock;
		if (interactive) {
			push(new BlockChange(packet.getPos(), true, now()));
		}
	}

	public static void onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
		if (!playersReady()) {
			return;
		}
		var actions = packet.actions();
		boolean listedAction = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
		boolean modeAction = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
		boolean latencyAction = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY);
		if (!listedAction && !modeAction && !latencyAction) {
			return;
		}
		long time = now();
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			String name = entry.profile() == null ? null : entry.profile().name();
			push(new InfoUpdate(entry.profileId(), name,
					listedAction ? entry.listed() : null,
					modeAction ? entry.gameMode().getId() : null,
					latencyAction ? entry.latency() : null,
					time));
		}
	}

	public static void onPlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet) {
		if (!playersReady()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		long time = now();
		for (UUID id : packet.profileIds()) {
			// Vanilla has not applied the removal yet, so the entry is readable.
			push(new InfoRemove(id, TabList.nameOf(mc, id), time));
		}
	}

	public static void onTeam(ClientboundSetPlayerTeamPacket packet) {
		if (!clientThread() || packet.getPlayerAction() != ClientboundSetPlayerTeamPacket.Action.REMOVE) {
			return;
		}
		List<String> names = new ArrayList<>(packet.getPlayers());
		if (!names.isEmpty()) {
			push(new TeamRemove(packet.getName(), names, now()));
		}
	}

	public static void onPlayerChat(ClientboundPlayerChatPacket packet) {
		if (!clientThread()) {
			return;
		}
		String text = packet.body() == null ? "" : packet.body().content();
		remember(text, false);
		// Player chat is not evidence: only a server system message is. The
		// detector still receives the event so its system-only path stays in
		// one place.
		push(new Chat(packet.sender(), text == null ? "" : text, now()));
	}

	public static void onSystemChat(ClientboundSystemChatPacket packet) {
		if (!clientThread() || packet.content() == null) {
			return;
		}
		String text = packet.content().getString();
		remember(text, true);
		ChatPresence.recordSystem(text);
		push(new SystemChat(text, now()));
	}

	public static void onOpenScreen(ClientboundOpenScreenPacket packet) {
		if (!clientThread()) {
			return;
		}
		push(new OpenScreen(packet.getContainerId(), now()));
	}

	public static void onOpenSign(ClientboundOpenSignEditorPacket packet) {
		if (!clientThread()) {
			return;
		}
		push(new OpenSign(packet.getPos(), now()));
	}

	public static void onOpenBook(ClientboundOpenBookPacket packet) {
		if (!clientThread()) {
			return;
		}
		push(new OpenBook(now()));
	}

	public static void onContainerSlot(ClientboundContainerSetSlotPacket packet) {
		if (!clientThread()) {
			return;
		}
		push(new SlotUpdate(packet.getContainerId(), packet.getSlot(), now()));
	}

	public static void onSuggestions(ClientboundCommandSuggestionsPacket packet) {
		if (!clientThread()) {
			return;
		}
		List<String> names = new ArrayList<>();
		for (var suggestion : packet.toSuggestions().getList()) {
			names.add(suggestion.getText());
		}
		push(new Suggestions(packet.id(), names, now()));
	}

	public static void onRemoveEntities(ClientboundRemoveEntitiesPacket packet) {
		if (!clientThread()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		List<UUID> uuids = new ArrayList<>(packet.getEntityIds().size());
		for (int id : packet.getEntityIds()) {
			net.minecraft.world.entity.Entity entity = mc.level.getEntity(id);
			if (entity != null) {
				uuids.add(entity.getUUID());
			}
		}
		if (!uuids.isEmpty()) {
			push(new EntitiesRemoved(uuids, now()));
		}
	}

	// ----------------------------------------------------------------- probing

	/**
	 * Asks the server to complete a name.
	 *
	 * This is a tab completion request, not a chat command: nothing is executed,
	 * nothing appears in the server log as a message, and the reply is the
	 * server's own list of online players — which is the one place a vanished
	 * account still shows up. {@code command} must end mid-argument so the reply
	 * is the name list rather than a command list.
	 *
	 * @return the request id the reply will carry, or -1 when there is no
	 *         connection to ask
	 */
	public static int probe(String command) {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc == null ? null : mc.getConnection();
		if (connection == null || command == null || command.isBlank()) {
			return -1;
		}
		int id = PROBE_IDS.getAndIncrement();
		connection.send(new ServerboundCommandSuggestionPacket(id, command));
		return id;
	}

	// ---------------------------------------------------------------- draining

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

	/** Drops everything queued. Called on a world change or a toggle. */
	public static void clear() {
		EVENTS.clear();
		TEXT.clear();
		ChatPresence.clear();
	}

	// ------------------------------------------------------------- text lines

	/**
	 * The chat and system lines that arrived after {@code seq}, oldest first.
	 *
	 * Reading does not consume: several readers can follow the same stream at
	 * their own pace, and a reader that stops for a while simply skips ahead by
	 * asking for everything past the last sequence number it saw.
	 */
	public static List<TextLine> textAfter(long seq) {
		if (TEXT.isEmpty()) {
			return List.of();
		}
		List<TextLine> out = new ArrayList<>();
		for (TextLine line : TEXT) {
			if (line.seq() > seq) {
				out.add(line);
			}
		}
		return out;
	}

	/** The sequence number of the newest line, for a reader that joins late. */
	public static long textSequence() {
		return TEXT_SEQ.get();
	}

	private static void remember(String text, boolean system) {
		if (text == null || text.isEmpty()) {
			return;
		}
		while (TEXT.size() >= MAX_TEXT) {
			TEXT.pollFirst();
		}
		TEXT.addLast(new TextLine(TEXT_SEQ.incrementAndGet(), text, system));
	}

	// --------------------------------------------------------------- internals

	private static void push(Event event) {
		// Oldest out first, so the queue holds the most recent evidence rather
		// than the first evidence of the session.
		while (EVENTS.size() >= MAX_EVENTS) {
			EVENTS.pollFirst();
		}
		EVENTS.addLast(event);
	}

	private static boolean clientThread() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread();
	}

	/**
	 * True when the tab list can be read.
	 *
	 * <p>Player info is the first thing a server sends, so these handlers can
	 * run before the client has a world, a player or even a connection object.
	 * Ask the client a question in that window and it answers with a null
	 * pointer, which is a crash on join rather than a missing alert.</p>
	 */
	private static boolean playersReady() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.isSameThread() && mc.getConnection() != null && mc.player != null;
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	private static String particleTag(ClientboundLevelParticlesPacket packet) {
		var type = packet.getParticle().getType();
		if (type == ParticleTypes.INSTANT_EFFECT) {
			return "instant_effect";
		}
		if (type == ParticleTypes.WITCH) {
			return "witch";
		}
		if (type == ParticleTypes.EFFECT) {
			return "effect";
		}
		return null;
	}

	/**
	 * Reduces a sound id to the four tags worth watching.
	 *
	 * The full id is kept in the evidence text, because "footstep" alone does
	 * not tell a user which block or which armour it was.
	 */
	private static String soundTag(Identifier id) {
		if (id == null) {
			return null;
		}
		String path = id.getPath().toLowerCase(Locale.ROOT);
		if (path.contains("footstep")) {
			return "footstep";
		}
		if (path.contains("armor.equip")) {
			return "armor";
		}
		// Two spellings on purpose: vanilla sends `entity.item.pickup`, and the
		// `item_pickup` form is what plugin sound packs use. The second test used
		// to repeat the first, so a server using that spelling never matched.
		if (path.contains("item.pickup") || path.contains("item_pickup")) {
			return "pickup";
		}
		// `contains` alone covers the suffix the old test listed separately.
		if (path.contains(".eat") || path.contains("burp")) {
			return "eat";
		}
		return null;
	}

	/** The display name of a sound id, used in evidence. */
	public static String describe(Identifier id) {
		return id == null ? "sound" : id.getPath();
	}
}
