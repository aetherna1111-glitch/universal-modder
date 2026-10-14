package dev.luminary.fabric.npc;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.character.CharacterSheet;
import dev.luminary.core.npc.combat.CombatMachine;
import dev.luminary.core.npc.combat.CombatRules;
import dev.luminary.core.npc.combat.CombatState;
import dev.luminary.core.npc.combat.Knockout;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.core.npc.metrics.Metrics;
import dev.luminary.core.npc.plan.NpcPlan;
import dev.luminary.core.npc.plan.PlanRunner;
import dev.luminary.core.npc.presence.Activity;
import dev.luminary.core.npc.presence.GazeTracker;
import dev.luminary.core.npc.presence.MutterChannel;
import dev.luminary.core.npc.relationship.Relationship;
import dev.luminary.core.npc.relationship.RelationshipEvent;
import dev.luminary.core.npc.routine.DailyRoutine;
import dev.luminary.core.npc.routine.RoutineSlot;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskState;
import dev.luminary.core.npc.weapon.WeaponCustody;
import dev.luminary.fabric.Luminary;
import dev.luminary.fabric.config.LuminaryConfig;
import dev.luminary.fabric.state.NpcWorldState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Presence, relationships, self-defence, knockout, the unique weapon, the daily routine and plans: everything the NPC
 * does that is not a validated task or a conversation. Every rule lives in the pure core ({@code core.npc.*});
 * this class only observes the world, feeds the machines and performs their reactions on the body. Server thread.
 */
public final class BehaviourService {
	private final NpcService npc;
	private final MinecraftServer server;
	private final NpcWorldState state;
	private final NpcDialogueService dialogue;
	private final Supplier<Instant> clock;
	private final Metrics metrics = new Metrics();
	private final PlanRunner planRunner = new PlanRunner();
	private final HolySwordItem.Sanctuaries sanctuaries = new HolySwordItem.Sanctuaries();
	private LuminaryConfig.Behaviour config = LuminaryConfig.Behaviour.DEFAULTS;

	BehaviourService(NpcService npc, MinecraftServer server, NpcWorldState state, NpcDialogueService dialogue, Supplier<Instant> clock) {
		this.npc = npc;
		this.server = server;
		this.state = state;
		this.dialogue = dialogue;
		this.clock = clock;
	}

	public void configure(LuminaryConfig.Behaviour behaviour) {
		this.config = behaviour;
		state.roster().setRules(behaviour.rosterRules());
		HolySwordItem.setBlessings(behaviour.blessings());
	}

	public LuminaryConfig.Behaviour config() {
		return config;
	}

	public Metrics metrics() {
		return metrics;
	}

	public HolySwordItem.Sanctuaries sanctuaries() {
		return sanctuaries;
	}

	private CombatRules combatRules() {
		return state.roster().rules().combat();
	}

	private Optional<NpcRoster.NpcRecord> record(LuminaryNpcEntity body) {
		if (body.npcId().isEmpty() || !state.roster().isBody(body.npcId().get(), body.getUuid())) {
			return Optional.empty();
		}
		return Optional.of(state.roster().ensureRecord(body.npcId().get()));
	}

	// ------------------------------------------------------------------ the NPC's own items

	private String nameKeyOf(LuminaryId id) {
		return dialogue.definitions().npc(id).map(NpcDefinition::nameKey).orElse("");
	}

	/** The sword bound to {@code id}'s instance, carrying the owner's name for the tooltip. */
	public ItemStack blade(LuminaryId id, UUID instance) {
		return HolySwordItem.withOwnerName(HolySwordItem.bound(LuminaryItems.LUMIN_BLADE, id, instance), nameKeyOf(id));
	}

	/** A vestment piece bound to {@code id}, carrying the owner's name for the tooltip. */
	public ItemStack vestment(LuminArmorItem piece, LuminaryId id) {
		return HolySwordItem.withOwnerName(LuminArmorItem.bound(piece, id), nameKeyOf(id));
	}

	// ------------------------------------------------------------------ speaking (authored lines only)

	/** An authored line of the character sheet in the configured language; empty when the sheet has none. */
	public Optional<String> line(LuminaryId id, String key, long seed) {
		return dialogue.definitions().npc(id).map(NpcDefinition::character).flatMap(c -> c.line(key, config.language(), seed));
	}

	static Text named(LuminaryNpcEntity body, Text line) {
		Text name = body.getCustomName() != null ? body.getCustomName() : Text.literal(body.npcId().map(Object::toString).orElse("npc"));
		return Text.empty().append(Text.literal("<").append(name).append("> ").formatted(Formatting.GOLD)).append(line);
	}

	void say(LuminaryNpcEntity body, PlayerEntity player, String text) {
		player.sendMessage(named(body, Text.literal(text)), false);
	}

	/** Spoken to everyone within {@code range} blocks, as a quiet aside. */
	void sayNearby(LuminaryNpcEntity body, String text, double range, boolean whisper) {
		Text line = whisper ? Text.literal(text).formatted(Formatting.GRAY, Formatting.ITALIC) : Text.literal(text);
		for (PlayerEntity p : playersWithin(body, range)) {
			p.sendMessage(named(body, line), false);
		}
	}

	/** Players (connected or GameTest mocks) within {@code range} blocks of the body. */
	static List<PlayerEntity> playersWithin(LuminaryNpcEntity body, double range) {
		List<PlayerEntity> out = new ArrayList<>();
		for (PlayerEntity p : body.getWorld().getEntitiesByClass(PlayerEntity.class, body.getBoundingBox().expand(range), e -> !e.isSpectator())) {
			if (p.squaredDistanceTo(body) <= range * range) {
				out.add(p);
			}
		}
		return out;
	}

	void remember(LuminaryId id, MemoryKind kind, UUID player, String topic, String text) {
		Instant now = clock.get();
		dialogue.memory(id).add(MemoryEntry.of(kind, player, topic, text, now, Provenance.OBSERVED_IN_WORLD), now);
		state.markDirty();
	}

	// ------------------------------------------------------------------ gaze

	/** Called every tick a player is looking at the body (from {@link AttendPlayerGoal}). */
	public void onGazing(LuminaryNpcEntity body, PlayerEntity player) {
		record(body).ifPresent(record -> {
			Instant now = clock.get();
			Optional<GazeTracker.Reaction> reaction = record.gaze().onGazing(player.getUuid(), now);
			if (reaction.isEmpty()) {
				return;
			}
			LuminaryId id = body.npcId().get();
			Relationship.Standing standing = record.relationships().standing(player.getUuid(), now, state.roster().rules().relationship());
			String key = switch (standing) {
				case FRIEND -> CharacterSheet.LINES_GAZE_FRIEND;
				case WARY, HOSTILE -> CharacterSheet.LINES_GAZE_HOSTILE;
				default -> CharacterSheet.LINES_GAZE;
			};
			Optional<String> text = line(id, key, reaction.get().sequence()).or(() -> line(id, CharacterSheet.LINES_GAZE, reaction.get().sequence()));
			if (text.isPresent()) {
				say(body, player, text.get());
				record.mutter().holdFor(Duration.ofSeconds(15), now);
				metrics.inc(Metrics.GAZE_REACTIONS);
			}
		});
	}

