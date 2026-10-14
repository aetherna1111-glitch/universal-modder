package dev.luminary.fabric.npc;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.trade.PlayerLedger;
import dev.luminary.core.npc.trade.Quest;
import dev.luminary.core.npc.trade.ShopOffer;
import dev.luminary.core.npc.trade.TradeService;
import dev.luminary.fabric.Luminary;
import dev.luminary.fabric.state.NpcWorldState;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The NPC's points economy on the server: quests for its points, a shop for them, and the special quest that ends
 * with the blade changing hands. Rules live in {@link TradeService}; this class owns the ledgers (in the NPC record,
 * persisted with it), the kill and death hooks, the items handed out and the screen packets. Nothing a client sends
 * is trusted: every action is re-checked here against the ledger and the player's distance to the body.
 */
public final class TradeManager {
	public static final Identifier OPEN_PACKET = new Identifier(Luminary.MOD_ID, "trade_open");
	public static final Identifier ACTION_PACKET = new Identifier(Luminary.MOD_ID, "trade_action");
	public static final int ACTION_ACCEPT = 0, ACTION_CLAIM = 1, ACTION_BUY = 2, ACTION_GEAR = 3, ACTION_TALK = 4, ACTION_REFRESH = 5, ACTION_DONATE = 6;
	public static final double REACH = 8.0;
	public static final String REASON_UNWORTHY = "luminary.trade.unworthy";
	public static final String REASON_NOT_ENOUGH_EMERALDS = "luminary.trade.not_enough_emeralds";

	private final NpcService npc;
	private final NpcWorldState state;
	private final Supplier<Instant> clock;
	private final TradeService service;

	TradeManager(NpcService npc, NpcWorldState state, Supplier<Instant> clock) {
		this.npc = npc;
		this.state = state;
		this.clock = clock;
		this.service = new TradeService(id -> npc.dialogue().definitions().quest(id));
	}

	public TradeService service() {
		return service;
	}

	public PlayerLedger ledger(LuminaryId id, PlayerEntity player) {
		return state.roster().ensureRecord(id).ledger(player.getUuid());
	}

	public List<Quest> quests(LuminaryId id) {
		return npc.dialogue().definitions().questsOf(id);
	}

	/** The offers whose item exists in this game; a shop line naming a mod that is not installed is skipped silently. */
	public List<ShopOffer> offers(LuminaryId id) {
		List<ShopOffer> out = new ArrayList<>();
		for (ShopOffer o : npc.dialogue().definitions().npc(id).map(NpcDefinition::shop).orElse(List.of())) {
			if (item(o).isPresent()) {
				out.add(o);
			}
		}
		return out;
	}

	static Optional<Item> item(ShopOffer o) {
		Identifier iid = Identifier.tryParse(o.itemId());
		return iid == null ? Optional.empty() : Registries.ITEM.getOrEmpty(iid);
	}

	/** Does this NPC trade at all (a shop or at least one quest)? */
	public boolean trades(LuminaryId id) {
		return !offers(id).isEmpty() || !quests(id).isEmpty();
	}

	// ------------------------------------------------------------------ actions

	public Result accept(LuminaryId id, PlayerEntity player, LuminaryId quest) {
		PlayerLedger ledger = ledger(id, player);
		if (npc.dialogue().definitions().quest(quest).map(Quest::special).orElse(false) && !npc.behaviour().worthy(id, player.getUuid())) {
			return Result.fail(REASON_UNWORTHY);   // the blade is not for someone he distrusts
		}
		Result r = service.accept(quest, ledger);
		if (r.ok()) {
			Quest q = npc.dialogue().definitions().quest(quest).orElseThrow();
			npc.behaviour().remember(id, MemoryKind.RECENT_INTERACTION, player.getUuid(), "quest/accept", player.getGameProfile().getName() + " took the task '" + q.id().path() + "'");
			state.markDirty();
		}
		return r;
	}

