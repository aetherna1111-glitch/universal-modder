package dev.luminary.core.npc;

import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.npc.combat.CombatState;
import dev.luminary.core.npc.combat.Knockout;
import dev.luminary.core.npc.conversation.ConversationContext;
import dev.luminary.core.npc.plan.NpcPlan;
import dev.luminary.core.npc.plan.PlanStep;
import dev.luminary.core.npc.plan.PlanTemplates;
import dev.luminary.core.npc.relationship.RelationshipEvent;
import dev.luminary.core.npc.relationship.RelationshipRules;
import dev.luminary.core.npc.routine.PlaceKind;
import dev.luminary.core.npc.weapon.WeaponCustody;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Schema v2: the behaviour state survives encode/decode and a v1 document still loads. */
class NpcStateCodecBehaviourTest {
	private final NpcStateCodec codec = NpcStateCodec.production();
	private final LuminaryId id = LuminaryId.parse("luminary:npc/lumin");
	private final Instant t0 = Instant.parse("2026-10-14T12:00:00Z");
	private final UUID player = UUID.randomUUID();

	@Test
	void roundTripsEverything() {
		NpcRoster roster = new NpcRoster();
		NpcRoster.NpcRecord r = roster.ensureRecord(id);
		r.conversation(player).add(ConversationContext.Role.PLAYER, "hello", t0);
		r.conversation(player).add(ConversationContext.Role.NPC, "yes?", t0.plusSeconds(1));
		r.relationships().apply(player, RelationshipEvent.ATTACKED, t0, RelationshipRules.DEFAULTS);
		r.combat().onAttacked(player, t0);
		r.setKnockout(Knockout.start(t0, Duration.ofMinutes(5), player).helped(t0.plusSeconds(1), Duration.ofSeconds(60)));
		r.weapon().onTaken(player, t0, Duration.ofHours(1));
		r.routine().setEnabled(false);
		r.routine().setPlace(PlaceKind.DESK, 12345L);
		NpcPlan plan = PlanTemplates.stockWood(1L, 2L, 3, t0, player);
		r.startPlan(plan);

		String json = codec.encode(roster);
		NpcRoster back = codec.decode(json);
		NpcRoster.NpcRecord b = back.get(id).orElseThrow();
		assertEquals(2, b.conversation(player).all().size());
		assertEquals("yes?", b.conversation(player).all().get(1).text());
		assertEquals(30, b.relationships().get(player, t0, RelationshipRules.DEFAULTS).threat());
		assertEquals(CombatState.DEFENSIVE_WARNING, b.combat().state());
		assertEquals(player, b.combat().opponent().orElseThrow());
		assertEquals(t0.plusSeconds(240), b.knockout().orElseThrow().deadline());
		assertEquals(1, b.knockout().orElseThrow().timesHelped());
		assertEquals(r.weapon().instanceId(), b.weapon().instanceId());
		assertEquals(WeaponCustody.Custody.STOLEN, b.weapon().custody());
		assertEquals(t0.plus(Duration.ofHours(1)), b.weapon().state().returnAt().orElseThrow());
		assertFalse(b.routine().enabled());
		assertEquals(12345L, b.routine().place(PlaceKind.DESK).orElseThrow());
		assertEquals(plan.id(), b.plan().orElseThrow().id());
		assertEquals(2, b.plan().orElseThrow().steps().size());
		assertEquals(PlanStep.Status.PENDING, b.plan().orElseThrow().steps().get(0).status());
		assertTrue(json.contains("\"schemaVersion\":2"));
	}

	@Test
	void aV1DocumentLoadsWithDefaults() {
		String v1 = "{\"schemaVersion\":1,\"npcs\":{\"luminary:npc/lumin\":{\"pos\":0,\"players\":{},\"containers\":[],\"memories\":[]}}}";
		NpcRoster back = codec.decode(v1);
		NpcRoster.NpcRecord b = back.get(id).orElseThrow();
		assertEquals(CombatState.CALM, b.combat().state());
		assertTrue(b.knockout().isEmpty());
		assertFalse(b.hasWeaponRecord());
		assertTrue(b.routine().enabled());
		assertTrue(b.plan().isEmpty());
		assertTrue(b.conversations().isEmpty());
	}

	@Test
	void newerDocumentsAreRefused() {
		assertThrows(dev.luminary.core.persistence.SchemaMigrations.UnsupportedSchemaException.class, () -> codec.decode("{\"schemaVersion\":3,\"npcs\":{}}"));
	}
}
