package dev.luminary.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.catalog.DiscoveryRecord;
import dev.luminary.core.catalog.DiscoveryTemplate;
import dev.luminary.core.catalog.Evidence;
import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
import dev.luminary.core.progression.Era;
import dev.luminary.core.progression.Prerequisite;
import dev.luminary.core.progression.Technology;
import dev.luminary.core.story.Clue;
import dev.luminary.core.story.StoryCondition;
import dev.luminary.core.story.StoryPhase;
import dev.luminary.core.npc.DialogueProvider;
import dev.luminary.core.npc.NpcDefinition;
import dev.luminary.core.npc.NpcDialogueService;
import dev.luminary.core.npc.NpcRoster;
import dev.luminary.fabric.npc.LuminaryNpcEntity;
import dev.luminary.fabric.npc.NpcService;
import dev.luminary.fabric.LuminaryRuntime;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.util.Identifier;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /luminary ...} — administration and debugging surface of the first slice. Everything here is
 * server-side; players never unlock or verify anything from the client.
 * <pre>
 * /luminary status                                      (op 2)
 * /luminary catalog templates                          (op 2)
 * /luminary catalog list                               (op 2)
 * /luminary catalog observe &lt;id&gt; [player]              (op 2)  attribution = player or the executing player
 * /luminary catalog sample &lt;id&gt; &lt;item&gt; &lt;count&gt;         (op 2)  adds Sample evidence from the executing player
 * Ids are Minecraft identifiers (ns:path); a bare path gets the "minecraft" namespace, so always write "luminary:...".
 * /luminary catalog verify &lt;id&gt;                        (op 3)  rules enforced
 * /luminary catalog verify &lt;id&gt; force                  (op 4)  admin override, recorded in history
 * /luminary progression status                         (op 2)  era, unlocked count, capabilities, signals
 * /luminary progression eras | technologies            (op 2)  definitions with "missing" prerequisites per technology
 * /luminary progression unlock &lt;id&gt; [force]            (op 3 / op 4)
 * /luminary progression signal &lt;id&gt; &lt;value&gt;            (op 4)  sets an external signal (integrations do this in code)
 * /luminary story status | phases | clues              (op 2)  phase, branch, revealed and currently revealable clues
 * /luminary story set &lt;phase&gt;                          (op 4)  administrative phase change, recorded
 * /luminary story branch &lt;branch&gt;                      (op 4)  commit to a strategic approach
 * /luminary story reveal &lt;clue&gt;                        (op 3)  surface a clue; refused while locked, no override exists
 * /luminary npc list                                   (op 2)  roster: body, position, players remembered
 * /luminary npc spawn &lt;npc&gt;                            (op 3)  one body per NPC, at the executor's position
 * /luminary npc despawn &lt;npc&gt;                          (op 3)
 * /luminary npc follow &lt;npc&gt; &lt;player&gt; | stop           (op 3)
 * /luminary npc talk &lt;npc&gt;                             (op 2)  talk without clicking (needs a player executor and a present body)
 * /luminary npc provider &lt;scripted|disabled&gt;           (op 4)  swap the dialogue provider until restart
 * </pre>
 */
public final class LuminaryCommands {
	private static final UUID CONSOLE = new UUID(0L, 0L);

