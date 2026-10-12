package dev.luminary.core.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.persistence.SchemaMigrations;

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
			npcs.add(id.toString(), o);
		});
		root.add("npcs", npcs);
		return GSON.toJson(root);
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