	public void onLookedAway(LuminaryNpcEntity body, PlayerEntity player) {
		record(body).ifPresent(r -> r.gaze().onLookedAway(player.getUuid()));
	}

	/** A conversation happened: no gaze line right after, whispers pause. */
	void onSpoke(LuminaryNpcEntity body, UUID player) {
		record(body).ifPresent(r -> {
			r.gaze().onSpoke(player, clock.get());
			r.mutter().holdFor(Duration.ofSeconds(30), clock.get());
		});
	}

	// ------------------------------------------------------------------ whispers

	/** The NPC's own vestments: equipped when missing (every second), their worn effects ticked (mobs do not tick armour). */
	public void tickVestments(LuminaryNpcEntity body) {
		if (body.npcId().isEmpty() || !(body.getWorld() instanceof ServerWorld world)) {
			return;
		}
		LuminaryId id = body.npcId().get();
		if (dialogue.definitions().npc(id).map(d -> !d.vestments()).orElse(true)) {
			return;
		}
		tickHelmetAnimation(body, id);
		for (LuminArmorItem piece : LuminaryItems.VESTMENTS) {
			net.minecraft.entity.EquipmentSlot slot = piece.getSlotType();
			if (slot == net.minecraft.entity.EquipmentSlot.HEAD && (body.helmetOff() || body.helmetAnimation() != 0)) {
				continue;   // asked bare-headed, or mid-animation: the diadem is not re-equipped
			}
			ItemStack worn = body.getEquippedStack(slot);
			if (body.age % 20 == 0 && !(worn.getItem() == piece && LuminArmorItem.owner(worn).filter(id::equals).isPresent())) {
				body.equipStack(slot, vestment(piece, id));
				body.setEquipmentDropChance(slot, 0.0f);
				worn = body.getEquippedStack(slot);
			}
			if (worn.getItem() == piece) {
				piece.wornTick(worn, world, body);
			}
		}
	}

	public static final int HELMET_ANIMATION_TICKS = 30;

	/**
	 * "Take off your helmet": the arm comes up, the diadem is in the hand for a moment, then it is put away; "put it
	 * on" plays the same in reverse. The gaze and the tool hand are left alone (a tool in hand postpones nothing: the
	 * helmet rides the off hand's place for the animation's length).
	 */
	public boolean helmet(LuminaryNpcEntity body, boolean off) {
		if (body.npcId().isEmpty() || body.helmetAnimation() != 0 || body.helmetOff() == off || body.isDown()) {
			return false;
		}
		body.setHelmetAnimation(off ? HELMET_ANIMATION_TICKS : -HELMET_ANIMATION_TICKS);
		body.swingHand(Hand.MAIN_HAND);
		return true;
	}