	private LuminaryCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("luminary")
				.requires(src -> src.hasPermissionLevel(2))
				.then(CommandManager.literal("status").executes(LuminaryCommands::status))
				.then(CommandManager.literal("catalog")
						.then(CommandManager.literal("templates").executes(LuminaryCommands::templates))
						.then(CommandManager.literal("list").executes(LuminaryCommands::list))
						.then(CommandManager.literal("observe")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(ctx -> observe(ctx, null))
										.then(CommandManager.argument("player", EntityArgumentType.player())
												.executes(ctx -> observe(ctx, EntityArgumentType.getPlayer(ctx, "player"))))))
						.then(CommandManager.literal("sample")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.then(CommandManager.argument("item", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
														.executes(LuminaryCommands::sample)))))
						.then(CommandManager.literal("verify")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(ctx -> verify(ctx, false))
										.then(CommandManager.literal("force")
												.requires(src -> src.hasPermissionLevel(4))
												.executes(ctx -> verify(ctx, true))))))
				.then(CommandManager.literal("progression")
						.then(CommandManager.literal("status").executes(LuminaryCommands::progressionStatus))
						.then(CommandManager.literal("eras").executes(LuminaryCommands::eras))
						.then(CommandManager.literal("technologies").executes(LuminaryCommands::technologies))
						.then(CommandManager.literal("unlock")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(ctx -> unlock(ctx, false))
										.then(CommandManager.literal("force")
												.requires(src -> src.hasPermissionLevel(4))
												.executes(ctx -> unlock(ctx, true)))))
						.then(CommandManager.literal("signal")
								.requires(src -> src.hasPermissionLevel(4))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.then(CommandManager.argument("value", LongArgumentType.longArg())
												.executes(LuminaryCommands::signal)))))
				.then(CommandManager.literal("story")
						.then(CommandManager.literal("status").executes(LuminaryCommands::storyStatus))
						.then(CommandManager.literal("phases").executes(LuminaryCommands::phases))
						.then(CommandManager.literal("clues").executes(LuminaryCommands::clues))
						.then(CommandManager.literal("set")
								.requires(src -> src.hasPermissionLevel(4))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::setPhase)))
						.then(CommandManager.literal("branch")
								.requires(src -> src.hasPermissionLevel(4))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::branch)))
						.then(CommandManager.literal("reveal")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::reveal))))
				.then(CommandManager.literal("npc")
						.then(CommandManager.literal("list").executes(LuminaryCommands::npcList))
						.then(CommandManager.literal("spawn")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcSpawn)))
						.then(CommandManager.literal("despawn")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcDespawn)))
						.then(CommandManager.literal("follow")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.then(CommandManager.literal("stop").executes(ctx -> npcFollow(ctx, null)))
										.then(CommandManager.argument("player", EntityArgumentType.player())
												.executes(ctx -> npcFollow(ctx, EntityArgumentType.getPlayer(ctx, "player"))))))
						.then(CommandManager.literal("talk")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcTalk)))
						.then(CommandManager.literal("provider")
								.requires(src -> src.hasPermissionLevel(4))
								.then(CommandManager.argument("provider", com.mojang.brigadier.arguments.StringArgumentType.word())
										.executes(LuminaryCommands::npcProvider)))));
	}

	private static int npcList(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			NpcService npc = rt.npc();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.npc_provider_is", npc.dialogue().provider().id()), false);
			for (NpcDefinition d : npc.dialogue().definitions().npcs()) {
				Optional<NpcRoster.NpcRecord> r = npc.roster().get(d.id());
				boolean present = npc.body(d.id()).isPresent();
				String body = present ? "present" : r.flatMap(NpcRoster.NpcRecord::entityUuid).map(u -> "missing body").orElse("never spawned");
				String where = r.flatMap(NpcRoster.NpcRecord::dimension).map(dim -> dim + " " + BlockPos.fromLong(r.get().lastPosPacked()).toShortString()).orElse("-");
				int players = r.map(x -> x.players().size()).orElse(0);
				ctx.getSource().sendFeedback(() -> Text.literal(" - " + d.id() + "  " + Text.translatable(d.nameKey()).getString() + "  " + body + "  last=" + where + "  players=" + players), false);
			}
			return npc.dialogue().definitions().npcs().size();
		}).orElse(0);
	}

	private static int npcSpawn(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			Result r = rt.npc().spawn(id, ctx.getSource().getWorld(), ctx.getSource().getPosition());
			return report(ctx.getSource(), r, "luminary.command.npc_spawned", id);
		}).orElse(0);
	}

	private static int npcDespawn(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			return report(ctx.getSource(), rt.npc().despawn(id), "luminary.command.npc_despawned", id);
		}).orElse(0);
	}

	private static int npcFollow(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			Result r = rt.npc().follow(id, player == null ? null : player.getUuid());
			return report(ctx.getSource(), r, player == null ? "luminary.command.npc_follow_stopped" : "luminary.command.npc_following", id);
		}).orElse(0);
	}

	private static int npcTalk(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			ServerPlayerEntity player = ctx.getSource().getPlayer();
			if (player == null) {
				ctx.getSource().sendError(Text.translatable("luminary.command.needs_player"));
				return 0;
			}
			Optional<LuminaryNpcEntity> body = rt.npc().body(id);
			if (body.isEmpty()) {
				ctx.getSource().sendError(Text.translatable(NpcService.REASON_NOT_PRESENT));
				return 0;
			}
			Optional<NpcDialogueService.Turn> turn = rt.npc().talk(body.get(), player);
			return turn.isPresent() ? 1 : 0;
		}).orElse(0);
	}

	private static int npcProvider(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "provider");
			Optional<DialogueProvider> provider = dev.luminary.fabric.LuminaryRuntime.providerFor(id);
			if (provider.isEmpty()) {
				ctx.getSource().sendError(Text.translatable("luminary.command.npc_unknown_provider", id));
				return 0;
			}
			rt.setDialogueProvider(provider.get());
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.npc_provider_set", id), true);
			return 1;
		}).orElse(0);
	}

	private static int storyStatus(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			var st = rt.story();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.story_status",
					st.state().currentPhase().map(Object::toString).orElse("-"), st.state().branch().map(Object::toString).orElse("-"),
					st.state().revealedClues().size(), st.definitions().clues().size(), st.revealable().size()), false);
			return 1;
		}).orElse(0);
	}

	private static int phases(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId current = rt.story().state().currentPhase().orElse(null);
			for (StoryPhase p : rt.story().definitions().phasesInOrder()) {
				String marker = p.id().equals(current) ? " <== current" : "";
				String conditions = p.activationConditions().isEmpty() ? "none" : "missing=" + describeConditions(rt.story().missing(p)) + " of " + p.activationConditions().size();
				ctx.getSource().sendFeedback(() -> Text.literal(" " + p.order() + ". " + p.id() + "  " + Text.translatable(p.titleKey()).getString()
						+ (p.autoAdvance() ? "  [auto]" : "  [admin]") + "  " + conditions + marker), false);
			}
			return rt.story().definitions().phasesInOrder().size();
		}).orElse(0);
	}

	private static int clues(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			for (Clue c : rt.story().definitions().clues()) {
				String state = rt.story().state().isRevealed(c.id()) ? "REVEALED" : rt.story().mayReveal(c.id()) ? "revealable" : "locked";
				ctx.getSource().sendFeedback(() -> Text.literal(" - " + c.id() + "  phase=" + c.phase() + "  " + state), false);
			}
			return rt.story().definitions().clues().size();
		}).orElse(0);
	}

	private static int setPhase(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			return report(ctx.getSource(), rt.story().setPhase(id, actorId(ctx.getSource())), "luminary.command.phase_set", id);
		}).orElse(0);
	}

	private static int branch(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			return report(ctx.getSource(), rt.story().chooseBranch(id, actorId(ctx.getSource())), "luminary.command.branch_set", id);
		}).orElse(0);
	}

	private static int reveal(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			Result r = rt.story().reveal(id, "command:" + ctx.getSource().getName());
			if (r.ok()) {
				Clue clue = rt.story().definitions().clue(id).orElseThrow();
				ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.clue_revealed", id.toString()).append(Text.literal(" — ")).append(Text.translatable(clue.textKey())), true);
				return 1;
			}
			ctx.getSource().sendError(Text.translatable(r.reasonKey()));
			return 0;
		}).orElse(0);
	}

	private static String describeConditions(java.util.Collection<StoryCondition> conditions) {
		java.util.List<String> parts = new java.util.ArrayList<>();
		for (StoryCondition c : conditions) {
			if (c instanceof StoryCondition.Progression p) parts.add(describe(java.util.List.of(p.prerequisite())));
			else if (c instanceof StoryCondition.BranchChosen b) parts.add("branch:" + b.branch());
			else if (c instanceof StoryCondition.ClueRevealed r) parts.add("clue:" + r.clue());
			else if (c instanceof StoryCondition.PhaseAtLeast p) parts.add("phase>=" + p.phase());
		}
		java.util.Collections.sort(parts);
		return parts.isEmpty() ? "none" : String.join(", ", parts);
	}

	private static int progressionStatus(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			var p = rt.progression();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.progression_status",
					p.state().currentEra().map(Object::toString).orElse("-"), p.state().unlocked().size(),
					p.definitions().technologies().size(), String.join(", ", p.capabilities().stream().map(Object::toString).sorted().toList()),
					p.state().signals().toString()), false);
			return 1;
		}).orElse(0);
	}

	private static int eras(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId current = rt.progression().state().currentEra().orElse(null);
			for (Era e : rt.progression().definitions().erasInOrder()) {
				String marker = e.id().equals(current) ? " <== current" : "";
				ctx.getSource().sendFeedback(() -> Text.literal(" " + e.order() + ". " + e.id() + "  " + Text.translatable(e.titleKey()).getString()
						+ "  prerequisites=" + describe(e.prerequisites()) + marker), false);
			}
			return rt.progression().definitions().erasInOrder().size();
		}).orElse(0);
	}

	private static int technologies(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			for (Technology t : rt.progression().definitions().technologies()) {
				boolean unlocked = rt.progression().state().isUnlocked(t.id());
				String tail = unlocked ? "UNLOCKED" : "missing=" + describe(rt.progression().missing(t));
				ctx.getSource().sendFeedback(() -> Text.literal(" - " + t.id() + "  era=" + t.era() + "  " + tail), false);
			}
			return rt.progression().definitions().technologies().size();
		}).orElse(0);
	}

	private static int unlock(CommandContext<ServerCommandSource> ctx, boolean force) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			Result result = rt.progression().unlock(id, actorId(ctx.getSource()), force);
			return report(ctx.getSource(), result, force ? "luminary.command.unlocked_forced" : "luminary.command.unlocked", id);
		}).orElse(0);
	}

	private static int signal(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			long value = LongArgumentType.getLong(ctx, "value");
			rt.progression().setSignal(id, value, "command:" + ctx.getSource().getName());
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.signal_set", id.toString(), value), true);
			return 1;
		}).orElse(0);
	}

	private static String describe(java.util.Collection<Prerequisite> prerequisites) {
		if (prerequisites.isEmpty()) {
			return "none";
		}
		java.util.List<String> parts = new java.util.ArrayList<>();
		for (Prerequisite p : prerequisites) {
			if (p instanceof Prerequisite.EraAtLeast e) parts.add("era>=" + e.era());
			else if (p instanceof Prerequisite.DiscoveryVerified d) parts.add("verified:" + d.discovery());
			else if (p instanceof Prerequisite.TechnologyUnlocked t) parts.add("tech:" + t.technology());
			else if (p instanceof Prerequisite.Signal s) parts.add("signal:" + s.signal() + ">=" + s.minValue());
		}
		java.util.Collections.sort(parts);
		return String.join(", ", parts);
	}

	private static Optional<LuminaryRuntime> runtime(ServerCommandSource src) {
		Optional<LuminaryRuntime> rt = LuminaryRuntime.get();
		if (rt.isEmpty()) {
			src.sendError(Text.translatable("luminary.command.not_ready"));
		}
		return rt;
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.status",
					rt.catalog().templates().size(), rt.state().catalog().size(), rt.state().executedEffectIds().size()), false);
			return 1;
		}).orElse(0);
	}

	private static int templates(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			for (DiscoveryTemplate t : rt.catalog().templates().all()) {
				ctx.getSource().sendFeedback(() -> Text.literal(" - " + t.id() + "  " + Text.translatable(t.titleKey()).getString()
						+ "  [" + t.visibility() + ", contributors>=" + t.verification().minDistinctContributors()
						+ ", samples>=" + t.verification().minSamples() + "]"), false);
			}
			return rt.catalog().templates().size();
		}).orElse(0);
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			for (DiscoveryRecord r : rt.state().catalog().all()) {
				ctx.getSource().sendFeedback(() -> Text.literal(" - " + r.id() + "  " + r.status() + "  by " + r.firstObserver().playerNameAtTime()
						+ "  evidence=" + r.evidence().size()), false);
			}
			return rt.state().catalog().size();
		}).orElse(0);
	}

	private static int observe(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity explicit) {
		return runtime(ctx.getSource()).map(rt -> {
			ServerPlayerEntity player = explicit != null ? explicit : ctx.getSource().getPlayer();
			LuminaryId id = parseId(ctx);
			if (id == null) {
				return 0;
			}
			Attribution who = attribution(ctx.getSource(), player);
			Result result = rt.catalog().observe(id, who);
			return report(ctx.getSource(), result, "luminary.command.observed", id);
		}).orElse(0);
	}

	private static int sample(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			if (id == null) {
				return 0;
			}
			LuminaryId item = toLuminaryId(IdentifierArgumentType.getIdentifier(ctx, "item"));
			int count = IntegerArgumentType.getInteger(ctx, "count");
			UUID contributor = actorId(ctx.getSource());
			LuminaryId evidenceId = new LuminaryId(id.namespace(), id.path() + "/sample/" + contributor + "/" + item.path() + "/" + Instant.now().toEpochMilli());
			Result result = rt.catalog().addEvidence(id, new Evidence.Sample(evidenceId, contributor, Instant.now(), item, count));
			return report(ctx.getSource(), result, "luminary.command.evidence_added", id);
		}).orElse(0);
	}

	private static int verify(CommandContext<ServerCommandSource> ctx, boolean force) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			if (id == null) {
				return 0;
			}
			Result result = rt.catalog().tryVerify(id, actorId(ctx.getSource()), force);
			return report(ctx.getSource(), result, force ? "luminary.command.verified_forced" : "luminary.command.verified", id);
		}).orElse(0);
	}

	/** Minecraft identifiers and {@link LuminaryId} share one grammar, so the conversion cannot fail. */
	private static LuminaryId toLuminaryId(Identifier id) {
		return new LuminaryId(id.getNamespace(), id.getPath());
	}

	private static LuminaryId parseId(CommandContext<ServerCommandSource> ctx) {
		return toLuminaryId(IdentifierArgumentType.getIdentifier(ctx, "id"));
	}

	private static int report(ServerCommandSource src, Result result, String okKey, LuminaryId id) {
		if (result.ok()) {
			src.sendFeedback(() -> Text.translatable(okKey, id.toString()), true);
			return 1;
		}
		src.sendError(Text.translatable(result.reasonKey()));
		return 0;
	}

	private static UUID actorId(ServerCommandSource src) {
		ServerPlayerEntity p = src.getPlayer();
		return p != null ? p.getUuid() : CONSOLE;
	}

	private static Attribution attribution(ServerCommandSource src, ServerPlayerEntity player) {
		if (player != null) {
			BlockPos pos = player.getBlockPos();
			return new Attribution(player.getUuid(), player.getGameProfile().getName(), Instant.now(),
					player.getWorld().getRegistryKey().getValue().toString(), pos.asLong());
		}
		return new Attribution(CONSOLE, src.getName(), Instant.now(), src.getWorld().getRegistryKey().getValue().toString(),
				BlockPos.ofFloored(src.getPosition()).asLong());
	}
}
