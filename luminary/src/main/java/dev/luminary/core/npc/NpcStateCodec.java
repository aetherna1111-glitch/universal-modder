package dev.luminary.core.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.core.npc.task.NpcAction;
import java.util.Optional;
import dev.luminary.core.npc.task.NpcTask;
import dev.luminary.core.npc.task.TaskLedger;
import dev.luminary.core.npc.task.TaskState;
import dev.luminary.core.persistence.SchemaMigrations;
import com.google.gson.JsonArray;
import java.util.TreeMap;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JSON codec for the NPC roster, stored in its own persistent state ({@code luminary_npc.dat}) because it changes
 * on every conversation and will grow with memory, unlike the civilization state. Own schema line, starting at 1.
 */
public final class NpcStateCodec {
	public static final int CURRENT_SCHEMA_VERSION = 1;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final SchemaMigrations migrations;

	public NpcStateCodec(SchemaMigrations migrations) {
		this.migrations = migrations;
	}

	public static NpcStateCodec production() {
		return new NpcStateCodec(new SchemaMigrations(1));
	}

	public String encode(NpcRoster roster) {
		JsonObject root = new JsonObject();
		root.addProperty(SchemaMigrations.SCHEMA_VERSION, CURRENT_SCHEMA_VERSION);
		JsonObject npcs = new JsonObject();
		roster.all().forEach((id, r) -> {
			JsonObject o = new JsonObject();
			r.entityUuid().ifPresent(u -> o.addProperty("entity", u.toString()));
			r.dimension().ifPresent(d -> o.addProperty("dimension", d));
			o.addProperty("pos", r.lastPosPacked());
			r.lastSeen().ifPresent(t -> o.addProperty("lastSeen", t.toString()));
			JsonObject players = new JsonObject();
			r.players().forEach((pid, m) -> {
				JsonObject p = new JsonObject();
				p.addProperty("name", m.nameAtLastMeeting());
				p.addProperty("interactions", m.interactions());
				p.addProperty("firstMet", m.firstMet().toString());
				p.addProperty("lastMet", m.lastMet().toString());
				if (m.lastLineKey() != null) {
					p.addProperty("lastLine", m.lastLineKey());
				}
				players.add(pid.toString(), p);
			});
			o.add("players", players);
			JsonArray containers = new JsonArray();
			r.authorizedContainers().forEach(containers::add);
			o.add("containers", containers);
			r.task().ifPresent(t -> o.add("task", taskToJson(t)));
			JsonArray memories = new JsonArray();
			for (MemoryEntry e : r.memory().all()) {
				JsonObject m = new JsonObject();
				m.addProperty("id", e.id().toString());
				m.addProperty("kind", e.kind().name());
				e.player().ifPresent(pl -> m.addProperty("player", pl.toString()));
				e.topicKey().ifPresent(t -> m.addProperty("topic", t));
				m.addProperty("text", e.text());
				m.addProperty("at", e.at().toString());
				m.addProperty("lastUsed", e.lastUsed().toString());
				m.addProperty("importance", e.importance());
				m.addProperty("provenance", e.provenance().name());
				memories.add(m);
			}
			o.add("memories", memories);
			npcs.add(id.toString(), o);
		});
		root.add("npcs", npcs);
		return GSON.toJson(root);
	}

	static JsonObject taskToJson(NpcTask t) {
		JsonObject o = new JsonObject();
		o.addProperty("id", t.id().toString());
		o.addProperty("createdAt", t.createdAt().toString());
		o.addProperty("state", t.state().name());
		t.failureReason().ifPresent(r -> o.addProperty("failure", r));
		JsonObject action = new JsonObject();
		action.addProperty("type", t.action().type());
		if (t.action() instanceof NpcAction.GatherWood g) {
			action.addProperty("target", g.targetPos());
			action.addProperty("container", g.containerPos());
			action.addProperty("maxLogs", g.maxLogs());
		}
		o.add("action", action);
		JsonObject ledger = new JsonObject();
		ledger.addProperty("blocksBroken", t.ledger().blocksBroken());
		JsonObject collected = new JsonObject();
		t.ledger().collected().forEach(collected::addProperty);
		ledger.add("collected", collected);
		JsonObject deposited = new JsonObject();
		t.ledger().deposited().forEach(deposited::addProperty);
		ledger.add("deposited", deposited);
		o.add("ledger", ledger);
		o.addProperty("currentTarget", t.currentTarget());
		o.addProperty("phaseTicks", t.phaseTicks());
		o.addProperty("idleTicks", t.idleTicks());
		return o;
	}

