package dev.luminary.core.npc.trade;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TradeServiceTest {
	private final LuminaryId npc = LuminaryId.parse("luminary:npc/lumin");
	private final Quest lich = new Quest(LuminaryId.parse("luminary:quest/lich"), npc, "t.lich", "d.lich", Map.of("bomd:lich", 1), 10, false, false, List.of(), 1);
	private final Quest essences = new Quest(LuminaryId.parse("luminary:quest/essences"), npc, "t.e", "d.e", Map.of("a:x", 1, "a:y", 2), 30, false, false, List.of(), 2);
	private final Quest trial = new Quest(LuminaryId.parse("luminary:quest/trial"), npc, "t.trial", "d.trial", Map.of("a:z", 1), 0, true, true, List.of(), 99);
	private final List<Quest> all = List.of(lich, essences, trial);
	private final TradeService service = new TradeService(id -> all.stream().filter(q -> q.id().equals(id)).findFirst());
	private final List<ShopOffer> shop = List.of(new ShopOffer("luminary:lightstone_dust", 8, 10, false), new ShopOffer("luminary:envoy_seal", 1, 80, true));

	@Test
	void killsCountOnlyAfterAcceptanceAndOnlyTheWantedEntities() {
		PlayerLedger l = new PlayerLedger();
		assertTrue(service.onKill("bomd:lich", l).isEmpty(), "nothing accepted: the kill is not counted");
		assertEquals(Result.OK, service.accept(lich.id(), l));
		assertEquals(TradeService.REASON_QUEST_ACTIVE, service.accept(lich.id(), l).reasonKey());
		assertTrue(service.onKill("minecraft:zombie", l).isEmpty());
		assertEquals(TradeService.QuestState.ACTIVE, service.state(lich, l));
		assertEquals(TradeService.REASON_QUEST_INCOMPLETE, service.claim(lich.id(), l).reasonKey());
		assertEquals(List.of(lich), service.onKill("bomd:lich", l), "the kill that completes the task is reported");
		assertTrue(service.onKill("bomd:lich", l).isEmpty(), "a kill past the requirement changes nothing");
		assertEquals(TradeService.QuestState.CLAIMABLE, service.state(lich, l));
		assertEquals(Result.OK, service.claim(lich.id(), l));
		assertEquals(10, l.points());
		assertEquals(TradeService.QuestState.DONE, service.state(lich, l));
		assertEquals(TradeService.REASON_QUEST_DONE, service.accept(lich.id(), l).reasonKey());
		assertEquals(TradeService.REASON_QUEST_DONE, service.claim(lich.id(), l).reasonKey());
	}

	@Test
	void aMultiEntityQuestNeedsEveryCountAndTheViewSumsProgress() {
		PlayerLedger l = new PlayerLedger();
		service.accept(essences.id(), l);
		service.onKill("a:x", l);
		service.onKill("a:y", l);
		TradeService.QuestView v = service.views(all, l).get(1);
		assertEquals(2, v.progress());
		assertEquals(3, v.required());
		assertEquals(TradeService.QuestState.ACTIVE, v.state());
		assertEquals(List.of(essences), service.onKill("a:y", l));
		assertEquals(TradeService.QuestState.CLAIMABLE, service.state(essences, l));
	}

	@Test
	void theSpecialQuestOpensAfterFiveTasksWithNothingBoughtAndDeathResetsIt() {
		PlayerLedger l = new PlayerLedger();
		assertEquals(TradeService.QuestState.LOCKED, service.state(trial, l));
		assertEquals(TradeService.REASON_SPECIAL_LOCKED, service.accept(trial.id(), l).reasonKey());
		for (int i = 0; i < 5; i++) {
			Quest q = new Quest(LuminaryId.parse("luminary:quest/q" + i), npc, "t", "d", Map.of("a:m", 1), 5, false, false, List.of(), i);
			l.restoreCompleted(q.id());
			l.grant(5);
		}
		assertTrue(l.specialAvailable());
		assertEquals(TradeService.QuestState.AVAILABLE, service.state(trial, l));
		assertEquals(Result.OK, service.accept(trial.id(), l));
		service.onKill("a:z", l);
		assertEquals(TradeService.QuestState.CLAIMABLE, service.state(trial, l));
		assertEquals(List.of(trial), service.onDeath(l), "death wipes the trial's count");
		assertEquals(TradeService.QuestState.ACTIVE, service.state(trial, l));
		assertTrue(service.onDeath(l).isEmpty(), "nothing to wipe twice");
		service.onKill("a:z", l);
		assertEquals(Result.OK, service.claim(trial.id(), l));
		assertTrue(l.specialClaimed());
		assertFalse(l.specialAvailable(), "offered once");
		assertEquals(25, l.points(), "the special quest pays in the blade, not points");
	}

	@Test
	void onePurchaseClosesTheSpecialQuestForGood() {
		PlayerLedger l = new PlayerLedger();
		for (int i = 0; i < 6; i++) {
			l.restoreCompleted(LuminaryId.parse("luminary:quest/q" + i));
		}
		l.grant(100);
		assertEquals(Result.OK, service.buy(shop, 0, l, false));
		assertEquals(90, l.points());
		assertEquals(1, l.purchases());
		assertFalse(l.specialAvailable());
		assertEquals(TradeService.QuestState.LOCKED, service.state(trial, l));
	}

	@Test
	void theShopChecksPointsIndexAndUniqueness() {
		PlayerLedger l = new PlayerLedger();
		assertEquals(TradeService.REASON_UNKNOWN_OFFER, service.buy(shop, 7, l, false).reasonKey());
		assertEquals(TradeService.REASON_NOT_ENOUGH_POINTS, service.buy(shop, 0, l, false).reasonKey());
		l.grant(200);
		assertEquals(TradeService.REASON_UNIQUE_OWNED, service.buy(shop, 1, l, true).reasonKey());
		assertEquals(Result.OK, service.buy(shop, 1, l, false));
		assertEquals(120, l.points());
		assertEquals(Result.OK, service.buy(shop, 0, l, true), "the dust is not unique: owning some is no bar");
		assertEquals(Optional.empty(), service.views(all, l).stream().filter(v -> v.state() == TradeService.QuestState.CLAIMABLE).findAny());
	}
}
