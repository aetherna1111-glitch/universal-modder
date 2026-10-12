package dev.luminary.core.npc.intent;

import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.TaskRules;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic rules, English and Russian, so the NPC takes orders with no model at all and the whole chain
 * (words → action → validation → execution → memory) is testable on a headless server. Also the fallback behind
 * the model planner. Matching is by word stems on a lower-cased utterance; the first matching rule wins.
 */
public final class KeywordIntentPlanner implements IntentPlanner {
	public static final String ID = "keyword";
	public static final int DEFAULT_LOGS = 4;

	private static final List<String> COME = List.of("come here", "come to me", "come over", "get over here", "подойди", "иди сюда", "ко мне", "сюда");
	private static final List<String> WOOD = List.of("wood", "log", "tree", "timber", "lumber", "firewood", "дерев", "дров", "брев", "бревн", "лес");
	private static final List<String> GATHER = List.of("gather", "chop", "cut", "collect", "fetch", "get", "bring", "harvest", "need", "want",
			"наруб", "сруб", "собер", "принес", "нужн", "хочу", "добуд", "заготов");
	private static final List<String> DEPOSIT = List.of("deposit", "store", "put away", "put it away", "unload", "stash", "put everything", "empty your",
			"сложи", "положи", "разгруз", "убери", "выгруз", "в сундук");
	private static final List<String> WITHDRAW = List.of("bring me", "fetch", "get me", "take out", "withdraw", "grab", "hand me", "give me",
			"принеси", "достань", "возьми", "дай", "вытащи");
	private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,3})\\b");

	private final TaskRules rules;

	public KeywordIntentPlanner(TaskRules rules) {
		this.rules = rules;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public CompletableFuture<Optional<Intent>> plan(IntentRequest request) {
		return CompletableFuture.completedFuture(planNow(request));
	}

	public Optional<Intent> planNow(IntentRequest request) {
		String text = " " + request.utterance().toLowerCase(Locale.ROOT).strip() + " ";
		IntentCandidates c = request.candidates();
		if (any(text, COME)) {
			if (request.busy()) return Optional.of(Intent.decline(Intent.REPLY_BUSY, ID));
			return Optional.of(c.playerPos().map(p -> Intent.act(new NpcAction.MoveTo(p.pos()), Intent.REPLY_WILL_DO, ID))
					.orElseGet(() -> Intent.decline(Intent.REPLY_WHERE, ID)));
		}
		boolean wood = any(text, WOOD);
		if (any(text, WITHDRAW) && !wood) {
			Optional<Withdrawal> w = matchItem(text, c);
			if (w.isPresent()) {
				if (request.busy()) return Optional.of(Intent.decline(Intent.REPLY_BUSY, ID));
				int count = number(text).map(n -> Math.max(1, Math.min(n, rules.maxWithdrawCount()))).orElse(1);
				return Optional.of(Intent.act(new NpcAction.Withdraw(w.get().container().spot().pos(), w.get().itemId(), count), Intent.REPLY_WILL_DO, ID));
			}
			if (mentionsAnItemWord(text, c)) {
				return Optional.of(Intent.decline(Intent.REPLY_NO_SUCH_ITEM, ID));
			}
		}
		if (wood && any(text, GATHER)) {
			if (request.busy()) return Optional.of(Intent.decline(Intent.REPLY_BUSY, ID));
			if (c.nearestLog().isEmpty()) return Optional.of(Intent.decline(Intent.REPLY_NO_TREES, ID));
			if (c.nearestContainer().isEmpty()) return Optional.of(Intent.decline(Intent.REPLY_NO_CONTAINER, ID));
			int logs = number(text).map(n -> Math.max(1, Math.min(n, rules.maxLogsPerTask()))).orElse(DEFAULT_LOGS);
			return Optional.of(Intent.act(new NpcAction.GatherWood(c.nearestLog().get().pos(), c.nearestContainer().get().spot().pos(), logs), Intent.REPLY_WILL_DO, ID));
		}
		if (any(text, DEPOSIT)) {
			if (request.busy()) return Optional.of(Intent.decline(Intent.REPLY_BUSY, ID));
			if (c.nearestContainer().isEmpty()) return Optional.of(Intent.decline(Intent.REPLY_NO_CONTAINER, ID));
			return Optional.of(Intent.act(new NpcAction.Deposit(c.nearestContainer().get().spot().pos()), Intent.REPLY_WILL_DO, ID));
		}
		return Optional.empty();
	}

	private record Withdrawal(IntentCandidates.ContainerInfo container, String itemId) {}

	/** An item is matched when a word of its id path (e.g. "oak", "planks") appears in the text, or the full id does. */
	private static Optional<Withdrawal> matchItem(String text, IntentCandidates c) {
		Withdrawal best = null;
		int bestScore = 0;
		for (IntentCandidates.ContainerInfo info : c.containers()) {
			for (Map.Entry<String, Integer> e : info.contents().entrySet()) {
				int score = itemScore(text, e.getKey());
				if (score > bestScore) {
					bestScore = score;
					best = new Withdrawal(info, e.getKey());
				}
			}
		}
		return Optional.ofNullable(best);
	}

	static int itemScore(String text, String itemId) {
		if (text.contains(" " + itemId + " ")) return 100;
		String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
		int score = 0;
		for (String word : path.split("_")) {
			if (word.length() >= 3 && text.contains(word)) {
				score += word.length();
			}
		}
		return score;
	}

	private static boolean mentionsAnItemWord(String text, IntentCandidates c) {
		return c.held().keySet().stream().anyMatch(id -> itemScore(text, id) > 0);
	}

	private static boolean any(String text, List<String> stems) {
		for (String s : stems) {
			if (text.contains(s)) {
				return true;
			}
		}
		return false;
	}

	static Optional<Integer> number(String text) {
		Matcher m = NUMBER.matcher(text);
		return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
	}
}