	private void tickHelmetAnimation(LuminaryNpcEntity body, LuminaryId id) {
		int t = body.helmetAnimation();
		if (t == 0) {
			return;
		}
		boolean off = t > 0;
		int left = Math.abs(t);
		net.minecraft.entity.EquipmentSlot head = net.minecraft.entity.EquipmentSlot.HEAD;
		if (left == HELMET_ANIMATION_TICKS - 8) {
			// the hand reaches the head: the diadem leaves (or arrives at) the head and shows in the hand
			if (off) {
				body.equipStack(head, ItemStack.EMPTY);
			}
			ItemStack shown = vestment(LuminaryItems.VESTMENT_HELMET, id);
			if (body.getMainHandStack().isEmpty()) {
				body.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, shown);
			} else {
				body.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, shown);
			}
			body.swingHand(Hand.MAIN_HAND);
		}
		if (left == 1) {
			// the hand comes down: the diadem is put away (or back on)
			for (net.minecraft.entity.EquipmentSlot hand : new net.minecraft.entity.EquipmentSlot[] {net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.entity.EquipmentSlot.OFFHAND}) {
				if (body.getEquippedStack(hand).isOf(LuminaryItems.VESTMENT_HELMET)) {
					body.equipStack(hand, ItemStack.EMPTY);
				}
			}
			if (!off) {
				body.equipStack(head, vestment(LuminaryItems.VESTMENT_HELMET, id));
			}
			body.setHelmetOff(off);
			body.setHelmetAnimation(0);
			return;
		}
		body.setHelmetAnimation(off ? left - 1 : -(left - 1));
	}

	static boolean isHelmetOff(String t) {
		return (t.contains("шлем") || t.contains("helmet") || t.contains("венец") || t.contains("diadem"))
				&& (t.contains("сними") || t.contains("снять") || t.contains("без ") || t.contains("take off") || t.contains("remove") || t.contains(" off"));
	}

	static boolean isHelmetOn(String t) {
		return (t.contains("шлем") || t.contains("helmet") || t.contains("венец") || t.contains("diadem"))
				&& (t.contains("надень") || t.contains("надеть") || t.contains("put on") || t.contains("wear") || t.contains(" on"));
	}

	/** Every second: derive what the NPC is doing and maybe whisper it to whoever stands close. */
	public void tickPresence(LuminaryNpcEntity body) {
		if (body.age % 20 != 0) {
			return;
		}
		record(body).ifPresent(record -> {
			Activity activity = activityOf(body, record);
			body.setActivity(activity);
			double range = record.mutter().rules().range();
			boolean near = !playersWithin(body, range).isEmpty();
			Optional<MutterChannel.Mutter> m = record.mutter().tick(activity, near, clock.get());
			if (m.isPresent()) {
				LuminaryId id = body.npcId().get();
				Optional<String> text = line(id, m.get().lineKey(), m.get().sequence()).or(() -> line(id, Activity.IDLE.mutterKey(), m.get().sequence()));
				if (text.isPresent()) {
					sayNearby(body, text.get(), range, true);
					metrics.inc(Metrics.WHISPERS);
				}
			}
		});
	}

	Activity activityOf(LuminaryNpcEntity body, NpcRoster.NpcRecord record) {
		if (record.knockout().isPresent()) {
			return Activity.DOWN;
		}
		switch (record.combat().state()) {
			case COMBAT -> {
				return Activity.FIGHTING;
			}
			case DEFENSIVE_WARNING, GUARDED_DEESCALATION -> {
				return Activity.GUARDED;
			}
			case RECOVERY -> {
				return Activity.RECOVERING;
			}
			default -> {
			}
		}
		if (record.hasActiveTask()) {
			NpcTask t = record.task().orElseThrow();
			return switch (t.state()) {
				case BREAKING, COLLECTING -> Activity.GATHERING;
				case SMELTING, LOADING -> Activity.SMELTING;
				case WITHDRAWING -> Activity.FETCHING;
				case DEPOSITING -> Activity.CARRYING;
				default -> body.heldCount() > 0 ? Activity.CARRYING : Activity.WALKING;
			};
		}
		Activity routine = body.routineActivity();
		if (routine != null) {
			return routine;
		}
		return body.getNavigation().isIdle() ? Activity.IDLE : Activity.WALKING;
	}

	// ------------------------------------------------------------------ chat hooks

	/**
	 * Words addressed to the NPC that the relationship and combat machines care about (apology, duel). Returns the
	 * line to answer with when the words were handled here and need no planner or model.
	 */
	public Optional<String> onAddressed(LuminaryNpcEntity body, PlayerEntity player, String text) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty()) {
			return Optional.empty();
		}
		NpcRoster.NpcRecord record = rec.get();
		LuminaryId id = body.npcId().get();
		Instant now = clock.get();
		record.relationships().apply(player.getUuid(), RelationshipEvent.CONVERSATION, now, state.roster().rules().relationship());
		onSpoke(body, player.getUuid());
		String t = text.toLowerCase(Locale.ROOT);
		if (isHelmetOff(t) || isHelmetOn(t)) {
			boolean off = isHelmetOff(t) && !t.contains("надень") && !t.contains("put on");
			if (dialogue.definitions().npc(id).map(d -> !d.vestments()).orElse(true)) {
				return Optional.empty();   // no vestments: the dialogue answers
			}
			boolean started = helmet(body, off);
			String key = !started ? (body.helmetOff() == off ? "npc.luminary.helmet.already" : "npc.luminary.helmet.busy") : off ? "npc.luminary.helmet.off" : "npc.luminary.helmet.on";
			return Optional.of(Text.translatable(key).getString().isEmpty() ? "..." : net.minecraft.util.Language.getInstance().get(key));
		}
		if (isApology(t) && record.combat().opponent().filter(o -> o.equals(player.getUuid())).isPresent() && record.combat().state() != CombatState.CALM) {
			CombatMachine.Transition tr = record.combat().onApology(player.getUuid(), now);
			if (tr.reaction() != CombatMachine.Reaction.NONE) {
				record.relationships().apply(player.getUuid(), RelationshipEvent.APOLOGIZED, now, state.roster().rules().relationship());
				syncEquipment(body, record);
				remember(id, MemoryKind.RELATIONSHIP, player.getUuid(), "apology", player.getGameProfile().getName() + " apologized after attacking me");
				return Optional.of(line(id, CharacterSheet.LINES_APOLOGY_ACCEPTED, now.getEpochSecond()).orElse("..."));
			}
		}
		if (isDuelProposal(t) && record.knockout().isEmpty()) {
			Relationship.Standing standing = record.relationships().standing(player.getUuid(), now, state.roster().rules().relationship());
			if (standing == Relationship.Standing.HOSTILE) {
				return Optional.empty();   // no sparring with someone it fears; the dialogue answers
			}
			record.combat().acceptDuel(player.getUuid(), now);
			remember(id, MemoryKind.RECENT_INTERACTION, player.getUuid(), "duel", player.getGameProfile().getName() + " asked for a sparring duel; I accepted");
			state.markDirty();
			return Optional.of(line(id, CharacterSheet.LINES_DUEL_ACCEPTED, now.getEpochSecond()).orElse("..."));
		}
		state.markDirty();
		return Optional.empty();
	}

	static boolean isApology(String t) {
		return t.contains("sorry") || t.contains("apolog") || t.contains("forgive") || t.contains("извини") || t.contains("прости") || t.contains("виноват") || t.contains("прошу прощения");
	}

	static boolean isDuelProposal(String t) {
		return t.contains("duel") || t.contains("spar") || t.contains("дуэл") || t.contains("спарринг") || t.contains("сразимся") || t.contains("потренируемся в бою");
	}

	/** The NPC's attitude towards a player, for prompts and refusals. */
	public Relationship.Standing standing(LuminaryId id, UUID player) {
		return state.roster().ensureRecord(id).relationships().standing(player, clock.get(), state.roster().rules().relationship());
	}

	public Relationship relationship(LuminaryId id, UUID player) {
		return state.roster().ensureRecord(id).relationships().get(player, clock.get(), state.roster().rules().relationship());
	}

	public void event(LuminaryId id, UUID player, RelationshipEvent event) {
		state.roster().ensureRecord(id).relationships().apply(player, event, clock.get(), state.roster().rules().relationship());
		state.markDirty();
	}

	// ------------------------------------------------------------------ combat

	/** The body took a hit. Returns false when the hit must be ignored (down, or not a real attack). */
	public void onAttacked(LuminaryNpcEntity body, DamageSource source, float amount) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty() || !(source.getAttacker() instanceof PlayerEntity attacker)) {
			return;
		}
		NpcRoster.NpcRecord record = rec.get();
		LuminaryId id = body.npcId().get();
		Instant now = clock.get();
		metrics.inc(Metrics.ATTACKS_RECEIVED);
		boolean duel = record.combat().duelPending(attacker.getUuid(), now) || record.combat().inDuelWith(attacker.getUuid());
		boolean warned = record.combat().state() == CombatState.DEFENSIVE_WARNING || record.combat().state() == CombatState.GUARDED_DEESCALATION;
		RelationshipEvent event = duel ? RelationshipEvent.DUEL_HIT : warned ? RelationshipEvent.ATTACKED_WHEN_WARNED : RelationshipEvent.ATTACKED;
		record.relationships().apply(attacker.getUuid(), event, now, state.roster().rules().relationship());
		// a raised shield takes the blow's points; at zero it shatters and is gone for the repair time
		if (record.combat().shieldUp() && body.getOffHandStack().isOf(Items.SHIELD) && !record.shieldBroken(now)
				&& record.damageShield(amount, combatRules().shieldPoints(), now, combatRules().shieldRepair())) {
			body.clearActiveItem();
			body.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
			((ServerWorld) body.getWorld()).playSound(null, body.getBlockPos(), net.minecraft.sound.SoundEvents.ITEM_SHIELD_BREAK, net.minecraft.sound.SoundCategory.NEUTRAL, 1.0f, 0.9f);
			remember(id, MemoryKind.SIGNIFICANT_EVENT, attacker.getUuid(), "shield", attacker.getGameProfile().getName() + " broke my shield");
			Luminary.LOGGER.info("[luminary] {}'s shield broke; whole again at {}", id, record.shieldBrokenUntil().orElse(null));
		}
		CombatMachine.Transition tr = record.combat().onAttacked(attacker.getUuid(), now);
		String name = attacker.getGameProfile().getName();
		switch (tr.reaction()) {
			case RAISE_SHIELD, WARN -> {
				if (tr.changed() || tr.reaction() == CombatMachine.Reaction.WARN) {
					line(id, CharacterSheet.LINES_WARNING, record.combat().recentHits()).ifPresent(l -> say(body, attacker, l));
				}
				if (tr.changed()) {
					remember(id, MemoryKind.SIGNIFICANT_EVENT, attacker.getUuid(), "attack", name + " struck me" + (duel ? " in a duel" : " without cause") + "; I raised my shield and warned them");
				}
			}
			case FIGHT -> {
				body.setCombatAnchor(body.getPos());
				metrics.inc(Metrics.COMBATS);
				line(id, CharacterSheet.LINES_FIGHT, now.getEpochSecond()).ifPresent(l -> say(body, attacker, l));
				remember(id, MemoryKind.SIGNIFICANT_EVENT, attacker.getUuid(), "fight", duel ? "Sparred with " + name + " at their request" : name + " kept attacking after my warning; I defended myself");
				Luminary.LOGGER.info("[luminary] {} enters COMBAT against {} ({})", id, name, duel ? "duel" : "self-defence");
			}
			default -> {
			}
		}
		syncEquipment(body, record);
		state.markDirty();
	}

	/** Per tick while not CALM: distance to the opponent drives de-escalation and recovery. */
	public void tickCombat(LuminaryNpcEntity body) {
		record(body).ifPresent(record -> {
			CombatMachine machine = record.combat();
			if (machine.state() == CombatState.CALM) {
				return;
			}
			Optional<Double> distance = opponentEntity(body, record).map(e -> Math.sqrt(e.squaredDistanceTo(body)));
			CombatMachine.Transition tr = machine.tick(distance, clock.get());
			if (tr.changed()) {
				LuminaryId body_id = body.npcId().get();
				if (tr.reaction() == CombatMachine.Reaction.STAND_DOWN) {
					line(body_id, CharacterSheet.LINES_DEESCALATE, clock.get().getEpochSecond()).ifPresent(l -> sayNearby(body, l, 12, false));
				}
				if (tr.to() == CombatState.CALM) {
					body.setCombatAnchor(null);
					metrics.inc(Metrics.RECOVERIES);
				}
				syncEquipment(body, record);
				state.markDirty();
			}
		});
	}

	/** The opponent as a living entity in the body's world, if present and alive. */
	Optional<LivingEntity> opponentEntity(LuminaryNpcEntity body, NpcRoster.NpcRecord record) {
		Optional<UUID> op = record.combat().opponent();
		if (op.isEmpty() || !(body.getWorld() instanceof ServerWorld world)) {
			return Optional.empty();
		}
		Entity e = world.getPlayerByUuid(op.get());
		if (e == null) {
			e = world.getEntity(op.get());
		}
		return e instanceof LivingEntity living && living.isAlive() && !(living instanceof PlayerEntity p && (p.isSpectator() || p.isCreative())) ? Optional.of(living) : Optional.empty();
	}

	public Optional<LivingEntity> combatTarget(LuminaryNpcEntity body) {
		return record(body).filter(r -> r.combat().state() != CombatState.CALM && r.knockout().isEmpty()).flatMap(r -> opponentEntity(body, r));
	}

	public boolean shouldStrike(LuminaryNpcEntity body, LivingEntity target) {
		return record(body).map(r -> r.combat().shouldStrike(Math.sqrt(target.squaredDistanceTo(body)))).orElse(false);
	}

	public boolean inCombat(LuminaryNpcEntity body) {
		return record(body).map(r -> r.combat().state() == CombatState.COMBAT).orElse(false);
	}

	/** One blow: a fixed fraction of the target's maximum health with the unique weapon, a fist otherwise. */
	public void strike(LuminaryNpcEntity body, LivingEntity target) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty()) {
			return;
		}
		boolean armed = rec.get().weapon().custody() == WeaponCustody.Custody.NPC;
		boolean harp = body.getMainHandStack().isOf(LuminaryItems.ANGELIC_HARP);
		float damage = armed ? (float) (target.getMaxHealth() * combatRules().weaponDamageFraction()) : harp ? (float) AngelicHarpItem.BASE_DAMAGE : 2.0f;
		body.swingHand(Hand.MAIN_HAND);
		if (target.damage(body.getDamageSources().mobAttack(body), damage)) {
			Vec3d push = target.getPos().subtract(body.getPos()).normalize();
			target.takeKnockback(0.5, -push.x, -push.z);
			metrics.inc(Metrics.STRIKES);
		}
	}

	/** Lumin judges from a distance: the sword's beam at an opponent beyond reach, for half the melee blow. Only while armed. */
	public boolean judge(LuminaryNpcEntity body, LivingEntity target) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty() || !(body.getWorld() instanceof ServerWorld world)) {
			return false;
		}
		if (body.getMainHandStack().isOf(LuminaryItems.ANGELIC_HARP) && body.squaredDistanceTo(target) <= AngelicHarpItem.CHORD_RADIUS * AngelicHarpItem.CHORD_RADIUS) {
			body.swingHand(Hand.MAIN_HAND);   // the harp's chord instead of the blade's beam
			boolean played = AngelicHarpItem.chord(body.getMainHandStack(), world, body, (float) (target.getMaxHealth() * combatRules().weaponDamageFraction() * 0.5));
			if (played) {
				metrics.inc(Metrics.STRIKES);
			}
			return played;
		}
		if (rec.get().weapon().custody() != WeaponCustody.Custody.NPC || !body.getMainHandStack().isOf(LuminaryItems.LUMIN_BLADE)) {
			return false;
		}
		Vec3d from = body.getEyePos();
		Vec3d dir = target.getPos().add(0, target.getHeight() * 0.5, 0).subtract(from);
		body.swingHand(Hand.MAIN_HAND);
		boolean fired = HolySwordItem.verdict(body.getMainHandStack(), world, body, from, dir, (float) (target.getMaxHealth() * combatRules().weaponDamageFraction() * 0.5));
		if (fired) {
			metrics.inc(Metrics.STRIKES);
		}
		return fired;
	}

	/** Shield in the off hand while guarded; the blade in the main hand only while fighting and still in custody. */
	void syncEquipment(LuminaryNpcEntity body, NpcRoster.NpcRecord record) {
		boolean down = record.knockout().isPresent();
		boolean shield = !down && record.combat().shieldUp() && combatRules().shieldFirst() && !record.shieldBroken(clock.get());
		ItemStack off = body.getOffHandStack();
		if (shield && !off.isOf(Items.SHIELD)) {
			body.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
			body.setCurrentHand(Hand.OFF_HAND);
		} else if (!shield && off.isOf(Items.SHIELD)) {
			body.clearActiveItem();
			body.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		} else if (shield && !body.isUsingItem()) {
			body.setCurrentHand(Hand.OFF_HAND);
		}
		boolean fighting = !down && record.combat().state() == CombatState.COMBAT;
		boolean blade = fighting && record.weapon().custody() == WeaponCustody.Custody.NPC;
		boolean harp = fighting && record.weapon().custody() == WeaponCustody.Custody.TRANSFERRED;   // the blade given away: he plays the harp
		ItemStack main = body.getMainHandStack();
		if (blade && !main.isOf(LuminaryItems.LUMIN_BLADE)) {
			GearManager.stowTool(body);
			body.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, blade(body.npcId().get(), record.weapon().instanceId()));
		} else if (harp && !main.isOf(LuminaryItems.ANGELIC_HARP)) {
			GearManager.stowTool(body);
			body.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, harp(body.npcId().get()));
		} else if (!blade && main.isOf(LuminaryItems.LUMIN_BLADE) || !harp && main.isOf(LuminaryItems.ANGELIC_HARP)) {
			body.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
	}

	/** The NPC's own harp, named after him. */
	public ItemStack harp(LuminaryId id) {
		return AngelicHarpItem.bound(LuminaryItems.ANGELIC_HARP, id, nameKeyOf(id));
	}

	// ------------------------------------------------------------------ knockout

	/** Health would reach zero: the body goes down instead of dying. */
	public void knockOut(LuminaryNpcEntity body, DamageSource source) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		LuminaryId id = body.npcId().orElse(null);
		UUID by = source.getAttacker() instanceof PlayerEntity p ? p.getUuid() : null;
		Instant now = clock.get();
		body.setDown(true);
		if (rec.isEmpty() || id == null) {
			return;
		}
		NpcRoster.NpcRecord record = rec.get();
		record.setKnockout(Knockout.start(now, combatRules().knockout(), by));
		record.combat().onKnockedOut(now);
		body.setCombatAnchor(null);
		syncEquipment(body, record);
		metrics.inc(Metrics.KNOCKOUTS);
		String who = by != null && source.getAttacker() instanceof PlayerEntity p ? p.getGameProfile().getName() : "something";
		remember(id, MemoryKind.SIGNIFICANT_EVENT, by, "knockout", who + " brought me down; I lay helpless for a while");
		line(id, CharacterSheet.LINES_KNOCKED_OUT, now.getEpochSecond()).ifPresent(l -> sayNearby(body, l, 12, false));
		Luminary.LOGGER.info("[luminary] {} knocked out by {} until {}", id, who, record.knockout().get().deadline());
		state.markDirty();
	}

	/** Called from the body's tick (AI is off while down): gets up when the real-time deadline passed. */
	public void tickKnockout(LuminaryNpcEntity body) {
		if (body.age % 20 != 0) {
			return;
		}
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty()) {
			if (body.isDown()) {
				body.setDown(false);   // a body with no record cannot stay down forever
			}
			return;
		}
		NpcRoster.NpcRecord record = rec.get();
		Optional<Knockout> k = record.knockout();
		if (k.isEmpty()) {
			if (body.isDown()) {
				body.setDown(false);
			}
			return;
		}
		if (!body.isDown()) {
			body.setDown(true);   // loaded body, persisted knockout
		}
		if (k.get().over(clock.get())) {
			recover(body, record);
		}
	}

	void recover(LuminaryNpcEntity body, NpcRoster.NpcRecord record) {
		Instant now = clock.get();
		record.setKnockout(null);
		body.setDown(false);
		body.setHealth(Math.max(1f, body.getMaxHealth() / 2f));
		record.combat().onRecovered(now);
		syncEquipment(body, record);
		metrics.inc(Metrics.RECOVERIES);
		LuminaryId id = body.npcId().get();
		remember(id, MemoryKind.SIGNIFICANT_EVENT, null, "recovered", "I got back on my feet");
		line(id, CharacterSheet.LINES_RECOVERED, now.getEpochSecond()).ifPresent(l -> sayNearby(body, l, 12, false));
		state.markDirty();
	}

	/** Admin: end the knockout now. */
	public Result revive(LuminaryId id) {
		Optional<LuminaryNpcEntity> body = npc.body(id);
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		if (record.knockout().isEmpty()) {
			return Result.fail("luminary.npc.not_down");
		}
		if (body.isEmpty()) {
			record.setKnockout(null);
			state.markDirty();
			return Result.OK;
		}
		recover(body.get(), record);
		return Result.OK;
	}

	/** What a right-click on a downed body does: help with food, steal the blade while sneaking, loot otherwise. */
	public DownInteraction interactDown(LuminaryNpcEntity body, PlayerEntity player, ItemStack inHand) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty()) {
			return DownInteraction.NOTHING;
		}
		NpcRoster.NpcRecord record = rec.get();
		LuminaryId id = body.npcId().get();
		Knockout k = record.knockout().orElse(null);
		if (k == null) {
			return DownInteraction.NOTHING;
		}
		Instant now = clock.get();
		String name = player.getGameProfile().getName();
		if (inHand.isFood()) {
			record.setKnockout(k.helped(now, combatRules().helpShortensBy()));
			if (!player.isCreative()) {
				inHand.decrement(1);
			}
			record.relationships().apply(player.getUuid(), RelationshipEvent.HELPED_WHILE_DOWN, now, state.roster().rules().relationship());
			remember(id, MemoryKind.RELATIONSHIP, player.getUuid(), "helped", name + " fed me while I was down");
			line(id, CharacterSheet.LINES_HELPED, now.getEpochSecond()).ifPresent(l -> say(body, player, l));
			if (record.knockout().get().over(now)) {
				recover(body, record);
			}
			state.markDirty();
			return DownInteraction.HELPED;
		}
		if (inHand.isEmpty() && player.isSneaking()) {
			if (record.weapon().custody() != WeaponCustody.Custody.NPC) {
				return DownInteraction.NOTHING;
			}
			Optional<WeaponCustody.State> taken = record.weapon().onTaken(player.getUuid(), now, config.theftDuration());
			if (taken.isEmpty()) {
				return DownInteraction.NOTHING;
			}
			ItemStack blade = blade(id, taken.get().instanceId());
			if (!player.giveItemStack(blade)) {
				player.dropItem(blade, false);
			}
			record.relationships().apply(player.getUuid(), RelationshipEvent.STOLE_WEAPON, now, state.roster().rules().relationship());
			remember(id, MemoryKind.SIGNIFICANT_EVENT, player.getUuid(), "weapon/theft", name + " took my blade while I lay helpless; it will find its way back to me");
			line(id, CharacterSheet.LINES_WEAPON_TAKEN, now.getEpochSecond()).ifPresent(l -> say(body, player, l));
			metrics.inc(Metrics.WEAPON_THEFTS);
			Luminary.LOGGER.info("[luminary] {}'s blade stolen by {}; returns at {}", id, name, taken.get().returnAt().orElse(null));
			state.markDirty();
			return DownInteraction.STOLE_WEAPON;
		}
		if (inHand.isEmpty()) {
			for (int i = 0; i < body.heldItems().size(); i++) {
				ItemStack s = body.heldItems().getStack(i);
				if (!s.isEmpty()) {
					ItemStack out = s.copy();
					body.heldItems().setStack(i, ItemStack.EMPTY);
					if (!player.giveItemStack(out)) {
						player.dropItem(out, false);
					}
					record.setKnockout(k.looted());
					record.relationships().apply(player.getUuid(), RelationshipEvent.LOOTED_WHILE_DOWN, now, state.roster().rules().relationship());
					remember(id, MemoryKind.SIGNIFICANT_EVENT, player.getUuid(), "looted", name + " took my " + out.getName().getString() + " while I was down");
					state.markDirty();
					return DownInteraction.LOOTED;
				}
			}
		}
		return DownInteraction.NOTHING;
	}

	public enum DownInteraction { NOTHING, HELPED, LOOTED, STOLE_WEAPON }

	// ------------------------------------------------------------------ unique weapon

	/** Every second per body: the stolen blade comes home when its hour is up. */
	public void tickWeapon(LuminaryNpcEntity body) {
		if (body.age % 20 != 7) {
			return;
		}
		record(body).ifPresent(record -> {
			if (record.hasWeaponRecord() && record.weapon().dueForReturn(clock.get())) {
				returnWeapon(body.npcId().get(), record, "its hour was up");
			}
		});
	}

	/** Server tick (bodies may be unloaded): due returns are honoured even without a body. */
	public void tickServer() {
		sanctuaries.tick(server.getTicks());
		if (server.getTicks() % 40 != 13) {
			return;
		}
		Instant now = clock.get();
		state.roster().all().forEach((id, record) -> {
			if (record.hasWeaponRecord() && record.weapon().dueForReturn(now)) {
				returnWeapon(id, record, "its hour was up");
			}
			if (record.hasWeaponRecord() && record.weapon().custody() == WeaponCustody.Custody.TRANSFERRED) {
				if (server.getTicks() % 400 == 13) {
					recallDroppedBlade(id, record);
				}
				deliverToOwner(id, record);
			}
		});
	}

	/** The tag a dropped transferred blade carries: the server tick it was first seen on the ground. */
	static final String DROPPED_TAG = "luminary_dropped:";
	static final int DROPPED_HOUR_TICKS = 20 * 60 * 60;

	/**
	 * A transferred blade on the ground never despawns and cannot burn or blow up; after its hour there it is lifted
	 * back to its owner. Scanned every 20 s, well inside vanilla's 5-minute despawn.
	 */
	void recallDroppedBlade(LuminaryId id, NpcRoster.NpcRecord record) {
		for (ServerWorld w : server.getWorlds()) {
			List<ItemEntity> gone = new ArrayList<>();
			for (Entity e : w.iterateEntities()) {
				if (!(e instanceof ItemEntity item) || !isCopy(item.getStack(), id, record.weapon().instanceId())) {
					continue;
				}
				long since = item.getCommandTags().stream().filter(t -> t.startsWith(DROPPED_TAG)).mapToLong(t -> Long.parseLong(t.substring(DROPPED_TAG.length()))).findFirst().orElse(-1L);
				if (since < 0) {
					item.setNeverDespawn();
					item.setInvulnerable(true);
					item.addCommandTag(DROPPED_TAG + server.getTicks());
				} else if (server.getTicks() - since >= DROPPED_HOUR_TICKS) {
					gone.add(item);
				}
			}
			for (ItemEntity item : gone) {
				item.discard();
				if (record.weapon().markReturnToOwner(clock.get())) {
					state.markDirty();
				}
			}
		}
	}

	void returnWeapon(LuminaryId id, NpcRoster.NpcRecord record, String why) {
		Instant now = clock.get();
		UUID thief = record.weapon().state().holder().orElse(null);
		Optional<UUID> retired = record.weapon().returned(UUID.randomUUID(), now);
		if (retired.isEmpty()) {
			return;
		}
		int purged = purgeCopies(id, retired.get(), thief);
		metrics.inc(Metrics.WEAPON_RETURNS);
		metrics.add(Metrics.WEAPON_PURGED_COPIES, purged);
		String thiefName = thief == null ? "someone" : npc.nameOf(id, thief);
		remember(id, MemoryKind.SIGNIFICANT_EVENT, thief, "weapon/return", "My blade returned to me (" + why + "); " + thiefName + " had it");
		npc.body(id).ifPresent(b -> {
			line(id, CharacterSheet.LINES_WEAPON_RETURNED, now.getEpochSecond()).ifPresent(l -> sayNearby(b, l, 12, false));
			syncEquipment(b, record);
		});
		Luminary.LOGGER.info("[luminary] {}'s blade returned ({}); {} stale copies removed from loaded inventories", id, why, purged);
		state.markDirty();
	}

	/** Removes every reachable copy of a retired instance: online inventories and item entities in loaded chunks. */
	int purgeCopies(LuminaryId id, UUID retired, UUID thief) {
		int purged = 0;
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			boolean had = false;
			for (int i = 0; i < p.getInventory().size(); i++) {
				ItemStack s = p.getInventory().getStack(i);
				if (isCopy(s, id, retired)) {
					p.getInventory().setStack(i, ItemStack.EMPTY);
					purged++;
					had = true;
				}
			}
			if (had) {
				p.sendMessage(Text.translatable("npc.luminary.weapon.vanished").formatted(Formatting.LIGHT_PURPLE, Formatting.ITALIC), false);
				p.getInventory().markDirty();
			}
		}
		for (ServerWorld w : server.getWorlds()) {
			List<ItemEntity> gone = new ArrayList<>();
			for (Entity e : w.iterateEntities()) {
				if (e instanceof ItemEntity item && isCopy(item.getStack(), id, retired)) {
					gone.add(item);
				}
			}
			for (ItemEntity item : gone) {
				item.discard();
				purged++;
			}
		}
		return purged;
	}

	static boolean isCopy(ItemStack s, LuminaryId id, UUID instance) {
		return s.isOf(LuminaryItems.LUMIN_BLADE) && HolySwordItem.owner(s).filter(id::equals).isPresent()
				&& HolySwordItem.instance(s).filter(instance::equals).isPresent();
	}

	/** From the item's inventory tick: a copy a player holds is valid only for the thief inside the window or the permanent owner. */
	public void reconcileHeldBlade(ItemStack stack, PlayerEntity player) {
		Optional<LuminaryId> owner = HolySwordItem.owner(stack);
		Optional<UUID> instance = HolySwordItem.instance(stack);
		if (owner.isEmpty() || instance.isEmpty()) {
			return;   // a free blade (creative tab, /give): nobody's, so nothing to reconcile
		}
		Optional<NpcRoster.NpcRecord> record = state.roster().get(owner.get());
		if (record.isEmpty() || !record.get().hasWeaponRecord()) {
			stack.setCount(0);
			return;
		}
		WeaponCustody custody = record.get().weapon();
		if (!custody.mayBeHeldBy(instance.get(), player.getUuid())) {
			stack.setCount(0);
			metrics.inc(Metrics.WEAPON_PURGED_COPIES);
			player.sendMessage(Text.translatable("npc.luminary.weapon.vanished").formatted(Formatting.LIGHT_PURPLE, Formatting.ITALIC), false);
			player.getInventory().markDirty();
			if (custody.custody() == WeaponCustody.Custody.TRANSFERRED && custody.isCurrent(instance.get()) && custody.markReturnToOwner(clock.get())) {
				state.markDirty();   // a transferred blade in a stranger's hands: owed back to its owner, delivered by tickServer
				deliverToOwner(owner.get(), record.get());
			}
		}
	}

	/** A transferred blade owed to its owner comes back to the owner's inventory as soon as the owner is on the server. */
	public boolean deliverToOwner(LuminaryId id, NpcRoster.NpcRecord record) {
		WeaponCustody custody = record.weapon();
		if (!custody.returnToOwnerDue() || custody.state().holder().isEmpty()) {
			return false;
		}
		ServerPlayerEntity owner = server.getPlayerManager().getPlayer(custody.state().holder().get());
		return owner != null && deliverToOwner(id, record, owner);
	}

	/** Same, to a given owner (tests hand in a mock player the player manager does not list). */
	public boolean deliverToOwner(LuminaryId id, NpcRoster.NpcRecord record, PlayerEntity owner) {
		WeaponCustody custody = record.weapon();
		if (!custody.returnToOwnerDue() || custody.state().holder().filter(owner.getUuid()::equals).isEmpty()) {
			return false;
		}
		ItemStack blade = HolySwordItem.transferTo(blade(id, custody.instanceId()), owner.getUuid(), owner.getGameProfile().getName());
		if (!owner.giveItemStack(blade)) {
			owner.dropItem(blade, false);
		}
		custody.ownerReceived();
		owner.sendMessage(Text.translatable("npc.luminary.weapon.came_back").formatted(Formatting.GOLD, Formatting.ITALIC), false);
		metrics.inc(Metrics.WEAPON_RETURNS);
		state.markDirty();
		return true;
	}

	/** A player hands the blade back before the hour is up. */
	public boolean offerBladeBack(LuminaryNpcEntity body, PlayerEntity player, ItemStack inHand) {
		Optional<NpcRoster.NpcRecord> rec = record(body);
		if (rec.isEmpty() || !inHand.isOf(LuminaryItems.LUMIN_BLADE)) {
			return false;
		}
		LuminaryId id = body.npcId().get();
		WeaponCustody custody = rec.get().weapon();
		if (HolySwordItem.owner(inHand).filter(id::equals).isEmpty() || custody.custody() != WeaponCustody.Custody.STOLEN) {
			return false;
		}
		inHand.setCount(0);
		rec.get().relationships().apply(player.getUuid(), RelationshipEvent.RETURNED_WEAPON, clock.get(), state.roster().rules().relationship());
		returnWeapon(id, rec.get(), player.getGameProfile().getName() + " gave it back");
		return true;
	}

	/**
	 * The single integration point for a permanent transfer (a quest the game has not written yet, or an admin).
	 * {@code reason} is recorded verbatim. The player receives the live instance; the NPC fights unarmed from now on.
	 */
	public Result transferWeapon(LuminaryId id, PlayerEntity player, String reason) {
		if (dialogue.definitions().npc(id).isEmpty()) {
			return Result.fail(NpcService.REASON_UNKNOWN_NPC);
		}
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		if (record.weapon().custody() == WeaponCustody.Custody.TRANSFERRED) {
			return Result.fail("luminary.weapon.already_transferred");
		}
		if (record.weapon().custody() == WeaponCustody.Custody.STOLEN) {
			returnWeapon(id, record, "it was about to be given away properly");
		}
		WeaponCustody.State s = record.weapon().transferPermanently(player.getUuid(), reason, clock.get());
		// the same blade, every attribute and mark kept; only the owner id on it changes from the NPC's to the player's
		ItemStack blade = HolySwordItem.transferTo(blade(id, s.instanceId()), player.getUuid(), player.getGameProfile().getName());
		if (!player.giveItemStack(blade)) {
			player.dropItem(blade, false);
		}
		remember(id, MemoryKind.SIGNIFICANT_EVENT, player.getUuid(), "weapon/transfer", "I gave my blade to " + player.getGameProfile().getName() + " for good (" + reason + ")");
		npc.body(id).ifPresent(b -> syncEquipment(b, record));
		state.markDirty();
		return Result.OK;
	}

	/** Admin: take the weapon back from whoever has it; copies become stale. */
	public Result reclaimWeapon(LuminaryId id) {
		if (dialogue.definitions().npc(id).isEmpty()) {
			return Result.fail(NpcService.REASON_UNKNOWN_NPC);
		}
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		UUID holder = record.weapon().state().holder().orElse(null);
		if (record.weapon().custody() == WeaponCustody.Custody.STOLEN) {
			returnWeapon(id, record, "an administrator recalled it");
			return Result.OK;
		}
		UUID retired = record.weapon().reclaim(UUID.randomUUID());
		purgeCopies(id, retired, holder);
		npc.body(id).ifPresent(b -> syncEquipment(b, record));
		state.markDirty();
		return Result.OK;
	}

	// ------------------------------------------------------------------ reset

	/**
	 * Operator reset: the body is removed and spawned again where it stood (or at {@code fallback}), the knockout,
	 * the fight, the helmet state, the plan and the task are cleared, a stolen or stale sword is reclaimed, the
	 * vestments come back fresh. Memory, relationships and places are kept.
	 */
	public Result reset(LuminaryId id, net.minecraft.server.world.ServerWorld fallbackWorld, Vec3d fallback) {
		if (dialogue.definitions().npc(id).isEmpty()) {
			return Result.fail(NpcService.REASON_UNKNOWN_NPC);
		}
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		Optional<LuminaryNpcEntity> body = npc.body(id);
		net.minecraft.server.world.ServerWorld world = body.map(b -> (net.minecraft.server.world.ServerWorld) b.getWorld()).orElse(fallbackWorld);
		Vec3d pos = body.map(Entity::getPos).orElse(fallback);
		if (world == null || pos == null) {
			return Result.fail(NpcService.REASON_NOT_PRESENT);
		}
		if (record.hasActiveTask()) {
			npc.cancelTask(id);
		}
		record.plan().ifPresent(NpcPlan::cancel);
		record.setKnockout(null);
		record.combat().restore(CombatState.CALM, null, clock.get());
		if (record.weapon().custody() != WeaponCustody.Custody.TRANSFERRED) {
			reclaimWeapon(id);
		}
		body.ifPresent(b -> npc.despawn(id));
		Result spawned = npc.spawn(id, world, pos);
		npc.body(id).ifPresent(b -> {
			b.setHelmetOff(false);
			b.setHelmetAnimation(0);
			syncEquipment(b, record);
		});
		remember(id, MemoryKind.SIGNIFICANT_EVENT, null, "reset", "An administrator set me right again");
		state.markDirty();
		return spawned;
	}

	// ------------------------------------------------------------------ routine

	/** The slot the NPC should be living right now and where, if the routine is on and the place is known. */
	public Optional<RoutineGoal.Target> routineTarget(LuminaryNpcEntity body) {
		if (!config.routineEnabled()) {
			return Optional.empty();
		}
		return record(body).flatMap(record -> {
			if (!record.routine().enabled() || record.knockout().isPresent() || record.combat().state() != CombatState.CALM) {
				return Optional.empty();
			}
			Optional<RoutineSlot> slot = DailyRoutine.DEFAULT.at(body.getWorld().getTimeOfDay());
			if (slot.isEmpty()) {
				return Optional.empty();
			}
			return record.routine().place(slot.get().place()).map(pos -> new RoutineGoal.Target(slot.get(), BlockPos.fromLong(pos)));
		});
	}

	public void onRoutineArrived(LuminaryNpcEntity body, RoutineSlot slot) {
		metrics.inc(Metrics.ROUTINE_ARRIVALS);
	}

	// ------------------------------------------------------------------ plans

	public Result startPlan(LuminaryId id, NpcPlan plan) {
		if (dialogue.definitions().npc(id).isEmpty()) {
			return Result.fail(NpcService.REASON_UNKNOWN_NPC);
		}
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		if (!record.startPlan(plan)) {
			return Result.fail("luminary.plan.active");
		}
		state.markDirty();
		return Result.OK;
	}

	public Result cancelPlan(LuminaryId id) {
		NpcRoster.NpcRecord record = state.roster().ensureRecord(id);
		if (!record.hasActivePlan()) {
			return Result.fail("luminary.plan.none_active");
		}
		record.plan().get().cancel();
		if (record.hasActiveTask()) {
			npc.cancelTask(id);
		}
		state.markDirty();
		return Result.OK;
	}

	/** Every half second: advance the plan through the validated task path. */
	public void tickPlan(LuminaryNpcEntity body) {
		if (body.age % 10 != 3) {
			return;
		}
		record(body).ifPresent(record -> {
			if (!record.hasActivePlan() || record.knockout().isPresent() || record.combat().state() == CombatState.COMBAT) {
				return;
			}
			LuminaryId id = body.npcId().get();
			NpcPlan plan = record.plan().get();
			PlanRunner.Tick t = planRunner.tick(plan, new PlanWorld(id, body, record));
			switch (t.outcome()) {
				case STARTED -> metrics.inc(Metrics.PLAN_STEPS_STARTED);
				case PLAN_FAILED -> {
					metrics.inc(Metrics.PLAN_FAILURES);
					remember(id, MemoryKind.SIGNIFICANT_EVENT, plan.requestedBy().orElse(null), "plan/" + plan.id(), "Gave up on the plan '" + plan.goal() + "': " + t.detail().orElse("?"));
					tellRequester(plan, Text.translatable("npc.luminary.plan.failed", plan.goal(), t.detail().orElse("?")), body);
				}
				case PLAN_COMPLETED -> {
					remember(id, MemoryKind.SIGNIFICANT_EVENT, plan.requestedBy().orElse(null), "plan/" + plan.id(), "Finished the plan '" + plan.goal() + "'");
					tellRequester(plan, Text.translatable("npc.luminary.plan.done", plan.goal()), body);
				}
				default -> {
				}
			}
			if (t.outcome() != PlanRunner.Outcome.IDLE && t.outcome() != PlanRunner.Outcome.WAITING) {
				state.markDirty();
			}
		});
	}

	private void tellRequester(NpcPlan plan, Text line, LuminaryNpcEntity body) {
		plan.requestedBy().map(u -> server.getPlayerManager().getPlayer(u)).ifPresent(p -> p.sendMessage(named(body, line), false));
	}

	/** The plan runner's view of the world, built per tick from authoritative state. */
	private final class PlanWorld implements PlanRunner.World {
		private final LuminaryId id;
		private final LuminaryNpcEntity body;
		private final NpcRoster.NpcRecord record;

		PlanWorld(LuminaryId id, LuminaryNpcEntity body, NpcRoster.NpcRecord record) {
			this.id = id;
			this.body = body;
			this.record = record;
		}

		@Override
		public boolean hasActiveTask() {
			return record.hasActiveTask();
		}

		@Override
		public Optional<NpcTask> task(UUID taskId) {
			return record.task().filter(t -> taskId != null && t.id().equals(taskId));
		}

		@Override
		public Optional<NpcTask> currentTask() {
			return record.task();
		}

		@Override
		public int containerCount(long containerPos, String itemId) {
			if (!(body.getWorld().getBlockEntity(BlockPos.fromLong(containerPos)) instanceof Inventory inv)) {
				return 0;
			}
			int n = 0;
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (!s.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(itemId)) {
					n += s.getCount();
				}
			}
			return n;
		}

		@Override
		public int cargoCount() {
			return body.heldCount();
		}

		@Override
		public Result assign(NpcAction action, UUID requestedBy) {
			return npc.assign(id, action, requestedBy);
		}
	}

	// ------------------------------------------------------------------ status

	public String describe(LuminaryId id) {
		NpcRoster.NpcRecord r = state.roster().ensureRecord(id);
		Instant now = clock.get();
		StringBuilder sb = new StringBuilder();
		sb.append("combat=").append(r.combat().state());
		r.combat().opponent().ifPresent(o -> sb.append(" vs ").append(npc.nameOf(id, o)));
		if (r.shieldBroken(now)) {
			sb.append("; shield broken, back in ").append(r.shieldBrokenUntil().map(t -> Duration.between(now, t).toSeconds()).orElse(0L)).append("s");
		} else {
			sb.append("; shield ").append((int) r.shieldPoints(combatRules().shieldPoints())).append("/").append((int) combatRules().shieldPoints());
		}
		r.knockout().ifPresent(k -> sb.append("; down, up in ").append(k.remaining(now).toSeconds()).append("s (helped ").append(k.timesHelped()).append(", looted ").append(k.timesLooted()).append(")"));
		WeaponCustody.State w = r.weapon().state();
		sb.append("; weapon=").append(w.custody());
		w.holder().ifPresent(h -> sb.append(" held by ").append(npc.nameOf(id, h)));
		w.returnAt().ifPresent(t -> sb.append(", returns in ").append(Math.max(0, Duration.between(now, t).toSeconds())).append("s"));
		sb.append(" (thefts ").append(w.thefts()).append(", returns ").append(w.returns()).append(")");
		sb.append("; routine=").append(r.routine().enabled() ? "on" : "off").append(" places=").append(r.routine().places().keySet());
		r.plan().ifPresent(p -> sb.append("; plan '").append(p.goal()).append("' ").append(p.status()).append(" ").append(p.steps().stream().map(s -> s.id() + ":" + s.status()).toList()));
		return sb.toString();
	}

	public Supplier<Instant> clock() {
		return clock;
	}
}