	/** Points credited, reward items handed over, and for the special quest the blade itself. */
	public Result claim(LuminaryId id, PlayerEntity player, LuminaryId questId) {
		PlayerLedger ledger = ledger(id, player);
		Result r = service.claim(questId, ledger);
		if (!r.ok()) {
			return r;
		}
		Quest q = npc.dialogue().definitions().quest(questId).orElseThrow();
		state.roster().ensureRecord(id).relationships().apply(player.getUuid(), dev.luminary.core.npc.relationship.RelationshipEvent.QUEST_DONE, clock.get(), state.roster().rules().relationship());
		for (String itemId : q.rewardItems()) {
			Identifier iid = Identifier.tryParse(itemId);
			if (iid != null) {
				Registries.ITEM.getOrEmpty(iid).ifPresent(it -> give(player, new ItemStack(it)));
			}
		}
		npc.behaviour().remember(id, MemoryKind.SIGNIFICANT_EVENT, player.getUuid(), "quest/done", player.getGameProfile().getName() + " finished '" + q.id().path() + "' (+" + q.rewardPoints() + " points)");
		if (q.special()) {
			Result t = npc.behaviour().transferWeapon(id, player, "the Trial of the Envoy, done without buying a thing");
			if (!t.ok()) {
				Luminary.LOGGER.warn("[luminary] special quest {} claimed by {} but the blade could not be transferred: {}", q.id(), player.getGameProfile().getName(), t.reasonKey());
			}
		}
		state.markDirty();
		return r;
	}

	/**
	 * Admin: every quest of the NPC completed for the player by decree, {@code special} included or not. Points and
	 * reward items are handed out as for a real claim; the special quest hands over the blade. Returns how many.
	 */
	public int completeAll(LuminaryId id, PlayerEntity player, boolean special) {
		PlayerLedger ledger = ledger(id, player);
		int n = 0;
		for (Quest q : quests(id)) {
			if (q.special() && !special) {
				continue;
			}
			Optional<Quest> done = service.forceComplete(q.id(), ledger);
			if (done.isEmpty()) {
				continue;
			}
			n++;
			state.roster().ensureRecord(id).relationships().apply(player.getUuid(), dev.luminary.core.npc.relationship.RelationshipEvent.QUEST_DONE, clock.get(), state.roster().rules().relationship());
			for (String itemId : q.rewardItems()) {
				Identifier iid = Identifier.tryParse(itemId);
				if (iid != null) {
					Registries.ITEM.getOrEmpty(iid).ifPresent(it -> give(player, new ItemStack(it)));
				}
			}
			if (q.special()) {
				npc.behaviour().transferWeapon(id, player, "command: every quest completed by decree");
			}
		}
		if (n > 0) {
			npc.behaviour().remember(id, MemoryKind.SIGNIFICANT_EVENT, player.getUuid(), "quest/decree", "an administrator marked " + n + " of my tasks done for " + player.getGameProfile().getName());
			state.markDirty();
		}
		return n;
	}

	/** Admin: the player's ledger with the NPC wiped; a blade transferred to that player is reclaimed. Returns whether anything changed. */
	public boolean reset(LuminaryId id, PlayerEntity player) {
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		boolean changed = record.resetLedger(player.getUuid());
		if (record.hasWeaponRecord() && record.weapon().custody() == dev.luminary.core.npc.weapon.WeaponCustody.Custody.TRANSFERRED
				&& record.weapon().state().holder().filter(player.getUuid()::equals).isPresent()) {
			npc.behaviour().reclaimWeapon(id);
			changed = true;
		}
		if (changed) {
			state.markDirty();
		}
		return changed;
	}

	/** Alms: {@value KarmaEffects#DONATION_EMERALDS} emeralds out of the player's inventory, {@value KarmaEffects#DONATION_KARMA} karma onto the ledger. */
	public Result donate(LuminaryId id, PlayerEntity player) {
		int need = dev.luminary.core.npc.relationship.KarmaEffects.DONATION_EMERALDS;
		if (count(player, net.minecraft.item.Items.EMERALD) < need) {
			return Result.fail(REASON_NOT_ENOUGH_EMERALDS);
		}
		int left = need;
		for (int i = 0; i < player.getInventory().size() && left > 0; i++) {
			ItemStack s = player.getInventory().getStack(i);
			if (s.isOf(net.minecraft.item.Items.EMERALD)) {
				int take = Math.min(left, s.getCount());
				s.decrement(take);
				left -= take;
			}
		}
		player.getInventory().markDirty();
		state.roster().ensureRecord(id).relationships().apply(player.getUuid(), dev.luminary.core.npc.relationship.RelationshipEvent.DONATION, clock.get(), state.roster().rules().relationship());
		npc.behaviour().applyKarmaLuck(player);
		npc.behaviour().remember(id, MemoryKind.RECENT_INTERACTION, player.getUuid(), "donation", player.getGameProfile().getName() + " gave me " + need + " emeralds and asked nothing");
		state.markDirty();
		return Result.OK;
	}

