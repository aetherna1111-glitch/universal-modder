package dev.luminary.core.npc.trade;

import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The points economy, pure: quests accepted, kills counted, rewards claimed, offers bought. The world gives it
 * ledgers and quest definitions; the Fabric layer hands out items and the weapon. Every rule a client could try to
 * bend is decided here.
 */
public final class TradeService {
	public static final String REASON_UNKNOWN_QUEST = "luminary.trade.unknown_quest";
	public static final String REASON_QUEST_DONE = "luminary.trade.quest_done";
	public static final String REASON_QUEST_ACTIVE = "luminary.trade.quest_active";
	public static final String REASON_QUEST_NOT_ACTIVE = "luminary.trade.quest_not_active";
	public static final String REASON_QUEST_INCOMPLETE = "luminary.trade.quest_incomplete";
	public static final String REASON_SPECIAL_LOCKED = "luminary.trade.special_locked";
	public static final String REASON_NOT_ENOUGH_POINTS = "luminary.trade.not_enough_points";
	public static final String REASON_UNKNOWN_OFFER = "luminary.trade.unknown_offer";
	public static final String REASON_UNIQUE_OWNED = "luminary.trade.unique_owned";

	/** How a quest looks to one player right now. */
	public enum QuestState { AVAILABLE, ACTIVE, CLAIMABLE, DONE, LOCKED }

	public record QuestView(Quest quest, QuestState state, int progress, int required) {
	}

	private final Function<LuminaryId, Optional<Quest>> quests;

	public TradeService(Function<LuminaryId, Optional<Quest>> quests) {
		this.quests = quests;
	}

	public QuestState state(Quest q, PlayerLedger ledger) {
		if (ledger.isCompleted(q.id())) {
			return QuestState.DONE;
		}
		if (ledger.isActive(q.id())) {
			return ledger.progress(q.id()).map(p -> p.satisfies(q)).orElse(false) ? QuestState.CLAIMABLE : QuestState.ACTIVE;
		}
		if (q.special() && !ledger.specialAvailable()) {
			return QuestState.LOCKED;
		}
		return QuestState.AVAILABLE;
	}

	public List<QuestView> views(List<Quest> all, PlayerLedger ledger) {
		List<QuestView> out = new ArrayList<>();
		for (Quest q : all) {
			int progress = ledger.progress(q.id()).map(PlayerLedger.Progress::total).orElse(ledger.isCompleted(q.id()) ? q.totalRequired() : 0);
			out.add(new QuestView(q, state(q, ledger), Math.min(progress, q.totalRequired()), q.totalRequired()));
		}
		return out;
	}

	public Result accept(LuminaryId questId, PlayerLedger ledger) {
		Optional<Quest> q = quests.apply(questId);
		if (q.isEmpty()) {
			return Result.fail(REASON_UNKNOWN_QUEST);
		}
		switch (state(q.get(), ledger)) {
			case DONE -> {
				return Result.fail(REASON_QUEST_DONE);
			}
			case ACTIVE, CLAIMABLE -> {
				return Result.fail(REASON_QUEST_ACTIVE);
			}
			case LOCKED -> {
				return Result.fail(REASON_SPECIAL_LOCKED);
			}
			default -> {
			}
		}
		ledger.accept(questId);
		return Result.OK;
	}

	/** A kill by the player: every active quest that wants this entity advances. Returns the quests that just became claimable. */
	public List<Quest> onKill(String entityId, PlayerLedger ledger) {
		List<Quest> ready = new ArrayList<>();
		for (Map.Entry<LuminaryId, PlayerLedger.Progress> e : ledger.active().entrySet()) {
			Optional<Quest> q = quests.apply(e.getKey());
			if (q.isEmpty() || !q.get().kills().containsKey(entityId) || e.getValue().count(entityId) >= q.get().kills().get(entityId)) {
				continue;
			}
			boolean before = e.getValue().satisfies(q.get());
			e.getValue().add(entityId);
			if (!before && e.getValue().satisfies(q.get())) {
				ready.add(q.get());
			}
		}
		return ready;
	}

	/** The player died: quests that reset on death lose their progress. Returns them. */
	public List<Quest> onDeath(PlayerLedger ledger) {
		List<Quest> reset = new ArrayList<>();
		for (Map.Entry<LuminaryId, PlayerLedger.Progress> e : ledger.active().entrySet()) {
			Optional<Quest> q = quests.apply(e.getKey());
			if (q.isPresent() && q.get().resetOnDeath() && e.getValue().total() > 0) {
				e.getValue().reset();
				reset.add(q.get());
			}
		}
		return reset;
	}

	/** Claims a finished quest: points are credited; the caller hands out {@link Quest#rewardItems()} and the special reward. */
	public Result claim(LuminaryId questId, PlayerLedger ledger) {
		Optional<Quest> q = quests.apply(questId);
		if (q.isEmpty()) {
			return Result.fail(REASON_UNKNOWN_QUEST);
		}
		QuestState s = state(q.get(), ledger);
		if (s == QuestState.DONE) {
			return Result.fail(REASON_QUEST_DONE);
		}
		if (s != QuestState.CLAIMABLE) {
			return Result.fail(s == QuestState.ACTIVE ? REASON_QUEST_INCOMPLETE : REASON_QUEST_NOT_ACTIVE);
		}
		ledger.complete(q.get());
		return Result.OK;
	}

	/** Buys offer {@code index}; the caller gives the item after OK. {@code ownsUnique} says whether the player already has a unique offer's item. */
	public Result buy(List<ShopOffer> offers, int index, PlayerLedger ledger, boolean ownsUnique) {
		if (index < 0 || index >= offers.size()) {
			return Result.fail(REASON_UNKNOWN_OFFER);
		}
		ShopOffer offer = offers.get(index);
		if (offer.unique() && ownsUnique) {
			return Result.fail(REASON_UNIQUE_OWNED);
		}
		if (!ledger.spend(offer.price())) {
			return Result.fail(REASON_NOT_ENOUGH_POINTS);
		}
		return Result.OK;
	}

	public void grant(PlayerLedger ledger, int amount) {
		ledger.grant(amount);
	}
}
