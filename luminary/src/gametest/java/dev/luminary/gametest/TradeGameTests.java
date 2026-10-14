package dev.luminary.gametest;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.core.npc.combat.CombatState;
import dev.luminary.core.npc.trade.PlayerLedger;
import dev.luminary.core.npc.trade.TradeService;
import dev.luminary.core.npc.weapon.WeaponCustody;
import dev.luminary.fabric.LuminaryRuntime;
import dev.luminary.fabric.npc.HolySwordItem;
import dev.luminary.fabric.npc.LuminaryItems;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import dev.luminary.fabric.npc.TradeManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Lumin's economy: kills advance accepted tasks only, points buy from his shop, five tasks and no purchase open the trial, and the trial ends with his blade in the player's name. */
public final class TradeGameTests implements FabricGameTest {
	private static final String ARENA = "luminary-gametest:arena";
	private static final int FLOOR_Y = 1;
	private static final LuminaryId ID = LuminaryId.parse("luminary-gametest:npc/trade");

	private static void expect(boolean c, String m) {
		if (!c) throw new GameTestException(m);
	}

	private static NpcService npc() {
		return LuminaryRuntime.get().orElseThrow().npc();
	}

	private static LuminaryId quest(String name) {
		return LuminaryId.parse("luminary-gametest:quest/" + name);
	}

	private static void kill(TestContext ctx, PlayerEntity player, BlockPos at) {
		ZombieEntity z = ctx.spawnEntity(EntityType.ZOMBIE, at);
		z.timeUntilRegen = 0;
		expect(z.damage(player.getDamageSources().playerAttack(player), 1000f), "the blow lands");
		expect(z.isDead() || z.getHealth() <= 0, "the zombie dies");
	}