	/** Alms received: a bell and a chime at the body, light rising around it and souls drifting off the giver's wrong. */
	public static void almsEffects(LuminaryNpcEntity body) {
		if (!(body.getWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
			return;
		}
		world.playSound(null, body.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_BELL_RESONATE, net.minecraft.sound.SoundCategory.NEUTRAL, 0.8f, 1.2f);
		world.playSound(null, body.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.NEUTRAL, 1.0f, 1.0f);
		world.playSound(null, body.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP, net.minecraft.sound.SoundCategory.NEUTRAL, 0.5f, 1.6f);
		for (int i = 0; i < 24; i++) {   // a ring of light rising around him
			double a = i * Math.PI / 12;
			world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, body.getX() + Math.cos(a) * 0.9, body.getY() + 0.2, body.getZ() + Math.sin(a) * 0.9, 1, 0, 0.06, 0, 0.0);
		}
		world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL, body.getX(), body.getY() + 1.6, body.getZ(), 10, 0.5, 0.4, 0.5, 0.02);
		world.spawnParticles(net.minecraft.particle.ParticleTypes.GLOW, body.getX(), body.getY() + 1.0, body.getZ(), 20, 0.6, 0.8, 0.6, 0.01);
		world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, body.getX(), body.getY() + 2.2, body.getZ(), 8, 0.4, 0.3, 0.4, 0.0);
	}

	static int count(PlayerEntity player, Item item) {
		int n = 0;
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack s = player.getInventory().getStack(i);
			if (s.isOf(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public Result buy(LuminaryId id, PlayerEntity player, int index) {
		List<ShopOffer> offers = offers(id);
		boolean ownsUnique = index >= 0 && index < offers.size() && item(offers.get(index)).map(it -> owns(player, it)).orElse(false);
		PlayerLedger ledger = ledger(id, player);
		Result r = service.buy(offers, index, ledger, ownsUnique);
		if (!r.ok()) {
			return r;
		}
		ShopOffer o = offers.get(index);
		give(player, new ItemStack(item(o).orElseThrow(), o.count()));
		npc.behaviour().remember(id, MemoryKind.RECENT_INTERACTION, player.getUuid(), "trade/buy", player.getGameProfile().getName() + " bought " + o.count() + "x " + o.itemId() + " for " + o.price() + " points");
		state.markDirty();
		return r;
	}

	static boolean owns(PlayerEntity player, Item item) {
		for (int i = 0; i < player.getInventory().size(); i++) {
			if (player.getInventory().getStack(i).isOf(item)) {
				return true;
			}
		}
		return false;
	}

	static void give(PlayerEntity player, ItemStack stack) {
		if (!player.giveItemStack(stack)) {
			player.dropItem(stack, false);
		}
	}

	// ------------------------------------------------------------------ world hooks

	/** A player killed something: every trading NPC's ledger for that player advances. The player hears about finished tasks. */
	public void onKill(PlayerEntity killer, Entity victim) {
		String entityId = Registries.ENTITY_TYPE.getId(victim.getType()).toString();
		for (NpcDefinition def : npc.dialogue().definitions().npcs()) {
			if (quests(def.id()).isEmpty()) {
				continue;
			}
			NpcRoster.NpcRecord record = state.roster().ensureRecord(def.id());
			PlayerLedger ledger = record.ledger(killer.getUuid());
			if (ledger.active().isEmpty()) {
				continue;
			}
			List<Quest> ready = service.onKill(entityId, ledger);
			boolean touched = ledger.active().entrySet().stream().anyMatch(e -> npc.dialogue().definitions().quest(e.getKey()).map(q -> q.kills().containsKey(entityId)).orElse(false));
			if (touched) {
				state.markDirty();
			}
			for (Quest q : ready) {
				killer.sendMessage(Text.translatable("npc.luminary.trade.quest_ready", Text.translatable(def.nameKey()), Text.translatable(q.titleKey())).formatted(Formatting.GOLD), false);
			}
			if (ready.isEmpty() && touched) {
				for (var e : ledger.active().entrySet()) {
					npc.dialogue().definitions().quest(e.getKey()).filter(q -> q.kills().containsKey(entityId)).ifPresent(q ->
							killer.sendMessage(Text.translatable("npc.luminary.trade.progress", Text.translatable(q.titleKey()), Math.min(e.getValue().total(), q.totalRequired()), q.totalRequired()).formatted(Formatting.GRAY, Formatting.ITALIC), true));
				}
			}
		}
	}

	/** A player died: the quests that reset on death start over, and the player is told. */
	public void onDeath(PlayerEntity player) {
		for (NpcDefinition def : npc.dialogue().definitions().npcs()) {
			if (quests(def.id()).isEmpty()) {
				continue;
			}
			PlayerLedger ledger = state.roster().ensureRecord(def.id()).ledger(player.getUuid());
			for (Quest q : service.onDeath(ledger)) {
				player.sendMessage(Text.translatable("npc.luminary.trade.quest_reset", Text.translatable(q.titleKey())).formatted(Formatting.RED), false);
				state.markDirty();
			}
		}
	}

	// ------------------------------------------------------------------ the screen

	/** Opens (or refreshes) the trade screen on the player's client with a full snapshot. */
	public void open(LuminaryNpcEntity body, ServerPlayerEntity player, String message) {
		LuminaryId id = body.npcId().orElse(null);
		if (id == null) {
			return;
		}
		ServerPlayNetworking.send(player, OPEN_PACKET, snapshot(body, id, player, message));
	}

	PacketByteBuf snapshot(LuminaryNpcEntity body, LuminaryId id, ServerPlayerEntity player, String message) {
		PlayerLedger ledger = ledger(id, player);
		List<ShopOffer> offers = offers(id);
		List<TradeService.QuestView> views = service.views(quests(id), ledger);
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(body.getId());
		buf.writeText(body.getCustomName() != null ? body.getCustomName() : Text.translatable(npc.dialogue().definitions().npc(id).map(NpcDefinition::nameKey).orElse("npc")));
		buf.writeString(greeting(id, ledger, views), 4096);
		buf.writeString(message == null ? "" : message, 4096);
		buf.writeVarInt(ledger.points());
		buf.writeVarInt(ledger.completed().size());
		buf.writeVarInt(ledger.purchases());
		buf.writeBoolean(ledger.specialAvailable());
		dev.luminary.core.npc.relationship.Relationship rel = npc.behaviour().relationship(id, player.getUuid());
		double karma = npc.behaviour().karmaOf(player.getUuid());
		buf.writeDouble(karma);
		buf.writeDouble(rel.trust());
		buf.writeDouble(rel.threat());
		buf.writeString(npc.behaviour().standing(id, player.getUuid()).name(), 32);
		buf.writeBoolean(npc.behaviour().worthy(id, player.getUuid()));
		buf.writeDouble(dev.luminary.core.npc.relationship.KarmaEffects.luck(karma));
		buf.writeDouble(dev.luminary.core.npc.relationship.KarmaEffects.markup(karma));
		buf.writeVarInt(offers.size());
		for (ShopOffer o : offers) {
			Item it = item(o).orElseThrow();
			buf.writeItemStack(new ItemStack(it, o.count()));
			buf.writeVarInt(o.price());
			buf.writeBoolean(o.unique());
			buf.writeBoolean(o.unique() && owns(player, it));
		}
		buf.writeVarInt(views.size());
		for (TradeService.QuestView v : views) {
			Quest q = v.quest();
			buf.writeString(q.id().toString(), 256);
			buf.writeString(q.titleKey(), 256);
			buf.writeString(q.descriptionKey(), 256);
			buf.writeByte(v.state().ordinal());
			buf.writeVarInt(v.progress());
			buf.writeVarInt(v.required());
			buf.writeVarInt(q.rewardPoints());
			buf.writeBoolean(q.special());
			buf.writeBoolean(q.resetOnDeath());
			buf.writeVarInt(q.kills().size());
			PlayerLedger.Progress prog = ledger.progress(q.id()).orElse(null);
			for (var e : q.kills().entrySet()) {
				buf.writeString(e.getKey(), 256);
				buf.writeVarInt(e.getValue());
				buf.writeVarInt(prog == null ? (v.state() == TradeService.QuestState.DONE ? e.getValue() : 0) : Math.min(prog.count(e.getKey()), e.getValue()));
			}
		}
		return buf;
	}

	/** What he says at the top of the screen: a line from his sheet, chosen by the situation. */
	String greeting(LuminaryId id, PlayerLedger ledger, List<TradeService.QuestView> views) {
		long seed = clock.get().getEpochSecond() / 30;
		if (ledger.specialAvailable() && views.stream().anyMatch(v -> v.quest().special() && v.state() == TradeService.QuestState.AVAILABLE)) {
			Optional<String> l = npc.behaviour().line(id, "trade_special", seed);
			if (l.isPresent()) {
				return l.get();
			}
		}
		if (views.stream().anyMatch(v -> v.state() == TradeService.QuestState.CLAIMABLE)) {
			Optional<String> l = npc.behaviour().line(id, "trade_claimable", seed);
			if (l.isPresent()) {
				return l.get();
			}
		}
		return npc.behaviour().line(id, "trade_greeting", seed).orElse("");
	}

	/** The client asked for something; checked here, answered with a fresh snapshot. Registered once at mod init. */
	public static void registerReceiver() {
		ServerPlayNetworking.registerGlobalReceiver(ACTION_PACKET, (server, player, handler, buf, sender) -> {
			int entityId = buf.readVarInt();
			int action = buf.readByte();
			String arg = buf.readString(256);
			server.execute(() -> dev.luminary.fabric.LuminaryRuntime.get().ifPresent(rt -> rt.npc().trade().handle(player, entityId, action, arg)));
		});
	}

	void handle(ServerPlayerEntity player, int entityId, int action, String arg) {
		Entity e = player.getWorld().getEntityById(entityId);
		if (!(e instanceof LuminaryNpcEntity body) || !body.isAlive() || body.isDown() || player.squaredDistanceTo(body) > REACH * REACH || body.npcId().isEmpty()) {
			return;
		}
		LuminaryId id = body.npcId().get();
		Result r;
		String line;
		switch (action) {
			case ACTION_ACCEPT -> {
				r = accept(id, player, LuminaryId.parse(arg));
				line = r.ok() ? npc.behaviour().line(id, "quest_accepted", clock.get().getEpochSecond()).orElse("") : "";
			}
			case ACTION_CLAIM -> {
				r = claim(id, player, LuminaryId.parse(arg));
				boolean special = npc.dialogue().definitions().quest(LuminaryId.parse(arg)).map(Quest::special).orElse(false);
				line = r.ok() ? npc.behaviour().line(id, special ? "sword_given" : "quest_claimed", clock.get().getEpochSecond()).orElse("") : "";
				if (r.ok() && special) {
					npc.behaviour().line(id, "sword_given", clock.get().getEpochSecond()).ifPresent(l -> npc.behaviour().say(body, player, l));
				}
			}
			case ACTION_BUY -> {
				int index;
				try {
					index = Integer.parseInt(arg);
				} catch (NumberFormatException ex) {
					return;
				}
				r = buy(id, player, index);
				line = r.ok() ? npc.behaviour().line(id, "trade_bought", clock.get().getEpochSecond()).orElse("") : "";
			}
			case ACTION_DONATE -> {
				r = donate(id, player);
				if (r.ok()) {
					almsEffects(body);
				}
				line = r.ok() ? npc.behaviour().line(id, "donation", clock.get().getEpochSecond()).orElse(Text.translatable("npc.luminary.trade.donation").getString()) : "";
			}
			case ACTION_GEAR -> {
				player.openHandledScreen(GearViewScreenHandler.factory(body));
				return;
			}
			case ACTION_TALK -> {
				npc.talk(body, player);
				return;
			}
			default -> {
				r = Result.OK;
				line = "";
			}
		}
		if (r.ok() && !line.isEmpty()) {
			npc.behaviour().say(body, player, line);
		}
		open(body, player, r.ok() ? "" : "!" + r.reasonKey());
	}
}
