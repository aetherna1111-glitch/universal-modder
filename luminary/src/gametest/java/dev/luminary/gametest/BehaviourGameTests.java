package dev.luminary.gametest;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.combat.CombatState;
import dev.luminary.core.npc.metrics.Metrics;
import dev.luminary.core.npc.plan.NpcPlan;
import dev.luminary.core.npc.plan.PlanTemplates;
import dev.luminary.core.npc.presence.Activity;
import dev.luminary.core.npc.presence.MutterChannel;
import dev.luminary.core.npc.relationship.Relationship;
import dev.luminary.core.npc.relationship.RelationshipEvent;
import dev.luminary.core.npc.routine.PlaceKind;
import dev.luminary.core.npc.weapon.WeaponCustody;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.config.LuminaryConfig;
import dev.luminary.fabric.npc.BehaviourService;
import dev.luminary.fabric.npc.HolySwordItem;
import dev.luminary.fabric.npc.LuminaryItems;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Slice 15: presence (gaze, whispers), self-defence with knockout, the unique weapon's theft and return on an
 * injected clock, apology and duel through chat, the daily routine and a multi-step plan.
 */
public final class BehaviourGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	private static final int FLOOR_Y = 1;

	private static void expect(boolean c, String m) {
		if (!c) throw new GameTestException(m);
	}

	private static void await(TestContext ctx, BooleanSupplier done, int budget, Runnable then) {
		await(ctx, done, budget, () -> "", then);
	}

	private static void await(TestContext ctx, BooleanSupplier done, int budget, java.util.function.Supplier<String> diagnosis, Runnable then) {
		if (done.getAsBoolean()) { then.run(); return; }
		if (budget <= 0) throw new GameTestException("condition not reached in time: " + diagnosis.get());
		ctx.waitAndRun(5, () -> await(ctx, done, budget - 5, diagnosis, then));
	}

	private static String diagnose(LuminaryNpcEntity body, PlayerEntity player) {
		var seen = body.getWorld().getEntitiesByClass(PlayerEntity.class, body.getBoundingBox().expand(12), e -> true);
		return "players seen=" + seen.size() + " looking=" + dev.luminary.fabric.npc.AttendPlayerGoalAccess.isLookingAt(player, body)
				+ " playerCanSee=" + player.canSee(body) + " npcCanSee=" + body.canSee(player) + " aiDisabled=" + body.isAiDisabled() + " down=" + body.isDown()
				+ " navIdle=" + body.getNavigation().isIdle() + " dist=" + Math.sqrt(player.squaredDistanceTo(body)) + " npcPos=" + body.getPos() + " playerPos=" + player.getPos()
				+ " playerAlive=" + player.isAlive() + " removed=" + player.isRemoved() + " health=" + player.getHealth() + " difficulty=" + body.getWorld().getDifficulty()
				+ " combat=" + npc().behaviour().describe(body.npcId().orElseThrow());
	}

	/**
	 * A mock player that exists in the world: 1.20.1's {@code createMockSurvivalPlayer} only constructs the entity, so
	 * lookups by box or UUID (gaze detection, the combat opponent) would never see it without spawning it.
	 */
	private static PlayerEntity worldPlayer(TestContext ctx, Vec3d at, float yaw, float pitch) {
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		face(player, at, yaw, pitch);
		expect(ctx.getWorld().spawnEntity(player), "mock player must enter the world");
		return player;
	}

	private static void face(PlayerEntity player, Vec3d at, float yaw, float pitch) {
		player.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
		player.setHeadYaw(yaw);
		player.setBodyYaw(yaw);
		player.prevHeadYaw = yaw;
		player.prevPitch = pitch;
		player.prevYaw = yaw;
	}

	private static NpcService npc() {
		return LuminaryRuntime.get().orElseThrow().npc();
	}

	private static LuminaryNpcEntity spawn(TestContext ctx, LuminaryId id, BlockPos at) {
		Result r = npc().spawn(id, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(at)));
		expect(r.ok(), "spawn failed: " + r.reasonKey());
		return npc().body(id).orElseThrow();
	}

	private static void hit(LuminaryNpcEntity body, PlayerEntity player, float amount) {
		body.timeUntilRegen = 0;   // no vanilla invulnerability frames between test blows
		body.damage(body.getDamageSources().playerAttack(player), amount);
	}

	private static void finish(TestContext ctx, LuminaryId id) {
		npc().setClockOffset(Duration.ZERO);
		npc().behaviour().configure(LuminaryConfig.Behaviour.DEFAULTS);
		npc().despawn(id);
		ctx.complete();
	}

	/** Clock offsets and behaviour rules are server-wide, so the tests that touch them run in their own batches (batches run one after another). */
	@GameTest(templateName = ARENA, tickLimit = 900, batchId = "behaviour-gaze")
	public void aGazeEarnsOneAuthoredLineThenCoolsDownAndWhispersReachOnlyTheNear(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/presence");
		BehaviourService b = npc().behaviour();
		LuminaryConfig.Behaviour d = LuminaryConfig.Behaviour.DEFAULTS;
		var gazeOnce = new dev.luminary.core.npc.presence.GazeTracker.Rules(Duration.ofMinutes(10), Duration.ofSeconds(10), Duration.ofMillis(600));   // one line per player for the whole test
		b.configure(new LuminaryConfig.Behaviour("en", gazeOnce, new MutterChannel.Rules(5.0, Duration.ofSeconds(1), Duration.ZERO, true), d.combat(), d.relationship(), d.theftDuration(), d.routineEnabled()));
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(8, FLOOR_Y + 1, 8));
		long gaze0 = b.metrics().count(Metrics.GAZE_REACTIONS);
		long whisper0 = b.metrics().count(Metrics.WHISPERS);
		PlayerEntity far = worldPlayer(ctx, ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 0.5)), 0f, 0f);   // 8 blocks north of the body, looking south (+Z) at it: gaze yes, whisper range no
		await(ctx, () -> b.metrics().count(Metrics.GAZE_REACTIONS) == gaze0 + 1, 120, () -> diagnose(body, far), () -> {
			expect(body.getNavigation().isIdle(), "the body stops for the watcher");
			ctx.waitAndRun(60, () -> {
				expect(b.metrics().count(Metrics.GAZE_REACTIONS) == gaze0 + 1, "a second line inside the per-player cooldown must not fire");
				expect(b.metrics().count(Metrics.WHISPERS) == whisper0, "nobody within 5 blocks: no whisper (" + b.metrics().count(Metrics.WHISPERS) + ")");
				face(far, ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 5.5)), 180f, 0f);   // close by, looking away from the body
				// the body may stroll a few blocks meanwhile: widen the range so the listener stays in it (the 5-block silence was shown above)
				b.configure(new LuminaryConfig.Behaviour("en", gazeOnce, new MutterChannel.Rules(20.0, Duration.ofSeconds(1), Duration.ZERO, true), d.combat(), d.relationship(), d.theftDuration(), d.routineEnabled()));
				NpcRoster.NpcRecord rec = npc().roster().ensureRecord(id);
				await(ctx, () -> b.metrics().count(Metrics.WHISPERS) > whisper0, 500, () -> diagnose(body, far) + " now=" + npc().now() + " silentUntil=" + rec.mutter().silentUntil()
						+ " nextAllowed=" + rec.mutter().nextAllowed() + " lastActivity=" + rec.mutter().lastActivity() + " within5=" + body.getWorld().getEntitiesByClass(PlayerEntity.class, body.getBoundingBox().expand(5), e -> true).size()
						+ " whispers=" + b.metrics().count(Metrics.WHISPERS) + "/" + whisper0, () -> {   // whispers hold for 15 s after the gaze line
					expect(body.activity() == Activity.IDLE || body.activity() == Activity.WALKING, "whispers are derived from the real activity: " + body.activity());
					finish(ctx, id);
				});
			});
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 600, batchId = "behaviour-knockout")
	public void repeatedAttacksRaiseTheShieldThenTheBladeAndZeroHealthMeansKnockoutNotDeath(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/combat");
		ctx.getWorld().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);   // the test server is peaceful: mob attacks would scale to zero
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(8, FLOOR_Y + 1, 8));
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(id);
		PlayerEntity attacker = worldPlayer(ctx, ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)), 180f, 0f);
		UUID a = attacker.getUuid();
		hit(body, attacker, 1f);   // vanilla invulnerability frames: later hits in the same second must be heavier to land
		expect(record.combat().state() == CombatState.DEFENSIVE_WARNING, "first hit: warning, got " + record.combat().state());
		expect(body.getOffHandStack().isOf(Items.SHIELD), "shield first");
		expect(!body.getMainHandStack().isOf(LuminaryItems.LUMIN_BLADE), "no blade while only warning");
		Relationship afterOne = npc().behaviour().relationship(id, a);
		expect(afterOne.threat() > 0 && afterOne.karma() < 0, "an unprovoked hit costs karma and raises threat: " + afterOne);
		hit(body, attacker, 2f);
		expect(record.combat().state() == CombatState.DEFENSIVE_WARNING, "second hit: still warning");
		hit(body, attacker, 3f);
		expect(record.combat().state() == CombatState.COMBAT, "third hit: combat, got " + record.combat().state());
		expect(body.getMainHandStack().isOf(LuminaryItems.LUMIN_BLADE), "the blade is drawn in combat");
		expect(record.combat().opponent().orElse(null).equals(a), "the attacker is the opponent");
		float before = attacker.getHealth();
		// the beam (a quarter) may land before the blow (a half) when he is a step too far; either way half is gone soon
		await(ctx, () -> attacker.getHealth() <= before - attacker.getMaxHealth() * 0.5f + 0.01f, 300, () -> diagnose(body, attacker), () -> {
			expect(body.combatAnchor().isPresent() && body.getPos().distanceTo(body.combatAnchor().get()) < 7, "holds ground near the anchor");
			// zero health: down, not dead
			hit(body, attacker, 1000f);
			expect(body.isAlive() && body.isDown() && body.getPose() == EntityPose.SLEEPING, "knocked out, still the same living entity");
			expect(record.knockout().isPresent(), "knockout recorded");
			expect(Duration.between(npc().now(), record.knockout().get().deadline()).toSeconds() >= 299, "five real minutes, UTC deadline");
			expect(!body.damage(body.getDamageSources().playerAttack(attacker), 5f), "invulnerable while down");
			expect(record.combat().state() == CombatState.RECOVERY && !body.getMainHandStack().isOf(LuminaryItems.LUMIN_BLADE), "the fight ended with the fall");
			expect(npc().body(id).map(e -> e == body).orElse(false), "no second body appears");
			// food shortens the wait; the clock then runs out
			ItemStack bread = new ItemStack(Items.BREAD, 2);
			expect(npc().behaviour().interactDown(body, attacker, bread) == BehaviourService.DownInteraction.HELPED && bread.getCount() == 1, "feeding helps");
			expect(record.knockout().get().timesHelped() == 1, "help counted");
			npc().setClockOffset(Duration.ofMinutes(6));
			await(ctx, () -> !body.isDown(), 100, () -> {
				expect(record.knockout().isEmpty() && body.isAlive() && body.getHealth() > 1f && body.getPose() == EntityPose.STANDING, "recovered on its feet");
				expect(!body.isAiDisabled(), "AI back on");
				finish(ctx, id);
			});
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "behaviour-shield")
	public void theRaisedShieldBreaksUnderHeavyBlowsAndIsWholeAgainAfterFiveMinutes(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/presence");
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(8, FLOOR_Y + 1, 8));
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(id);
		PlayerEntity attacker = worldPlayer(ctx, ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)), 180f, 0f);
		hit(body, attacker, 1f);
		expect(record.combat().state() == CombatState.DEFENSIVE_WARNING && body.getOffHandStack().isOf(Items.SHIELD), "shield up after the first hit");
		hit(body, attacker, 2f);    // vanilla invulnerability frames: each blow must be heavier than the last to land
		expect(Math.abs(record.shieldPoints(20) - 18.0) < 0.01, "the blow on the raised shield took its points: " + record.shieldPoints(20));
		hit(body, attacker, 30f);   // more than the shield has left
		expect(record.shieldBroken(npc().now()) && body.getOffHandStack().isEmpty(), "the shield shattered and is gone");
		hit(body, attacker, 3f);
		expect(record.combat().state() == CombatState.COMBAT && body.getOffHandStack().isEmpty(), "in combat without a shield while it is broken");
		npc().setClockOffset(Duration.ofMinutes(6));
		record.combat().restore(CombatState.CALM, null, npc().now());
		hit(body, attacker, 1f);
		expect(!record.shieldBroken(npc().now()) && body.getOffHandStack().isOf(Items.SHIELD), "five minutes later the shield is whole and raised again");
		finish(ctx, id);
	}

	@GameTest(templateName = ARENA, tickLimit = 600, batchId = "behaviour-weapon")
	public void theStolenBladeReturnsAfterExactlyItsHourAndEveryStaleCopyVanishes(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/weapon");
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(8, FLOOR_Y + 1, 8));
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(id);
		BehaviourService b = npc().behaviour();
		PlayerEntity thief = worldPlayer(ctx, ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)), 180f, 0f);
		PlayerEntity friend = ctx.createMockSurvivalPlayer();
		hit(body, thief, 1000f);
		expect(body.isDown(), "down");
		expect(b.interactDown(body, thief, ItemStack.EMPTY) == BehaviourService.DownInteraction.NOTHING, "nothing to loot from an empty bag");
		thief.setSneaking(true);
		expect(b.interactDown(body, thief, ItemStack.EMPTY) == BehaviourService.DownInteraction.STOLE_WEAPON, "sneak + empty hand takes the blade");
		thief.setSneaking(false);
		expect(b.interactDown(body, thief, ItemStack.EMPTY) == BehaviourService.DownInteraction.NOTHING, "it cannot be taken twice");
		WeaponCustody custody = record.weapon();
		expect(custody.custody() == WeaponCustody.Custody.STOLEN, "custody STOLEN");
		UUID stolenInstance = custody.instanceId();
		expect(custody.state().returnAt().orElseThrow().equals(custody.state().takenAt().orElseThrow().plus(Duration.ofHours(1))), "exactly one hour");
		int slot = -1;
		for (int i = 0; i < thief.getInventory().size(); i++) {
			if (thief.getInventory().getStack(i).isOf(LuminaryItems.LUMIN_BLADE)) slot = i;
		}
		expect(slot >= 0, "the thief holds the blade");
		ItemStack held = thief.getInventory().getStack(slot);
		expect(HolySwordItem.instance(held).orElseThrow().equals(stolenInstance), "the stack carries the live instance id");
		expect(b.relationship(id, thief.getUuid()).karma() <= -29.9, "theft is a heavy event: " + b.relationship(id, thief.getUuid()));
		b.reconcileHeldBlade(held, thief);
		expect(!held.isEmpty(), "the thief may hold it during the hour");
		ItemStack copy = held.copy();
		friend.getInventory().setStack(0, copy);
		b.reconcileHeldBlade(copy, friend);
		expect(copy.isEmpty(), "a copy in anyone else's hands vanishes");
		// a dropped stale copy and an offline-style copy both die with the return
		ItemStack dropped = held.copy();
		ctx.getWorld().spawnEntity(new net.minecraft.entity.ItemEntity(ctx.getWorld(), body.getX(), body.getY() + 1, body.getZ() + 2, dropped));
		npc().setClockOffset(Duration.ofMinutes(59));
		b.tickServer();
		for (int i = 0; i < 45; i++) b.tickWeapon(body);
		expect(record.weapon().custody() == WeaponCustody.Custody.STOLEN, "59 minutes: still stolen");
		long returns0 = b.metrics().count(Metrics.WEAPON_RETURNS);
		npc().setClockOffset(Duration.ofMinutes(61));
		await(ctx, () -> record.weapon().custody() == WeaponCustody.Custody.NPC, 100, () -> {
			expect(!record.weapon().instanceId().equals(stolenInstance), "a new instance was minted");
			expect(b.metrics().count(Metrics.WEAPON_RETURNS) == returns0 + 1, "one return counted");
			// mock players are not connected players, so the purge of online inventories cannot see them; the item's own inventory
			// tick does (the thief also picked up the dropped copy while standing next to it, so every blade stack is reconciled)
			for (int i = 0; i < thief.getInventory().size(); i++) {
				if (thief.getInventory().getStack(i).isOf(LuminaryItems.LUMIN_BLADE)) b.reconcileHeldBlade(thief.getInventory().getStack(i), thief);
			}
			boolean thiefStillHas = false;
			for (int i = 0; i < thief.getInventory().size(); i++) {
				if (thief.getInventory().getStack(i).isOf(LuminaryItems.LUMIN_BLADE)) thiefStillHas = true;
			}
			expect(!thiefStillHas, "the thief's copy is gone");
			expect(ctx.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class, body.getBoundingBox().expand(8), e -> e.getStack().isOf(LuminaryItems.LUMIN_BLADE)).isEmpty(), "the dropped copy is gone");
			ItemStack stale = HolySwordItem.bound(LuminaryItems.LUMIN_BLADE, id, stolenInstance);
			b.reconcileHeldBlade(stale, thief);
			expect(stale.isEmpty(), "a stale copy surfacing later (a chest, an offline inventory) is purged on sight");
			expect(record.weapon().state().thefts() == 1 && record.weapon().state().returns() == 1, "ledger");
			finish(ctx, id);
		});
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "behaviour-apology")
	public void apologyEndsAQuarrelAndADuelCostsNoKarmaWhileAHostilePlayerGetsNoWork(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/apology");
		npc().configureChat(true, 0);
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(8, FLOOR_Y + 1, 8));
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(id);
		PlayerEntity player = worldPlayer(ctx, ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)), 180f, 0f);
		hit(body, player, 1f);
		expect(record.combat().state() == CombatState.DEFENSIVE_WARNING, "warning");
		double threatBefore = npc().behaviour().relationship(id, player.getUuid()).threat();
		NpcService.ChatOutcome sorry = npc().onPlayerChat(player, "Apology, I am sorry, that was a mistake").join();
		expect(sorry.heard() && sorry.intent().flatMap(i -> i.literalReply()).orElse("").equals("APOLOGY_LINE"), "the apology is answered with the authored line: " + sorry);
		expect(record.combat().state() == CombatState.RECOVERY, "forgiven: recovery, got " + record.combat().state());
		expect(npc().behaviour().relationship(id, player.getUuid()).threat() < threatBefore, "threat dropped");
		expect(!body.getOffHandStack().isOf(Items.SHIELD), "shield lowered");
		// a declared duel: hits start a fight without warnings and without karma loss
		NpcService.ChatOutcome duel = npc().onPlayerChat(player, "Apology, let's duel").join();
		expect(duel.intent().flatMap(i -> i.literalReply()).orElse("").equals("DUEL_LINE") && record.combat().duelPending(player.getUuid(), npc().now()), "duel accepted: " + duel);
		double karmaBefore = npc().behaviour().relationship(id, player.getUuid()).karma();
		hit(body, player, 4f);
		expect(record.combat().state() == CombatState.COMBAT && record.combat().inDuelWith(player.getUuid()), "duel hit: straight to combat");
		expect(Math.abs(npc().behaviour().relationship(id, player.getUuid()).karma() - karmaBefore) < 0.01, "a consented duel costs no karma");
		// conversation context remembers the turns
		expect(record.conversation(player.getUuid()).turns(npc().now()).size() >= 2, "multi-turn context kept: " + record.conversation(player.getUuid()).turns(npc().now()).size());
		// a hostile player is refused work
		npc().behaviour().event(id, player.getUuid(), RelationshipEvent.STOLE_WEAPON);
		npc().behaviour().event(id, player.getUuid(), RelationshipEvent.ATTACKED_WHEN_WARNED);
		expect(npc().behaviour().standing(id, player.getUuid()) == Relationship.Standing.HOSTILE, "hostile now");
		ctx.setBlockState(new BlockPos(12, FLOOR_Y + 1, 8), Blocks.CHEST.getDefaultState());
		npc().authorizeContainer(id, ctx.getAbsolutePos(new BlockPos(12, FLOOR_Y + 1, 8)).asLong());
		ctx.setBlockState(new BlockPos(4, FLOOR_Y + 1, 8), Blocks.OAK_LOG.getDefaultState());
		record.combat().restore(CombatState.CALM, null, npc().now());   // so the request is not swallowed by the fight
		NpcService.ChatOutcome work = npc().onPlayerChat(player, "Apology, chop 1 log").join();
		expect(work.assignment().isPresent() && !work.assignment().get().ok() && work.assignment().get().reasonKey().equals("luminary.intent.hostile"), "refused: " + work);
		expect(!record.hasActiveTask(), "no task started for a hostile player");
		finish(ctx, id);
	}

	@GameTest(templateName = ARENA, tickLimit = 900)
	public void theRoutineWalksToTheStudyAndReadsThereUntilInterrupted(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/routine");
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(2, FLOOR_Y + 1, 2));
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(id);
		BlockPos study = ctx.getAbsolutePos(new BlockPos(12, FLOOR_Y + 1, 12));
		record.routine().setPlace(PlaceKind.STUDY, study.asLong());
		ctx.getWorld().setTimeOfDay(1000);   // morning: READING at the STUDY
		await(ctx, () -> body.getPos().distanceTo(Vec3d.ofBottomCenter(study)) <= 2.5 && body.getMainHandStack().isOf(Items.BOOK), 800, () -> {
			expect(body.routineActivityPublic() == Activity.READING, "reading at the study: " + body.routineActivityPublic());
			expect(npc().behaviour().metrics().count(Metrics.ROUTINE_ARRIVALS) >= 1, "arrival counted");
			record.routine().setEnabled(false);
			ctx.waitAndRun(10, () -> {
				expect(body.getMainHandStack().isEmpty(), "the prop is put away when the routine stops");
				finish(ctx, id);
			});
		});
	}

	/** Own batch: its logs would otherwise be inside the scan reach of a neighbouring arena's autonomous NPC, which then competes for them. */
	@GameTest(templateName = ARENA, tickLimit = 1400, batchId = "behaviour-plan")
	public void aPlanRunsItsStepsThroughValidatedTasksAndFinishesOnce(TestContext ctx) {
		LuminaryId id = LuminaryId.parse("luminary-gametest:npc/plan");
		BlockPos chest = new BlockPos(10, FLOOR_Y + 1, 2);
		BlockPos log = new BlockPos(6, FLOOR_Y + 1, 6);
		ctx.setBlockState(chest, Blocks.CHEST.getDefaultState());
		ctx.setBlockState(log, Blocks.OAK_LOG.getDefaultState());
		ctx.setBlockState(log.up(), Blocks.OAK_LOG.getDefaultState());
		LuminaryNpcEntity body = spawn(ctx, id, new BlockPos(2, FLOOR_Y + 1, 2));
		npc().authorizeContainer(id, ctx.getAbsolutePos(chest).asLong());
		long started0 = npc().behaviour().metrics().count(Metrics.PLAN_STEPS_STARTED);
		NpcPlan plan = PlanTemplates.stockWood(ctx.getAbsolutePos(log).asLong(), ctx.getAbsolutePos(chest).asLong(), 2, npc().now(), null);
		Result r = npc().behaviour().startPlan(id, plan);
		expect(r.ok(), "plan start: " + r.reasonKey());
		expect(!npc().behaviour().startPlan(id, PlanTemplates.stockWood(1L, 2L, 1, npc().now(), null)).ok(), "one plan at a time");
		await(ctx, () -> !plan.isActive(), 1300, () -> {
			expect(plan.status() == NpcPlan.Status.COMPLETED, "plan " + plan.status() + " " + plan.failure().orElse(""));
			expect(npc().behaviour().metrics().count(Metrics.PLAN_STEPS_STARTED) == started0 + 1, "the gather step ran once; the deposit step was skipped because nothing was left in hand");
			expect(plan.steps().get(1).status() == dev.luminary.core.npc.plan.PlanStep.Status.SKIPPED, "deposit skipped: " + plan.steps().get(1).status());
			var inv = (net.minecraft.inventory.Inventory) ctx.getWorld().getBlockEntity(ctx.getAbsolutePos(chest));
			int logs = 0;
			for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(Items.OAK_LOG)) logs += inv.getStack(i).getCount();
			expect(logs == 2 && body.heldCount() == 0, "two logs in the chest, hands empty: " + logs + "/" + body.heldCount());
			finish(ctx, id);
		});
	}
}