	private static int count(PlayerEntity p, net.minecraft.item.Item item) {
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (s.isOf(item)) n += s.getCount();
		}
		return n;
	}

	private static ItemStack find(PlayerEntity p, net.minecraft.item.Item item) {
		for (int i = 0; i < p.getInventory().size(); i++) {
			ItemStack s = p.getInventory().getStack(i);
			if (s.isOf(item)) return s;
		}
		return ItemStack.EMPTY;
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "trade-economy")
	public void tasksPayPointsTheShopTakesThemAndTheTrialHandsOverTheBlade(TestContext ctx) {
		expect(npc().spawn(ID, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(8, FLOOR_Y + 1, 8)))).ok(), "spawn");
		TradeManager trade = npc().trade();
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		expect(trade.quests(ID).size() == 6 && trade.quests(ID).get(5).special(), "five tasks and the trial last: " + trade.quests(ID));
		expect(trade.offers(ID).size() == 2, "an offer naming a mod that is not installed is skipped: " + trade.offers(ID));
		PlayerLedger ledger = trade.ledger(ID, player);
		// a kill before accepting counts for nothing
		kill(ctx, player, new BlockPos(4, FLOOR_Y + 1, 4));
		expect(ledger.points() == 0 && ledger.active().isEmpty(), "nothing accepted, nothing counted");
		expect(trade.service().state(trade.quests(ID).get(5), ledger) == TradeService.QuestState.LOCKED, "the trial is locked at first");
		for (int i = 1; i <= 5; i++) {
			Result r = trade.accept(ID, player, quest("zombie_" + i));
			expect(r.ok(), "accept " + i + ": " + r.reasonKey());
		}
		expect(!trade.claim(ID, player, quest("zombie_1")).ok(), "not done yet");
		kill(ctx, player, new BlockPos(4, FLOOR_Y + 1, 12));   // one zombie advances all five
		for (int i = 1; i <= 5; i++) {
			expect(trade.service().state(trade.quests(ID).get(i - 1), ledger) == TradeService.QuestState.CLAIMABLE, "task " + i + " claimable");
			Result r = trade.claim(ID, player, quest("zombie_" + i));
			expect(r.ok(), "claim " + i + ": " + r.reasonKey());
		}
		expect(ledger.points() == 20 && ledger.completed().size() == 5, "five tasks: 20 points, got " + ledger.points());
		expect(ledger.specialAvailable(), "five tasks and nothing bought: the trial opens");
		expect(trade.service().state(trade.quests(ID).get(5), ledger) == TradeService.QuestState.AVAILABLE, "the trial is offered");
		// the trial: two zombies, death resets
		expect(trade.accept(ID, player, quest("trial")).ok(), "accept the trial");
		kill(ctx, player, new BlockPos(12, FLOOR_Y + 1, 4));
		expect(ledger.progress(quest("trial")).orElseThrow().total() == 1, "one of two");
		trade.onDeath(player);
		expect(ledger.progress(quest("trial")).orElseThrow().total() == 0, "death wiped the count");
		kill(ctx, player, new BlockPos(12, FLOOR_Y + 1, 12));
		kill(ctx, player, new BlockPos(12, FLOOR_Y + 1, 10));
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(ID);
		expect(record.weapon().custody() == WeaponCustody.Custody.NPC, "the blade is his before the claim");
		Result claimed = trade.claim(ID, player, quest("trial"));
		expect(claimed.ok(), "claim the trial: " + claimed.reasonKey());
		expect(record.weapon().custody() == WeaponCustody.Custody.TRANSFERRED, "the blade changed hands for good");
		ItemStack blade = find(player, LuminaryItems.LUMIN_BLADE);
		expect(!blade.isEmpty(), "the player holds the blade");
		expect(HolySwordItem.ownerPlayer(blade).filter(player.getUuid()::equals).isPresent(), "the owner id on it is the player's");
		expect(HolySwordItem.owner(blade).filter(ID::equals).isPresent() && HolySwordItem.isBound(blade), "still bound through the NPC's custody record");
		expect(HolySwordItem.instance(blade).filter(record.weapon().instanceId()::equals).isPresent(), "the same custody instance");
		expect(HolySwordItem.ownerLine(blade).map(t -> t.getString().contains(player.getGameProfile().getName())).orElse(false), "the tooltip names the player: " + HolySwordItem.ownerLine(blade));
		double dmg = blade.getAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_ATTACK_DAMAGE).stream().mapToDouble(m -> m.getValue()).sum() + 1.0;
		expect(Math.abs(dmg - HolySwordItem.BASE_DAMAGE) < 0.01, "every attribute kept: " + dmg);
		expect(blade.getEnchantments().size() >= 3 && blade.getNbt().contains(HolySwordItem.NBT_RUNE), "runes and blessings kept");
		npc().behaviour().reconcileHeldBlade(blade, player);
		expect(!blade.isEmpty(), "in the owner's hands it stays");
		// a stranger picks it up: it turns to light and comes back to the owner
		PlayerEntity stranger = ctx.createMockSurvivalPlayer();
		expect(ctx.getWorld().spawnEntity(stranger), "stranger in the world");
		ItemStack taken = blade.copy();
		blade.setCount(0);
		stranger.giveItemStack(taken);
		npc().behaviour().reconcileHeldBlade(taken, stranger);
		expect(taken.isEmpty(), "a stranger's copy vanishes");
		expect(record.weapon().returnToOwnerDue(), "and it is owed back to the owner");
		expect(find(player, LuminaryItems.LUMIN_BLADE).isEmpty(), "the owner (not in the player list) has not got it yet");
		expect(npc().behaviour().deliverToOwner(ID, record, player), "delivered to the owner");
		ItemStack back = find(player, LuminaryItems.LUMIN_BLADE);
		expect(!back.isEmpty() && HolySwordItem.ownerPlayer(back).filter(player.getUuid()::equals).isPresent() && !record.weapon().returnToOwnerDue(), "the owner holds it again, in their name");
		expect(ledger.specialClaimed() && !ledger.specialAvailable(), "the trial is claimed once");
		// the shop: points buy, the unique seal only once, a mod item that is missing never shows
		Result buy = trade.buy(ID, player, 0);
		expect(buy.ok() && ledger.points() == 10 && count(player, LuminaryItems.LIGHTSTONE_DUST) == 8, "8 dust for 10 points: " + buy.reasonKey() + " points=" + ledger.points());
		expect(trade.buy(ID, player, 1).reasonKey().equals(TradeService.REASON_NOT_ENOUGH_POINTS), "15 for the seal, 10 left");
		trade.service().grant(ledger, 20);
		expect(trade.buy(ID, player, 1).ok() && count(player, LuminaryItems.ENVOY_SEAL) == 1, "the seal");
		expect(trade.buy(ID, player, 1).reasonKey().equals(TradeService.REASON_UNIQUE_OWNED), "one of a kind");
		expect(trade.buy(ID, player, 5).reasonKey().equals(TradeService.REASON_UNKNOWN_OFFER), "no such offer");
		expect(ledger.purchases() == 2, "two purchases on the ledger");
		// the testing commands: reset wipes everything and reclaims the blade; complete-by-decree does it all again
		expect(trade.reset(ID, player), "reset changes something");
		PlayerLedger fresh = trade.ledger(ID, player);
		expect(fresh.points() == 0 && fresh.completed().isEmpty() && fresh.purchases() == 0, "a clean ledger");
		expect(record.weapon().custody() == WeaponCustody.Custody.NPC, "the blade is his again after the reset");
		expect(trade.completeAll(ID, player, false) == 5 && fresh.points() == 20 && fresh.specialAvailable(), "five tasks by decree, the trial opens");
		expect(trade.completeAll(ID, player, true) == 1 && record.weapon().custody() == WeaponCustody.Custody.TRANSFERRED, "the trial by decree hands over the blade");
		expect(trade.completeAll(ID, player, true) == 0, "nothing left to decree");
		npc().behaviour().reclaimWeapon(ID);
		npc().despawn(ID);
		ctx.complete();
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "trade-harp")
	public void withTheBladeGivenAwayHeFightsWithTheHarp(TestContext ctx) {
		ctx.getWorld().getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
		expect(npc().spawn(ID, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(8, FLOOR_Y + 1, 8)))).ok(), "spawn");
		LuminaryNpcEntity body = npc().body(ID).orElseThrow();
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		player.refreshPositionAndAngles(ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)).x, FLOOR_Y + 1 + ctx.getAbsolutePos(BlockPos.ORIGIN).getY(), ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)).z, 180f, 0f);
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		Result t = npc().behaviour().transferWeapon(ID, player, "test");
		expect(t.ok(), "transfer: " + t.reasonKey());
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(ID);
		float[] blows = {1f, 2f, 3f, 4f, 5f};
		for (float b : blows) {
			body.timeUntilRegen = 0;
			body.damage(body.getDamageSources().playerAttack(player), b);
			if (record.combat().state() == CombatState.COMBAT) break;
		}
		expect(record.combat().state() == CombatState.COMBAT, "in combat: " + record.combat().state());
		expect(body.getMainHandStack().isOf(LuminaryItems.ANGELIC_HARP), "the harp in hand, not the blade: " + body.getMainHandStack());
		expect(HolySwordItem.ownerLine(body.getMainHandStack()).isPresent(), "his harp carries his name");
		expect(!body.getMainHandStack().isDamageable() || body.getMainHandStack().getNbt().getBoolean("Unbreakable"), "unbreakable");
		ZombieEntity z = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(10, FLOOR_Y + 1, 8));
		float hp = z.getHealth();
		boolean played = dev.luminary.fabric.npc.AngelicHarpItem.chord(body.getMainHandStack(), ctx.getWorld(), body, 6f);
		expect(played && z.getHealth() < hp, "the chord hurts the zombie: played=" + played + " hp " + hp + " -> " + z.getHealth());
		double harpDmg = body.getMainHandStack().getAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND).get(EntityAttributes.GENERIC_ATTACK_DAMAGE).stream().mapToDouble(m -> m.getValue()).sum() + 1.0;
		expect(Math.abs(harpDmg - HolySwordItem.BASE_DAMAGE) < 0.01 && body.getMainHandStack().getEnchantments().size() >= 3, "the harp is the sword's equal: " + harpDmg);
		expect(z.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.GLOWING), "and lights it up");
		npc().behaviour().reclaimWeapon(ID);
		npc().behaviour().revive(ID);
		npc().despawn(ID);
		ctx.complete();
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "trade-karma")
	public void karmaChangesLuckAndTradersPricesAndTheUnworthyAreRefusedTheTrial(TestContext ctx) {
		expect(npc().spawn(ID, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(8, FLOOR_Y + 1, 8)))).ok(), "spawn");
		TradeManager trade = npc().trade();
		var b = npc().behaviour();
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		expect(trade.completeAll(ID, player, false) == 5, "five tasks by decree");
		expect(Math.abs(b.karmaOf(player.getUuid()) - 15.0) < 0.01, "each finished task is +3 karma: " + b.karmaOf(player.getUuid()));
		expect(b.worthy(ID, player.getUuid()), "worthy with good karma");
		b.applyKarmaLuck(player);
		expect(Math.abs(player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_LUCK) - 0.5) < 0.01, "+15 karma is half a point of luck: " + player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_LUCK));
		// four unprovoked blows: karma -32 from +15 → -17 (below the traders' -10 line), threat high: wary/hostile and in debt
		NpcRoster.NpcRecord record = npc().roster().ensureRecord(ID);
		for (int i = 0; i < 4; i++) {
			record.relationships().apply(player.getUuid(), dev.luminary.core.npc.relationship.RelationshipEvent.ATTACKED, npc().now(), npc().roster().rules().relationship());
		}
		double karma = b.karmaOf(player.getUuid());
		expect(karma < 0, "karma below zero after the blows: " + karma);
		expect(!b.worthy(ID, player.getUuid()), "no longer worthy");
		Result r = trade.accept(ID, player, quest("trial"));
		expect(!r.ok() && r.reasonKey().equals(TradeManager.REASON_UNWORTHY), "the trial is refused to the unworthy: " + r.reasonKey());
		b.applyKarmaLuck(player);
		expect(player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_LUCK) < 0, "bad karma is bad luck: " + player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_LUCK));
		// a villager asks more of this player
		net.minecraft.entity.passive.VillagerEntity villager = ctx.spawnEntity(EntityType.VILLAGER, new BlockPos(4, FLOOR_Y + 1, 4));
		villager.setVillagerData(villager.getVillagerData().withProfession(net.minecraft.village.VillagerProfession.LIBRARIAN).withLevel(1));
		expect(!villager.getOffers().isEmpty(), "the librarian has offers");
		villager.interact(player, net.minecraft.util.Hand.MAIN_HAND);
		net.minecraft.village.TradeOffer first = villager.getOffers().get(0);
		int extra = dev.luminary.core.npc.relationship.KarmaEffects.extraPrice(first.getOriginalFirstBuyItem().getCount(), dev.luminary.core.npc.relationship.KarmaEffects.markup(karma));
		expect(extra > 0 && first.getSpecialPrice() >= extra, "the price went up by at least " + extra + ": special=" + first.getSpecialPrice());
		// alms: ten emeralds buy five karma, none without them
		Result poor = trade.donate(ID, player);
		expect(!poor.ok() && poor.reasonKey().equals(TradeManager.REASON_NOT_ENOUGH_EMERALDS), "no emeralds, no alms");
		player.giveItemStack(new ItemStack(net.minecraft.item.Items.EMERALD, 12));
		double before = b.karmaOf(player.getUuid());
		expect(trade.donate(ID, player).ok(), "alms accepted");
		TradeManager.almsEffects(npc().body(ID).orElseThrow());   // the bell and the light: must not throw on a server world
		expect(Math.abs(b.karmaOf(player.getUuid()) - before - 5) < 0.01 && count(player, net.minecraft.item.Items.EMERALD) == 2, "+5 karma for 10 emeralds: " + b.karmaOf(player.getUuid()) + ", emeralds left " + count(player, net.minecraft.item.Items.EMERALD));
		// good karma: the same librarian asks less
		record.relationships().setKarma(player.getUuid(), 100, npc().now(), npc().roster().rules().relationship());
		net.minecraft.entity.passive.VillagerEntity kind = ctx.spawnEntity(EntityType.VILLAGER, new BlockPos(12, FLOOR_Y + 1, 12));
		kind.setVillagerData(kind.getVillagerData().withProfession(net.minecraft.village.VillagerProfession.LIBRARIAN).withLevel(1));
		kind.interact(player, net.minecraft.util.Hand.MAIN_HAND);
		net.minecraft.village.TradeOffer cheap = kind.getOffers().get(0);
		expect(cheap.getSpecialPrice() < 0 && cheap.getAdjustedFirstBuyItem().getCount() < cheap.getOriginalFirstBuyItem().getCount(), "karma +100 is a discount: special=" + cheap.getSpecialPrice());
		record.relationships().setKarma(player.getUuid(), karma, npc().now(), npc().roster().rules().relationship());
		// the ledger reset does not forgive: karma is the relationship, not the trade
		trade.reset(ID, player);
		expect(b.karmaOf(player.getUuid()) < 0, "a wiped ledger keeps the karma");
		npc().despawn(ID);
		ctx.complete();
	}

	@GameTest(templateName = ARENA, tickLimit = 400, batchId = "trade-chat")
	public void chatTopicsAreAnsweredFromTheLedgersAndFollowMeWorks(TestContext ctx) {
		expect(npc().spawn(ID, ctx.getWorld(), Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(8, FLOOR_Y + 1, 8)))).ok(), "spawn");
		LuminaryNpcEntity body = npc().body(ID).orElseThrow();
		PlayerEntity player = ctx.createMockSurvivalPlayer();
		player.refreshPositionAndAngles(ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)).x, player.getY(), ctx.getAbsolute(new Vec3d(8.5, FLOOR_Y + 1, 10.5)).z, 180f, 0f);
		expect(ctx.getWorld().spawnEntity(player), "player in the world");
		npc().trade().service().grant(npc().trade().ledger(ID, player), 7);
		var points = npc().request(body, player, "Trader, сколько у меня очков?").join();
		expect(points.intent().isPresent() && points.intent().get().literalReply().map(l -> l.contains("7")).orElse(false), "the points line carries the number: " + points.intent());
		var quests = npc().request(body, player, "есть задания?").join();
		expect(quests.intent().flatMap(i -> i.literalReply()).map(l -> l.contains("6") || l.contains("5")).orElse(false), "the quests line carries the counts: " + quests.intent());
		var follow = npc().request(body, player, "иди за мной").join();
		expect(follow.intent().isPresent() && body.getNavigation() != null, "follow answered");
		expect(npc().request(body, player, "стой").join().intent().flatMap(i -> i.replyKey()).filter("npc.luminary.follow.stop"::equals).isPresent(), "stop after following");
		expect(npc().request(body, player, "стой").join().intent().flatMap(i -> i.replyKey()).filter("npc.luminary.follow.not_following"::equals).isPresent(), "stop when not following");
		var hello = npc().request(body, player, "привет!").join();
		expect(hello.intent().flatMap(i -> i.literalReply()).isPresent(), "with no model, a greeting is answered from the sheet: " + hello.intent());
		var work = npc().request(body, player, "принеси мне меч").join();
		expect(work.intent().map(i -> !"topic".equals(i.plannerId())).orElse(true), "a line with a work cue is never a topic answer: " + work.intent());
		npc().despawn(ID);
		ctx.complete();
	}
}
