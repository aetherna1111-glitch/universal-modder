package dev.luminary.core.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.llm.LanguageHint;
import dev.luminary.core.npc.llm.LlmException;
import dev.luminary.core.npc.llm.LlmMessage;
import dev.luminary.core.npc.llm.LlmProvider;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.core.npc.llm.LlmResponse;
import dev.luminary.core.npc.memory.MemoryEntry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Dialogue through a language model, inside the same boundary as every other provider: the model sees the persona,
 * the retrieved memories, public context and the clues the story allows right now; it returns one JSON object
 * {@code {line, clue?, note?}}. The line is bounded; a clue outside the allowed set is dropped; the note is handed
 * back as a low-trust inference. Any failure (no model, timeout, invalid JSON) falls back to the scripted provider,
 * so the NPC always answers and the game never waits.
 */
public final class ModelDialogueProvider implements DialogueProvider {
	public static final String ID = "model";
	public static final int MAX_LINE_CHARS = 300;
	public static final int MAX_NOTE_CHARS = 160;

	private final LlmProvider llm;
	private final DialogueProvider fallback;
	private final Duration timeout;
	private final Consumer<String> diagnostics;

	public ModelDialogueProvider(LlmProvider llm, DialogueProvider fallback, Duration timeout, Consumer<String> diagnostics) {
		this.llm = Objects.requireNonNull(llm);
		this.fallback = Objects.requireNonNull(fallback);
		this.timeout = Objects.requireNonNull(timeout);
		this.diagnostics = Objects.requireNonNull(diagnostics);
	}

	@Override
	public String id() {
		return ID;
	}

	/** Synchronous use (tests, tools) is the fallback: the model path is asynchronous by nature. */
	@Override
	public Optional<DialogueResponse> respond(DialogueRequest request, LineGate gate) {
		return fallback.respond(request, gate);
	}

