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
import dev.luminary.core.npc.combat.CombatMachine;
import dev.luminary.core.npc.combat.CombatState;
import dev.luminary.core.npc.combat.Knockout;
import dev.luminary.core.npc.conversation.ConversationContext;
import dev.luminary.core.npc.plan.NpcPlan;
import dev.luminary.core.npc.plan.PlanStep;
import dev.luminary.core.npc.relationship.Relationship;
import dev.luminary.core.npc.relationship.RelationshipEvent;
import dev.luminary.core.npc.routine.PlaceKind;
import dev.luminary.core.npc.weapon.WeaponCustody;
import java.util.ArrayList;
import java.util.List;
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
	/** v1: roster, players, containers, task, memories, autonomy. v2: conversations, relationships, combat, knockout, weapon, routine, plan (all optional). */
	public static final int CURRENT_SCHEMA_VERSION = 2;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private final SchemaMigrations migrations;

	public NpcStateCodec(SchemaMigrations migrations) {
		this.migrations = migrations;
	}

	public static NpcStateCodec production() {
		return new NpcStateCodec(new SchemaMigrations(1).step(doc -> doc));   // v1 → v2: every new object is optional
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
			encodeSlice15(r, o);
			npcs.add(id.toString(), o);
		});
		root.add("npcs", npcs);
		return GSON.toJson(root);
	}

	private static void encodeSlice15(NpcRoster.NpcRecord r, JsonObject o) {
		if (!r.conversations().isEmpty()) {
			JsonObject conv = new JsonObject();
			r.conversations().forEach((pid, ctx) -> {
				if (ctx.isEmpty()) {
					return;
				}
				JsonArray turns = new JsonArray();
				for (ConversationContext.Turn t : ctx.all()) {
					JsonObject tj = new JsonObject();
					tj.addProperty("role", t.role().name());
					tj.addProperty("text", t.text());
					tj.addProperty("at", t.at().toString());
					turns.add(tj);
				}
				conv.add(pid.toString(), turns);
			});
			o.add("conversations", conv);
		}
		if (!r.relationships().all().isEmpty()) {
			JsonObject rel = new JsonObject();
			r.relationships().all().forEach((pid, rs) -> {
				JsonObject x = new JsonObject();
				x.addProperty("karma", rs.karma());
				x.addProperty("trust", rs.trust());
				x.addProperty("threat", rs.threat());
				x.addProperty("updatedAt", rs.updatedAt().toString());
				rs.lastEvent().ifPresent(ev -> x.addProperty("lastEvent", ev.name()));
				x.addProperty("attacks", rs.attacks());
				rel.add(pid.toString(), x);
			});
			o.add("relationships", rel);
		}
		CombatMachine combat = r.combat();
		if (combat.state() != CombatState.CALM) {
			JsonObject c = new JsonObject();
			c.addProperty("state", combat.state().name());
			combat.opponent().ifPresent(u -> c.addProperty("opponent", u.toString()));
			c.addProperty("since", combat.stateSince().toString());
			o.add("combat", c);
		}
		r.shieldBrokenUntil().ifPresent(t -> o.addProperty("shieldBrokenUntil", t.toString()));
		r.knockout().ifPresent(k -> {
			JsonObject kj = new JsonObject();
			kj.addProperty("since", k.since().toString());
			kj.addProperty("deadline", k.deadline().toString());
			k.downedBy().ifPresent(u -> kj.addProperty("downedBy", u.toString()));
			kj.addProperty("helped", k.timesHelped());
			kj.addProperty("looted", k.timesLooted());
			o.add("knockout", kj);
		});
		if (r.hasWeaponRecord()) {
			WeaponCustody.State w = r.weapon().state();
			JsonObject wj = new JsonObject();
			wj.addProperty("instance", w.instanceId().toString());
			wj.addProperty("custody", w.custody().name());
			w.holder().ifPresent(h -> wj.addProperty("holder", h.toString()));
			w.takenAt().ifPresent(t -> wj.addProperty("takenAt", t.toString()));
			w.returnAt().ifPresent(t -> wj.addProperty("returnAt", t.toString()));
			w.transferReason().ifPresent(t -> wj.addProperty("transferReason", t));
			wj.addProperty("thefts", w.thefts());
			wj.addProperty("returns", w.returns());
			o.add("weapon", wj);
		}
		if (!r.routine().enabled() || !r.routine().places().isEmpty()) {
			JsonObject rj = new JsonObject();
			rj.addProperty("enabled", r.routine().enabled());
			JsonObject places = new JsonObject();
			r.routine().places().forEach((k, pos) -> places.addProperty(k.name(), pos));
			rj.add("places", places);
			o.add("routine", rj);
		}
		r.plan().ifPresent(p -> o.add("plan", planToJson(p)));
		if (!r.ledgers().isEmpty()) {
			JsonObject trade = new JsonObject();
			r.ledgers().forEach((pid, l) -> {
				JsonObject lj = new JsonObject();
				lj.addProperty("points", l.points());
				lj.addProperty("purchases", l.purchases());
				lj.addProperty("specialClaimed", l.specialClaimed());
				JsonArray done = new JsonArray();
				l.completed().forEach(q -> done.add(q.toString()));
				lj.add("completed", done);
				JsonObject active = new JsonObject();
				l.active().forEach((q, prog) -> {
					JsonObject pj = new JsonObject();
					prog.kills().forEach(pj::addProperty);
					active.add(q.toString(), pj);
				});
				lj.add("active", active);
				trade.add(pid.toString(), lj);
			});
			o.add("trade", trade);
		}
	}

	static JsonObject planToJson(NpcPlan p) {
		JsonObject o = new JsonObject();
		o.addProperty("id", p.id().toString());
		o.addProperty("goal", p.goal());
		o.addProperty("createdAt", p.createdAt().toString());
		p.requestedBy().ifPresent(u -> o.addProperty("requestedBy", u.toString()));
		o.addProperty("status", p.status().name());
		o.addProperty("replans", p.replans());
		p.failure().ifPresent(f -> o.addProperty("failure", f));
		JsonArray steps = new JsonArray();
		for (PlanStep s : p.steps()) {
			JsonObject sj = new JsonObject();
			sj.addProperty("id", s.id());
			sj.add("action", actionToJson(s.action()));
			JsonObject pre = new JsonObject();
			if (s.precondition() instanceof PlanStep.Precondition.ContainerHas c) {
				pre.addProperty("type", "container_has");
				pre.addProperty("container", c.containerPos());
				pre.addProperty("item", c.itemId());
				pre.addProperty("count", c.count());
			} else if (s.precondition() instanceof PlanStep.Precondition.HandsEmpty) {
				pre.addProperty("type", "hands_empty");
			} else if (s.precondition() instanceof PlanStep.Precondition.Carrying) {
				pre.addProperty("type", "carrying");
			} else {
				pre.addProperty("type", "none");
			}
			sj.add("precondition", pre);
			sj.addProperty("status", s.status().name());
			sj.addProperty("attempts", s.attempts());
			s.taskId().ifPresent(t -> sj.addProperty("task", t.toString()));
			s.lastFailure().ifPresent(f -> sj.addProperty("failure", f));
			steps.add(sj);
		}
		o.add("steps", steps);
		return o;
	}

	static NpcPlan planFromJson(JsonObject o) {
		List<PlanStep> steps = new ArrayList<>();
		for (JsonElement el : o.getAsJsonArray("steps")) {
			JsonObject sj = el.getAsJsonObject();
			JsonObject pre = sj.getAsJsonObject("precondition");
			PlanStep.Precondition p = switch (pre.get("type").getAsString()) {
				case "container_has" -> new PlanStep.Precondition.ContainerHas(pre.get("container").getAsLong(), pre.get("item").getAsString(), pre.get("count").getAsInt());
				case "hands_empty" -> new PlanStep.Precondition.HandsEmpty();
				case "carrying" -> new PlanStep.Precondition.Carrying();
				default -> new PlanStep.Precondition.None();
			};
			PlanStep s = new PlanStep(sj.get("id").getAsString(), actionFromJson(sj.getAsJsonObject("action")), p);
			s.restore(PlanStep.Status.valueOf(sj.get("status").getAsString()), sj.get("attempts").getAsInt(),
					sj.has("task") ? UUID.fromString(sj.get("task").getAsString()) : null, sj.has("failure") ? sj.get("failure").getAsString() : null);
			steps.add(s);
		}
		NpcPlan plan = new NpcPlan(UUID.fromString(o.get("id").getAsString()), o.get("goal").getAsString(), steps, Instant.parse(o.get("createdAt").getAsString()),
				o.has("requestedBy") ? UUID.fromString(o.get("requestedBy").getAsString()) : null);
		plan.restore(NpcPlan.Status.valueOf(o.get("status").getAsString()), o.get("replans").getAsInt(), o.has("failure") ? o.get("failure").getAsString() : null);
		return plan;
	}

	private static void decodeSlice15(NpcRoster roster, LuminaryId id, JsonObject o) {
		NpcRoster.NpcRecord r = roster.ensureRecord(id);
		if (o.has("conversations")) {
			for (Map.Entry<String, JsonElement> ce : o.getAsJsonObject("conversations").entrySet()) {
				ConversationContext ctx = r.conversation(UUID.fromString(ce.getKey()));
				for (JsonElement el : ce.getValue().getAsJsonArray()) {
					JsonObject tj = el.getAsJsonObject();
					ctx.restore(new ConversationContext.Turn(ConversationContext.Role.valueOf(tj.get("role").getAsString()), tj.get("text").getAsString(), Instant.parse(tj.get("at").getAsString())));
				}
			}
		}
		if (o.has("relationships")) {
			for (Map.Entry<String, JsonElement> re : o.getAsJsonObject("relationships").entrySet()) {
				JsonObject x = re.getValue().getAsJsonObject();
				r.relationships().restore(UUID.fromString(re.getKey()), new Relationship(x.get("karma").getAsDouble(), x.get("trust").getAsDouble(), x.get("threat").getAsDouble(),
						Instant.parse(x.get("updatedAt").getAsString()), x.has("lastEvent") ? Optional.of(RelationshipEvent.valueOf(x.get("lastEvent").getAsString())) : Optional.empty(),
						x.has("attacks") ? x.get("attacks").getAsInt() : 0));
			}
		}
		if (o.has("combat")) {
			JsonObject c = o.getAsJsonObject("combat");
			r.combat().restore(CombatState.valueOf(c.get("state").getAsString()), c.has("opponent") ? UUID.fromString(c.get("opponent").getAsString()) : null, Instant.parse(c.get("since").getAsString()));
		}
		if (o.has("shieldBrokenUntil")) {
			r.setShieldBrokenUntil(Instant.parse(o.get("shieldBrokenUntil").getAsString()));
		}
		if (o.has("knockout")) {
			JsonObject k = o.getAsJsonObject("knockout");
			r.setKnockout(new Knockout(Instant.parse(k.get("since").getAsString()), Instant.parse(k.get("deadline").getAsString()),
					k.has("downedBy") ? Optional.of(UUID.fromString(k.get("downedBy").getAsString())) : Optional.empty(), k.get("helped").getAsInt(), k.get("looted").getAsInt()));
		}
		if (o.has("weapon")) {
			JsonObject w = o.getAsJsonObject("weapon");
			NpcRoster.Restore.weapon(roster, id, new WeaponCustody.State(UUID.fromString(w.get("instance").getAsString()), WeaponCustody.Custody.valueOf(w.get("custody").getAsString()),
					w.has("holder") ? Optional.of(UUID.fromString(w.get("holder").getAsString())) : Optional.empty(),
					w.has("takenAt") ? Optional.of(Instant.parse(w.get("takenAt").getAsString())) : Optional.empty(),
					w.has("returnAt") ? Optional.of(Instant.parse(w.get("returnAt").getAsString())) : Optional.empty(),
					w.has("transferReason") ? Optional.of(w.get("transferReason").getAsString()) : Optional.empty(),
					w.get("thefts").getAsInt(), w.get("returns").getAsInt()));
		}
		if (o.has("routine")) {
			JsonObject rj = o.getAsJsonObject("routine");
			r.routine().setEnabled(!rj.has("enabled") || rj.get("enabled").getAsBoolean());
			if (rj.has("places")) {
				for (Map.Entry<String, JsonElement> pe : rj.getAsJsonObject("places").entrySet()) {
					r.routine().setPlace(PlaceKind.valueOf(pe.getKey()), pe.getValue().getAsLong());
				}
			}
		}
		if (o.has("plan")) {
			NpcRoster.Restore.plan(roster, id, planFromJson(o.getAsJsonObject("plan")));
		}
		if (o.has("trade")) {
			for (Map.Entry<String, JsonElement> te : o.getAsJsonObject("trade").entrySet()) {
				JsonObject lj = te.getValue().getAsJsonObject();
				dev.luminary.core.npc.trade.PlayerLedger l = r.ledger(UUID.fromString(te.getKey()));
				l.restore(lj.get("points").getAsInt(), lj.get("purchases").getAsInt(), lj.has("specialClaimed") && lj.get("specialClaimed").getAsBoolean());
				if (lj.has("completed")) {
					for (JsonElement q : lj.getAsJsonArray("completed")) {
						l.restoreCompleted(LuminaryId.parse(q.getAsString()));
					}
				}
				if (lj.has("active")) {
					for (Map.Entry<String, JsonElement> ae : lj.getAsJsonObject("active").entrySet()) {
						dev.luminary.core.npc.trade.PlayerLedger.Progress prog = l.restoreActive(LuminaryId.parse(ae.getKey()));
						ae.getValue().getAsJsonObject().entrySet().forEach(ke -> prog.restore(ke.getKey(), ke.getValue().getAsInt()));
					}
				}
			}
		}
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
		if (!t.ledger().smelted().isEmpty()) {
			JsonObject smelted = new JsonObject();
			t.ledger().smelted().forEach(smelted::addProperty);
			ledger.add("smelted", smelted);
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
		} else if (action instanceof NpcAction.Smelt s) {
			a.addProperty("furnace", s.furnacePos());
			a.addProperty("container", s.containerPos());
			a.addProperty("item", s.inputItemId());
			a.addProperty("count", s.count());
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
			case "smelt":
				return new NpcAction.Smelt(a.get("furnace").getAsLong(), a.get("container").getAsLong(), a.get("item").getAsString(), a.get("count").getAsInt());
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
		Map<String, Integer> smelted = new TreeMap<>();
		if (l.has("smelted")) {
			l.getAsJsonObject("smelted").entrySet().forEach(e -> smelted.put(e.getKey(), e.getValue().getAsInt()));
		}
		UUID requestedBy = o.has("requestedBy") ? UUID.fromString(o.get("requestedBy").getAsString()) : null;
		return new NpcTask(UUID.fromString(o.get("id").getAsString()), action, Instant.parse(o.get("createdAt").getAsString()), requestedBy,
				TaskState.valueOf(o.get("state").getAsString()), o.has("failure") ? o.get("failure").getAsString() : null,
				TaskLedger.restore(l.get("blocksBroken").getAsInt(), collected, deposited, withdrawn, smelted),
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
			decodeSlice15(roster, id, o);
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
