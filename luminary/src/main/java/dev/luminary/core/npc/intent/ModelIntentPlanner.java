package dev.luminary.core.npc.intent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.luminary.core.npc.capability.CapabilityRegistry;
import dev.luminary.core.npc.llm.LanguageHint;
import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmMessage;
import dev.luminary.core.npc.llm.LlmProvider;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.core.npc.llm.LlmResponse;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.TaskRules;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A model proposes the intent, but only in the vocabulary offered: an intent name from a fixed list, candidate
 * indices (never coordinates), an item id that the chosen container actually holds, a bounded count, and one reply
 * line. Anything else is dropped (the reply is kept, so the NPC still answers). Failures and timeouts hand the
 * request to the fallback planner, normally the keyword rules.
 */
public final class ModelIntentPlanner implements IntentPlanner {
	public static final String ID = "model";
	public static final int MAX_REPLY_CHARS = 300;

	private final LlmProvider llm;
	private final IntentPlanner fallback;
	private final TaskRules rules;
	private final Duration timeout;
	private final Consumer<String> diagnostics;
	/** The allowlist between model output and the world; every intent name the model emits passes through it. */
	private final CapabilityRegistry capabilities = new CapabilityRegistry();

	public ModelIntentPlanner(LlmProvider llm, IntentPlanner fallback, TaskRules rules, Duration timeout, Consumer<String> diagnostics) {
		this.llm = Objects.requireNonNull(llm);
		this.fallback = Objects.requireNonNull(fallback);
		this.rules = Objects.requireNonNull(rules);
		this.timeout = Objects.requireNonNull(timeout);
		this.diagnostics = Objects.requireNonNull(diagnostics);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public CompletableFuture<Optional<Intent>> plan(IntentRequest request) {
		if (!llm.healthy()) {
			diagnostics.accept("intent: model unavailable (" + llm.id() + "), keyword fallback");
			return fallback.plan(request);
		}
		CompletableFuture<LlmResponse> future = llm.complete(LlmRequest.structured(buildPrompt(request), schema(), 200));
		if (timeout.toMillis() > 0) {
			future = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		return future.handle((response, error) -> {
			if (error != null) {
				Throwable c = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
				diagnostics.accept("intent: model request failed (" + (c instanceof LlmException le ? le.kind() : c.getClass().getSimpleName()) + "): " + c.getMessage());
				return Optional.<Optional<Intent>>empty();
			}
			return Optional.of(interpret(response, request));
		}).thenCompose(r -> r.isPresent() ? CompletableFuture.completedFuture(r.get()) : fallback.plan(request));
	}

	// ------------------------------------------------------------------ prompt

	List<LlmMessage> buildPrompt(IntentRequest request) {
		IntentCandidates c = request.candidates();
		StringBuilder s = new StringBuilder();
		s.append("You decide whether a player's message to a non-player character in a cooperative Minecraft server is a request for ");
		s.append("physical work the character can do, and you answer in character. Allowed intents:\n");
		s.append("- gather_wood: cut logs at one of the LOGS below and put them into one of the CONTAINERS (count = logs, 1-").append(rules.maxLogsPerTask()).append(")\n");
		s.append("- deposit: put everything the character carries into one of the CONTAINERS\n");
		s.append("- withdraw: take an item that a CONTAINER actually holds (item id from its list, count 1-").append(rules.maxWithdrawCount()).append(")\n");
		s.append("- smelt: take an item a CONTAINER holds plus fuel from it, smelt it in one of the FURNACES, return the result (item id, count 1-").append(rules.maxWithdrawCount()).append(")\n");
		s.append("- move_to: walk to the player\n");
		s.append("- use_container: the player hands you the container they are LOOKING AT (listed below) for depositing and withdrawing\n");
		s.append("- forget_container: the player takes that container back\n");
		s.append("- none: not a request for work, or impossible with what is listed (explain briefly in character)\n");
		s.append("Rules: refer to places only by the indices below; never invent positions, items or abilities. ");
		s.append("If the message is NOT a request for work (greetings, questions, small talk), set intent to none and leave reply empty: ");
		s.append("someone else answers conversation. For a work intent, reply with one short in-character sentence confirming what you will do; ");
		s.append("never mention 'requests', 'tasks' or 'assisting'. ").append(LanguageHint.forText(request.utterance())).append("\n\n");
		s.append("PLAYER NAME: ").append(request.playerName()).append(" (copy it exactly; never translate, transliterate or alter it). The player is not the character; when they write the character's name they address you.\n")
				.append("Choose an action ONLY when the player clearly asks for work in these words. Compliments, questions, greetings, statements and talk about names are always \"none\".\n\n");
		s.append("CHARACTER:\n");
		for (String p : request.persona()) {
			s.append("- ").append(p).append('\n');
		}
		s.append("\nSTATE: ").append(request.busy() ? "busy with a task" : "idle").append("; carrying: ")
				.append(c.held().isEmpty() ? "nothing" : c.held().toString()).append('\n');
		s.append("\nLOGS (index: x y z):\n");
		if (c.logs().isEmpty()) s.append("- none in sight\n");
		for (int i = 0; i < c.logs().size(); i++) {
			s.append("- ").append(i).append(": ").append(c.logs().get(i).coords()).append('\n');
		}
		s.append("\nCONTAINERS the character may use (index: x y z, contents):\n");
		if (c.containers().isEmpty()) s.append("- none\n");
		for (int i = 0; i < c.containers().size(); i++) {
			IntentCandidates.ContainerInfo info = c.containers().get(i);
			s.append("- ").append(i).append(": ").append(info.spot().coords()).append(", ")
					.append(info.contents().isEmpty() ? "empty" : info.contents().toString()).append('\n');
		}
		s.append("\nFURNACES (index: x y z):\n");
		if (c.furnaces().isEmpty()) s.append("- none\n");
		for (int i = 0; i < c.furnaces().size(); i++) {
			s.append("- ").append(i).append(": ").append(c.furnaces().get(i).coords()).append('\n');
		}
		s.append("\nCONTAINER THE PLAYER IS LOOKING AT: ").append(c.lookedAtContainer().map(IntentCandidates.Spot::coords).orElse("none")).append('\n');
		s.append("\nPLAYER POSITION: ").append(c.playerPos().map(IntentCandidates.Spot::coords).orElse("unknown")).append('\n');
		if (!request.memories().isEmpty()) {
			s.append("\nWHAT YOU REMEMBER:\n");
			for (MemoryEntry m : request.memories()) {
				s.append("- [").append(m.kind()).append('/').append(m.provenance()).append("] ").append(m.text()).append('\n');
			}
		}
		s.append("\nRespond with one JSON object: {\"intent\": one of gather_wood|deposit|withdraw|smelt|move_to|use_container|forget_container|none, ")
				.append("\"log\": index or null, \"container\": index or null, \"furnace\": index or null, \"item\": item id or null, \"count\": integer or null, \"reply\": string}.");
		List<LlmMessage> messages = new ArrayList<>();
		messages.add(LlmMessage.system(s.toString()));
		messages.add(LlmMessage.user(request.playerName() + ": " + request.utterance()));
		return messages;
	}

	static JsonObject schema() {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		JsonObject props = new JsonObject();
		JsonObject intent = new JsonObject();
		intent.addProperty("type", "string");
		JsonArray values = new JsonArray();
		for (String v : List.of("gather_wood", "deposit", "withdraw", "smelt", "move_to", "use_container", "forget_container", "none")) values.add(v);
		intent.add("enum", values);
		props.add("intent", intent);
		JsonArray intOrNull = new JsonArray();
		intOrNull.add("integer");
		intOrNull.add("null");
		for (String k : List.of("log", "container", "furnace", "count")) {
			JsonObject p = new JsonObject();
			p.add("type", intOrNull.deepCopy());
			props.add(k, p);
		}
		JsonArray strOrNull = new JsonArray();
		strOrNull.add("string");
		strOrNull.add("null");
		JsonObject item = new JsonObject();
		item.add("type", strOrNull);
		props.add("item", item);
		JsonObject reply = new JsonObject();
		reply.addProperty("type", "string");
		props.add("reply", reply);
		s.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("intent");
		required.add("reply");
		s.add("required", required);
		return s;
	}

	// ------------------------------------------------------------------ validation

	Optional<Intent> interpret(LlmResponse response, IntentRequest request) {
		Optional<JsonObject> json = response.structured();
		if (json.isEmpty() || !json.get().has("intent") || json.get().get("intent").isJsonNull()) {
			diagnostics.accept("intent: model reply had no usable JSON; keyword fallback");
			return fallbackNow(request);
		}
		JsonObject o = json.get();
		String reply = o.has("reply") && !o.get("reply").isJsonNull() ? o.get("reply").getAsString().strip().replace('\n', ' ') : "";
		if (reply.length() > MAX_REPLY_CHARS) {
			reply = reply.substring(0, MAX_REPLY_CHARS - 1) + "…";
		}
		Optional<String> literal = reply.isEmpty() ? Optional.empty() : Optional.of(reply);
		String intent = o.get("intent").getAsString().strip();
		if (capabilities.forModelIntent(intent).isEmpty()) {
			// outside the allowlist: the words may be spoken, nothing is done; without words the character prompt answers
			diagnostics.accept("intent: model asked for '" + intent + "', which is outside the model allowlist; it only gets to talk");
			return literal.map(l -> new Intent(Optional.empty(), Optional.empty(), Optional.of(l), ID));
		}
		IntentCandidates c = request.candidates();
		Optional<NpcAction> action = Optional.empty();
		switch (intent) {
			case "use_container", "forget_container" -> {
				if (c.lookedAtContainer().isEmpty()) {
					return Optional.of(new Intent(Optional.empty(), Optional.of(Intent.REPLY_LOOK_AT_CONTAINER), Optional.empty(), ID));
				}
				Intent g = Intent.grant(c.lookedAtContainer().get().pos(), intent.equals("use_container"), ID);
				return Optional.of(literal.isPresent() ? new Intent(Optional.empty(), Optional.empty(), literal, ID, g.grant()) : g);
			}
			case "none" -> {
				// not work: the conversation goes to the dialogue provider, whose prompt is about the character, not about jobs
				return Optional.empty();
			}
			case "move_to" -> action = c.playerPos().map(p -> new NpcAction.MoveTo(p.pos()));
			case "deposit" -> action = index(o, "container", c.containers().size()).map(i -> new NpcAction.Deposit(c.containers().get(i).spot().pos()));
			case "gather_wood" -> {
				Optional<Integer> log = index(o, "log", c.logs().size());
				Optional<Integer> container = index(o, "container", c.containers().size());
				if (log.isEmpty() && !c.logs().isEmpty()) log = Optional.of(0);
				if (container.isEmpty() && !c.containers().isEmpty()) container = Optional.of(0);
				if (log.isPresent() && container.isPresent()) {
					int count = count(o).map(n -> Math.max(1, Math.min(n, rules.maxLogsPerTask()))).orElse(KeywordIntentPlanner.DEFAULT_LOGS);
					action = Optional.of(new NpcAction.GatherWood(c.logs().get(log.get()).pos(), c.containers().get(container.get()).spot().pos(), count));
				}
			}
			case "withdraw", "smelt" -> {
				Optional<Integer> container = index(o, "container", c.containers().size());
				Optional<Integer> furnace = index(o, "furnace", c.furnaces().size());
				if (furnace.isEmpty() && !c.furnaces().isEmpty()) furnace = Optional.of(0);
				String item = o.has("item") && !o.get("item").isJsonNull() ? o.get("item").getAsString().strip() : "";
				if (container.isPresent() && !item.isEmpty()) {
					Map<String, Integer> contents = c.containers().get(container.get()).contents();
					String resolved = contents.containsKey(item) ? item : contents.containsKey("minecraft:" + item) ? "minecraft:" + item : null;
					if (resolved == null) {
						diagnostics.accept("intent: model named an item the container does not hold (" + item + "); dropped");
					} else if (intent.equals("withdraw")) {
						int count = count(o).map(n -> Math.max(1, Math.min(n, rules.maxWithdrawCount()))).orElse(1);
						action = Optional.of(new NpcAction.Withdraw(c.containers().get(container.get()).spot().pos(), resolved, count));
					} else if (furnace.isPresent()) {
						int count = count(o).map(n -> Math.max(1, Math.min(n, rules.maxWithdrawCount()))).orElse(KeywordIntentPlanner.DEFAULT_SMELT);
						action = Optional.of(new NpcAction.Smelt(c.furnaces().get(furnace.get()).pos(), c.containers().get(container.get()).spot().pos(), resolved, count));
					}
				}
			}
			default -> diagnostics.accept("intent: model named an unknown intent (" + intent + "); dropped");
		}
		if (action.isEmpty()) {
			diagnostics.accept("intent: '" + intent + "' could not be grounded in the offered candidates; no action");
		}
		if (action.isPresent() && request.busy()) {
			diagnostics.accept("intent: NPC is busy; model action dropped");
			return Optional.of(new Intent(Optional.empty(), Optional.of(Intent.REPLY_BUSY), Optional.empty(), ID));
		}
		if (action.isEmpty() && literal.isEmpty()) {
			return fallbackNow(request);
		}
		return Optional.of(new Intent(action, Optional.empty(), literal, ID));
	}

	public CapabilityRegistry capabilities() {
		return capabilities;
	}

	private Optional<Intent> fallbackNow(IntentRequest request) {
		return fallback instanceof KeywordIntentPlanner k ? k.planNow(request) : Optional.empty();
	}

	private static Optional<Integer> index(JsonObject o, String key, int size) {
		if (!o.has(key) || o.get(key).isJsonNull()) {
			return Optional.empty();
		}
		try {
			int i = o.get(key).getAsInt();
			return i >= 0 && i < size ? Optional.of(i) : Optional.empty();
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static Optional<Integer> count(JsonObject o) {
		if (!o.has("count") || o.get("count").isJsonNull()) {
			return Optional.empty();
		}
		try {
			return Optional.of(o.get("count").getAsInt());
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