	static NpcTask taskFromJson(JsonObject o) {
		JsonObject a = o.getAsJsonObject("action");
		String type = a.get("type").getAsString();
		NpcAction action;
		if ("gather_wood".equals(type)) {
			action = new NpcAction.GatherWood(a.get("target").getAsLong(), a.get("container").getAsLong(), a.get("maxLogs").getAsInt());
		} else {
			throw new IllegalArgumentException("unknown task action type '" + type + "'");
		}
		JsonObject l = o.getAsJsonObject("ledger");
		Map<String, Integer> collected = new TreeMap<>();
		l.getAsJsonObject("collected").entrySet().forEach(e -> collected.put(e.getKey(), e.getValue().getAsInt()));
		Map<String, Integer> deposited = new TreeMap<>();
		l.getAsJsonObject("deposited").entrySet().forEach(e -> deposited.put(e.getKey(), e.getValue().getAsInt()));
		return new NpcTask(UUID.fromString(o.get("id").getAsString()), action, Instant.parse(o.get("createdAt").getAsString()),
				TaskState.valueOf(o.get("state").getAsString()), o.has("failure") ? o.get("failure").getAsString() : null,
				TaskLedger.restore(l.get("blocksBroken").getAsInt(), collected, deposited),
				o.get("currentTarget").getAsLong(), o.get("phaseTicks").getAsInt(), o.get("idleTicks").getAsInt());
	}

	public NpcRoster decode(String json) {
		JsonElement parsed = JsonParser.parseString(json);
		if (!parsed.isJsonObject()) {
			throw new IllegalArgumentException("npc state must be a JSON object");
		}
		JsonObject root = migrations.migrate(parsed.getAsJsonObject());
		NpcRoster roster = new NpcRoster();
		if (!root.has("npcs")) {
			return roster;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npcs").entrySet()) {
			LuminaryId id = LuminaryId.parse(e.getKey());
			JsonObject o = e.getValue().getAsJsonObject();
			NpcRoster.Restore.record(roster, id,
					o.has("entity") ? UUID.fromString(o.get("entity").getAsString()) : null,
					o.has("dimension") ? o.get("dimension").getAsString() : null,
					o.has("pos") ? o.get("pos").getAsLong() : 0L,
					o.has("lastSeen") ? Instant.parse(o.get("lastSeen").getAsString()) : null);
			if (o.has("containers")) {
				for (JsonElement c : o.getAsJsonArray("containers")) {
					NpcRoster.Restore.container(roster, id, c.getAsLong());
				}
			}
			if (o.has("task")) {
				NpcRoster.Restore.task(roster, id, taskFromJson(o.getAsJsonObject("task")));
			}
			if (o.has("memories")) {
				MemoryStore store = roster.ensureRecord(id).memory();
				for (JsonElement el : o.getAsJsonArray("memories")) {
					JsonObject m = el.getAsJsonObject();
					MemoryStore.Restore.entry(store, new MemoryEntry(UUID.fromString(m.get("id").getAsString()), MemoryKind.valueOf(m.get("kind").getAsString()),
							m.has("player") ? Optional.of(UUID.fromString(m.get("player").getAsString())) : Optional.empty(),
							m.has("topic") ? Optional.of(m.get("topic").getAsString()) : Optional.empty(),
							m.get("text").getAsString(), Instant.parse(m.get("at").getAsString()), Instant.parse(m.get("lastUsed").getAsString()),
							m.get("importance").getAsDouble(), Provenance.valueOf(m.get("provenance").getAsString())));
				}
			}
			if (o.has("players")) {
				for (Map.Entry<String, JsonElement> pe : o.getAsJsonObject("players").entrySet()) {
					JsonObject p = pe.getValue().getAsJsonObject();
					NpcRoster.Restore.player(roster, id, UUID.fromString(pe.getKey()), new NpcRoster.PlayerMemory(
							p.get("name").getAsString(), p.get("interactions").getAsInt(), Instant.parse(p.get("firstMet").getAsString()),
							Instant.parse(p.get("lastMet").getAsString()), p.has("lastLine") ? p.get("lastLine").getAsString() : null));
				}
			}
		}
		return roster;
	}
}