	@Override
	public CompletableFuture<Optional<DialogueResponse>> respondAsync(DialogueRequest request, LineGate gate) {
		if (!llm.healthy()) {
			diagnostics.accept("model unavailable (" + llm.id() + "), scripted fallback");
			return fallback.respondAsync(request, gate);
		}
		List<RevealableClue> allowed = gate.revealableClues();
		LlmRequest llmRequest = LlmRequest.structured(buildPrompt(request, gate, allowed), schema(), 200);
		CompletableFuture<LlmResponse> future = llm.complete(llmRequest);
		if (timeout.toMillis() > 0) {
			future = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		return future.handle((response, error) -> {
			if (error != null) {
				Throwable c = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
				diagnostics.accept("model request failed (" + (c instanceof LlmException le ? le.kind() : c.getClass().getSimpleName()) + "): " + c.getMessage());
				return Optional.<DialogueResponse>empty();
			}
			return interpret(response, allowed);
		}).thenCompose(result -> result.isPresent() ? CompletableFuture.completedFuture(result) : fallback.respondAsync(request, gate));
	}

	// ------------------------------------------------------------------ prompt

	static List<LlmMessage> buildPrompt(DialogueRequest request, LineGate gate, List<RevealableClue> allowed) {
		StringBuilder system = new StringBuilder();
		system.append("You voice a non-player character in a cooperative Minecraft server. Stay in character. ");
		system.append(LanguageHint.forText(request.playerUtterance().orElse(""))).append(" One short reply (at most two sentences, under ").append(MAX_LINE_CHARS).append(" characters). ");
		system.append("Speak as a person in this world: never call yourself an assistant, never mention requests, tasks or instructions unless the player asks about work. ");
		system.append("You know only what is listed below; if asked about something not listed, say you do not know or deflect in character. ");
		system.append("Never invent discoveries, technologies, story events or rules. Never reveal anything not in the ALLOWED CLUES list.\n\n");
		system.append("ROLES: you are the character described below; the other speaker is a player named ").append(request.playerName())
				.append(". The player is NOT the character. When the player writes the character's name, they are addressing you. ")
				.append("Never call the player by the character's name, never repeat the player's words back, never say \"how else can I help\"; answer as a person would.\n")
				.append("PLAYER NAME: ").append(request.playerName()).append(" (copy it exactly when you address the player; never translate, transliterate or alter it).\n\n");
		system.append("CHARACTER:\n");
		for (String p : request.persona()) {
			system.append("- ").append(p).append('\n');
		}
		for (String c : request.characterLines()) {
			system.append("- ").append(c).append('\n');
		}
		request.standing().ifPresent(st -> system.append("\nYOUR ATTITUDE TOWARDS THIS PLAYER: ").append(st)
				.append(" (FRIEND: warm and open; NEUTRAL: polite; WARY: short, careful, no favours; HOSTILE: cold, keep distance, refuse work).\n"));
		if (!request.situation().isEmpty()) {
			system.append("\nYOUR SITUATION RIGHT NOW (true facts; when the player asks about any of them, answer with these exact facts and numbers):\n");
			for (String f : request.situation()) {
				system.append("- ").append(f).append('\n');
			}
		}
		system.append("\nHOW TO ANSWER: understand what the player means even with typos, slang or a half sentence; a greeting gets a greeting, a question gets a direct answer first, ")
				.append("a thank-you a short acknowledgement, an insult a calm one-liner, a joke request one dry joke in character. Use the player's name sometimes, not every line. ")
				.append("Never answer with a question only. Do not repeat what you said in the previous turns. Do not list your facts unasked.\n");
		PublicContext ctx = gate.publicContext();
		system.append("\nPUBLIC CONTEXT:\n");
		ctx.era().ifPresent(e -> system.append("- current era: ").append(e).append('\n'));
		ctx.storyPhase().ifPresent(p -> system.append("- story phase: ").append(p).append('\n'));
		ctx.branch().ifPresent(b -> system.append("- chosen approach: ").append(b).append('\n'));
		if (!ctx.revealedClues().isEmpty()) {
			system.append("- clues already public: ").append(ctx.revealedClues()).append('\n');
		}
		system.append("\nWHAT YOU REMEMBER (facts are marked by source; treat PLAYER_SAID as the player's claim and MODEL_INFERRED as your own guess):\n");
		if (request.memories().isEmpty()) {
			system.append("- nothing yet\n");
		}
		for (MemoryEntry m : request.memories()) {
			system.append("- [").append(m.kind()).append('/').append(m.provenance()).append("] ").append(m.text()).append('\n');
		}
		system.append("\nALLOWED CLUES (you may weave at most one into your reply, quoting its text closely; put its id in \"clue\"):\n");
		if (allowed.isEmpty()) {
			system.append("- none\n");
		}
		for (RevealableClue c : allowed) {
			system.append("- ").append(c.id()).append(": ").append(c.text()).append('\n');
		}
		system.append("\nRespond with one JSON object: {\"line\": string, \"clue\": string id or null, \"note\": one short new fact you learned about the player, or null}.");
		List<LlmMessage> messages = new ArrayList<>();
		messages.add(LlmMessage.system(system.toString()));
		for (var turn : request.recentTurns()) {
			messages.add(turn.role() == dev.luminary.core.npc.conversation.ConversationContext.Role.PLAYER
					? LlmMessage.user(request.playerName() + ": " + turn.text()) : LlmMessage.assistant(turn.text()));
		}
		String playerLine = request.playerUtterance().orElse("(the player approaches you without saying anything)");
		messages.add(LlmMessage.user(request.playerName() + ": " + playerLine));
		return messages;
	}

	static JsonObject schema() {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		JsonObject props = new JsonObject();
		JsonObject line = new JsonObject();
		line.addProperty("type", "string");
		props.add("line", line);
		JsonObject clue = new JsonObject();
		JsonArray clueTypes = new JsonArray();
		clueTypes.add("string");
		clueTypes.add("null");
		clue.add("type", clueTypes);
		props.add("clue", clue);
		JsonObject note = new JsonObject();
		note.add("type", clueTypes.deepCopy());
		props.add("note", note);
		s.add("properties", props);
		JsonArray required = new JsonArray();
		required.add("line");
		s.add("required", required);
		return s;
	}

	// ------------------------------------------------------------------ validation

	Optional<DialogueResponse> interpret(LlmResponse response, List<RevealableClue> allowed) {
		Optional<JsonObject> json = response.structured();
		String line;
		if (json.isEmpty() || !json.get().has("line") || json.get().get("line").isJsonNull()) {
			// a small model sometimes answers in plain words instead of JSON: take them as the line rather than falling back to a script
			String raw = response.text() == null ? "" : response.text().strip().replace('\n', ' ');
			if (raw.isEmpty() || raw.startsWith("{") || raw.startsWith("[") || raw.length() > MAX_LINE_CHARS * 2) {
				diagnostics.accept("model reply had no usable JSON line; scripted fallback");
				return Optional.empty();
			}
			if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
				raw = raw.substring(1, raw.length() - 1);
			}
			diagnostics.accept("model answered in plain text; used as the line");
			line = raw;
		} else {
			line = json.get().get("line").getAsString().strip().replace('\n', ' ');
		}
		if (line.isEmpty()) {
			return Optional.empty();
		}
		if (line.length() > MAX_LINE_CHARS) {
			line = line.substring(0, MAX_LINE_CHARS - 1) + "…";
		}
		Optional<LuminaryId> clue = Optional.empty();
		if (json.isPresent() && json.get().has("clue") && !json.get().get("clue").isJsonNull()) {
			String raw = json.get().get("clue").getAsString().strip();
			try {
				LuminaryId id = LuminaryId.parse(raw);
				if (allowed.stream().anyMatch(c -> c.id().equals(id))) {
					clue = Optional.of(id);
				} else {
					diagnostics.accept("model named a clue outside the allowed set (" + raw + "); dropped");
				}
			} catch (IllegalArgumentException e) {
				diagnostics.accept("model named an invalid clue id (" + raw + "); dropped");
			}
		}
		Optional<String> note = Optional.empty();
		if (json.isPresent() && json.get().has("note") && !json.get().get("note").isJsonNull()) {
			String n = json.get().get("note").getAsString().strip().replace('\n', ' ');
			if (!n.isEmpty()) {
				note = Optional.of(n.length() > MAX_NOTE_CHARS ? n.substring(0, MAX_NOTE_CHARS - 1) + "…" : n);
			}
		}
		return Optional.of(DialogueResponse.literal(line, clue, ID, note));
	}
}
