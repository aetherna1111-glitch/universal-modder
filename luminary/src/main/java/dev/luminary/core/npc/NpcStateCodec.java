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
import dev.luminary.core.npc.autonomy.AutonomyState;
import dev.luminary.core.npc.autonomy.StandingOrder;
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
			AutonomyState auto = r.autonomy();
			if (auto.enabled() || !auto.orders().isEmpty() || !auto.decisions().isEmpty() || !auto.avoidUntil().isEmpty()) {
				JsonObject a = new JsonObject();
				a.addProperty("enabled", auto.enabled());
				JsonArray orders = new JsonArray();
				for (StandingOrder order : auto.orders()) {
					JsonObject so = new JsonObject();
					so.addProperty("type", order.type());
					if (order instanceof StandingOrder.StockWood w) {
						so.addProperty("container", w.containerPos());
						so.addProperty("minLogs", w.minLogs());
					}
					orders.add(so);
				}
				a.add("orders", orders);
				JsonArray decisions = new JsonArray();
				auto.decisions().forEach(d -> decisions.add(d.toString()));
				a.add("decisions", decisions);
				JsonObject avoid = new JsonObject();
				auto.avoidUntil().forEach((pos, until) -> avoid.addProperty(Long.toString(pos), until.toString()));
				a.add("avoid", avoid);
				o.add("autonomy", a);
			}
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
		t.requestedBy().ifPresent(p -> o.addProperty("requestedBy", p.toString()));
		o.add("action", actionToJson(t.action()));
		JsonObject ledger = new JsonObject();
		ledger.addProperty("blocksBroken", t.ledger().blocksBroken());
		JsonObject collected = new JsonObject();
		t.ledger().collected().forEach(collected::addProperty);
		ledger.add("collected", collected);
		JsonObject deposited = new JsonObject();
		t.ledger().deposited().forEach(deposited::addProperty);
		ledger.add("deposited", deposited);
		if (!t.ledger().withdrawn().isEmpty()) {
			JsonObject withdrawn = new JsonObject();
			t.ledger().withdrawn().forEach(withdrawn::addProperty);
			ledger.add("withdrawn", withdrawn);
		}
		o.add("ledger", ledger);
		o.addProperty("currentTarget", t.currentTarget());
		o.addProperty("phaseTicks", t.phaseTicks());
		o.addProperty("idleTicks", t.idleTicks());
		return o;
	}

	/** Action arguments as JSON; the same shape the planner layer and commands print. */
	public static JsonObject actionToJson(NpcAction action) {
		JsonObject a = new JsonObject();
		a.addProperty("type", action.type());
		if (action instanceof NpcAction.GatherWood g) {
			a.addProperty("target", g.targetPos());
			a.addProperty("container", g.containerPos());
			a.addProperty("maxLogs", g.maxLogs());
		} else if (action instanceof NpcAction.MoveTo m) {
			a.addProperty("target", m.targetPos());
		} else if (action instanceof NpcAction.Deposit d) {
			a.addProperty("container", d.containerPos());
		} else if (action instanceof NpcAction.Withdraw w) {
			a.addProperty("container", w.containerPos());
			a.addProperty("item", w.itemId());
			a.addProperty("count", w.count());
		}
		return a;
	}

	public static NpcAction actionFromJson(JsonObject a) {
		String type = a.get("type").getAsString();
		switch (type) {
			case "gather_wood":
				return new NpcAction.GatherWood(a.get("target").getAsLong(), a.get("container").getAsLong(), a.get("maxLogs").getAsInt());
			case "move_to":
				return new NpcAction.MoveTo(a.get("target").getAsLong());
			case "deposit":
				return new NpcAction.Deposit(a.get("container").getAsLong());
			case "withdraw":
				return new NpcAction.Withdraw(a.get("container").getAsLong(), a.get("item").getAsString(), a.get("count").getAsInt());
			default:
				throw new IllegalArgumentException("unknown task action type '" + type + "'");
		}
	}

	static NpcTask taskFromJson(JsonObject o) {
		NpcAction action = actionFromJson(o.getAsJsonObject("action"));
		JsonObject l = o.getAsJsonObject("ledger");
		Map<String, Integer> collected = new TreeMap<>();
		l.getAsJsonObject("collected").entrySet().forEach(e -> collected.put(e.getKey(), e.getValue().getAsInt()));
		Map<String, Integer> deposited = new TreeMap<>();
		l.getAsJsonObject("deposited").entrySet().forEach(e -> deposited.put(e.getKey(), e.getValue().getAsInt()));
		Map<String, Integer> withdrawn = new TreeMap<>();
		if (l.has("withdrawn")) {
			l.getAsJsonObject("withdrawn").entrySet().forEach(e -> withdrawn.put(e.getKey(), e.getValue().getAsInt()));
		}
		UUID requestedBy = o.has("requestedBy") ? UUID.fromString(o.get("requestedBy").getAsString()) : null;
		return new NpcTask(UUID.fromString(o.get("id").getAsString()), action, Instant.parse(o.get("createdAt").getAsString()), requestedBy,
				TaskState.valueOf(o.get("state").getAsString()), o.has("failure") ? o.get("failure").getAsString() : null,
				TaskLedger.restore(l.get("blocksBroken").getAsInt(), collected, deposited, withdrawn),
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
			if (o.has("autonomy")) {
				JsonObject a = o.getAsJsonObject("autonomy");
				AutonomyState auto = roster.ensureRecord(id).autonomy();
				auto.setEnabled(a.has("enabled") && a.get("enabled").getAsBoolean());
				if (a.has("orders")) {
					for (JsonElement el : a.getAsJsonArray("orders")) {
						JsonObject so = el.getAsJsonObject();
						if ("stock_wood".equals(so.get("type").getAsString())) {
							auto.addOrder(new StandingOrder.StockWood(so.get("container").getAsLong(), so.get("minLogs").getAsInt()));
						} else {
							throw new IllegalArgumentException("unknown standing order type '" + so.get("type").getAsString() + "'");
						}
					}
				}
				if (a.has("decisions")) {
					for (JsonElement el : a.getAsJsonArray("decisions")) {
						AutonomyState.Restore.decision(auto, Instant.parse(el.getAsString()));
					}
				}
				if (a.has("avoid")) {
					for (Map.Entry<String, JsonElement> ae : a.getAsJsonObject("avoid").entrySet()) {
						AutonomyState.Restore.avoid(auto, Long.parseLong(ae.getKey()), Instant.parse(ae.getValue().getAsString()));
					}
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
