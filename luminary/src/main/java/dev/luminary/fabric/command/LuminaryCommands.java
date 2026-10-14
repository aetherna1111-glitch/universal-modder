package dev.luminary.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import dev.luminary.core.npc.llm.LlmMessage;
import dev.luminary.core.npc.llm.LlmRequest;
import dev.luminary.core.npc.memory.MemoryEntry;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.core.npc.memory.MemoryStore;
import dev.luminary.core.npc.memory.Provenance;
import dev.luminary.core.npc.task.NpcAction;
import dev.luminary.core.npc.task.NpcTask;
import net.minecraft.command.argument.BlockPosArgumentType;
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
 * /luminary npc container allow|deny &lt;npc&gt; &lt;pos&gt;       (op 3)  authorize a deposit container (persisted)
 * /luminary npc task gather &lt;npc&gt; &lt;log pos&gt; &lt;container pos&gt; [maxLogs]   (op 3)  validated gather-wood task
 * /luminary npc task status &lt;npc&gt; | cancel &lt;npc&gt;        (op 2 / op 3)
 * /luminary npc memory &lt;npc&gt; [player]                 (op 2)  what the NPC remembers (optionally about one player)
 * /luminary npc memory note &lt;npc&gt; &lt;text...&gt;            (op 3)  an authored stable fact the NPC knows (teaching role)
 * /luminary npc behaviour &lt;npc&gt;                        (op 2)  combat state, knockout, weapon custody, routine, plan
 * /luminary npc relations &lt;npc&gt;                        (op 2)  karma / trust / threat per player
 * /luminary npc revive &lt;npc&gt;                           (op 3)  end a knockout now
 * /luminary npc weapon &lt;npc&gt; [reclaim | transfer &lt;player&gt; &lt;reason&gt;]   (op 2 / op 4)  the single permanent-transfer point
 * /luminary npc place &lt;npc&gt; &lt;study|desk|site|home|workshop&gt; &lt;pos&gt; | clear &lt;kind&gt;   (op 3)  routine places
 * /luminary npc routine &lt;npc&gt; on|off                   (op 3)
 * /luminary npc plan status|cancel &lt;npc&gt;              (op 2 / op 3)
 * /luminary npc plan stock &lt;npc&gt; &lt;tree&gt; &lt;container&gt; &lt;logs&gt; | smelt &lt;npc&gt; &lt;container&gt; &lt;furnace&gt; &lt;item&gt; &lt;count&gt;   (op 3)
 * /luminary metrics                                    (op 2)  counters and latencies
 * /luminary llm status                                 (op 2)  provider, model, health, counters
 * /luminary llm ping                                   (op 4)  one tiny request to the configured model; answer arrives asynchronously
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
						.then(CommandManager.literal("autonomy")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.literal("server")
										.requires(src -> src.hasPermissionLevel(4))
										.then(CommandManager.literal("on").executes(ctx -> autonomyServer(ctx, true)))
										.then(CommandManager.literal("off").executes(ctx -> autonomyServer(ctx, false))))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(LuminaryCommands::autonomyStatus)
										.then(CommandManager.literal("on").executes(ctx -> autonomyNpc(ctx, true)))
										.then(CommandManager.literal("off").executes(ctx -> autonomyNpc(ctx, false)))))
						.then(CommandManager.literal("order")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.literal("stock_wood")
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("container", BlockPosArgumentType.blockPos())
														.then(CommandManager.argument("minLogs", IntegerArgumentType.integer(1, 1728))
																.executes(LuminaryCommands::orderStockWood)))))
								.then(CommandManager.literal("clear")
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::orderClear))))
						.then(CommandManager.literal("gear")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcGear)))
						.then(CommandManager.literal("planner")
								.requires(src -> src.hasPermissionLevel(4))
								.then(CommandManager.argument("planner", com.mojang.brigadier.arguments.StringArgumentType.word())
										.executes(LuminaryCommands::npcPlanner)))
						.then(CommandManager.literal("intent")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.then(CommandManager.argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
												.executes(LuminaryCommands::npcIntent))))
						.then(CommandManager.literal("provider")
								.requires(src -> src.hasPermissionLevel(4))
								.then(CommandManager.argument("provider", com.mojang.brigadier.arguments.StringArgumentType.word())
										.executes(LuminaryCommands::npcProvider)))
						.then(CommandManager.literal("container")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.literal("allow")
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(ctx -> container(ctx, true)))))
								.then(CommandManager.literal("deny")
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(ctx -> container(ctx, false))))))
						.then(CommandManager.literal("memory")
								.then(CommandManager.literal("note")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("text", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
														.executes(LuminaryCommands::memoryNote))))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(ctx -> memoryList(ctx, null))
										.then(CommandManager.argument("player", EntityArgumentType.player())
												.executes(ctx -> memoryList(ctx, EntityArgumentType.getPlayer(ctx, "player"))))))
						.then(CommandManager.literal("task")
								.then(CommandManager.literal("status")
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::taskStatus)))
								.then(CommandManager.literal("cancel")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::taskCancel)))
								.then(CommandManager.literal("move")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("target", BlockPosArgumentType.blockPos()).executes(LuminaryCommands::taskMove))))
								.then(CommandManager.literal("deposit")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("container", BlockPosArgumentType.blockPos()).executes(LuminaryCommands::taskDeposit))))
								.then(CommandManager.literal("withdraw")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("container", BlockPosArgumentType.blockPos())
														.then(CommandManager.argument("item", IdentifierArgumentType.identifier())
																.executes(ctx -> taskWithdraw(ctx, 1))
																.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
																		.executes(ctx -> taskWithdraw(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))))
								.then(CommandManager.literal("smelt")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("furnace", BlockPosArgumentType.blockPos())
														.then(CommandManager.argument("container", BlockPosArgumentType.blockPos())
																.then(CommandManager.argument("item", IdentifierArgumentType.identifier())
																		.executes(ctx -> taskSmelt(ctx, 8))
																		.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
																				.executes(ctx -> taskSmelt(ctx, IntegerArgumentType.getInteger(ctx, "count")))))))))
								.then(CommandManager.literal("gather")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("target", BlockPosArgumentType.blockPos())
														.then(CommandManager.argument("container", BlockPosArgumentType.blockPos())
																.executes(ctx -> taskGather(ctx, 4))
																.then(CommandManager.argument("maxLogs", IntegerArgumentType.integer(1))
																		.executes(ctx -> taskGather(ctx, IntegerArgumentType.getInteger(ctx, "maxLogs")))))))))
						.then(CommandManager.literal("behaviour")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcBehaviour)))
						.then(CommandManager.literal("relations")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcRelations)))
						.then(CommandManager.literal("revive")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcRevive)))
						.then(CommandManager.literal("weapon")
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(LuminaryCommands::npcBehaviour)
										.then(CommandManager.literal("reclaim")
												.requires(src -> src.hasPermissionLevel(4))
												.executes(LuminaryCommands::weaponReclaim))
										.then(CommandManager.literal("transfer")
												.requires(src -> src.hasPermissionLevel(4))
												.then(CommandManager.argument("player", EntityArgumentType.player())
														.then(CommandManager.argument("reason", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
																.executes(LuminaryCommands::weaponTransfer))))))
						.then(CommandManager.literal("place")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.then(CommandManager.literal("clear")
												.then(CommandManager.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
														.executes(LuminaryCommands::placeClear)))
										.then(CommandManager.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
												.then(CommandManager.argument("pos", BlockPosArgumentType.blockPos()).executes(LuminaryCommands::placeSet)))))
						.then(CommandManager.literal("routine")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.then(CommandManager.literal("on").executes(ctx -> routine(ctx, true)))
										.then(CommandManager.literal("off").executes(ctx -> routine(ctx, false)))))
						.then(CommandManager.literal("plan")
								.then(CommandManager.literal("status")
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::npcBehaviour)))
								.then(CommandManager.literal("cancel")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier()).executes(LuminaryCommands::planCancel)))
								.then(CommandManager.literal("stock")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("target", BlockPosArgumentType.blockPos())
														.then(CommandManager.argument("container", BlockPosArgumentType.blockPos())
																.then(CommandManager.argument("logs", IntegerArgumentType.integer(1))
																		.executes(LuminaryCommands::planStock))))))
								.then(CommandManager.literal("smelt")
										.requires(src -> src.hasPermissionLevel(3))
										.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
												.then(CommandManager.argument("container", BlockPosArgumentType.blockPos())
														.then(CommandManager.argument("furnace", BlockPosArgumentType.blockPos())
																.then(CommandManager.argument("item", IdentifierArgumentType.identifier())
																		.then(CommandManager.argument("count", IntegerArgumentType.integer(1))
																				.executes(LuminaryCommands::planSmelt)))))))))
				.then(CommandManager.literal("metrics").executes(LuminaryCommands::metrics))
				.then(CommandManager.literal("llm")
						.then(CommandManager.literal("status").executes(LuminaryCommands::llmStatus))
						.then(CommandManager.literal("ping")
								.requires(src -> src.hasPermissionLevel(4))
								.executes(LuminaryCommands::llmPing))));
	}

	private static int llmStatus(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			var p = rt.llm().provider();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.llm_status", p.id(), p.model(),
					rt.llm().config().endpoint().toString(), p.healthy() ? "healthy" : "unavailable", p.stats().toString()), false);
			return 1;
		}).orElse(0);
	}

	private static int llmPing(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			ServerCommandSource src = ctx.getSource();
			LlmRequest request = LlmRequest.text(java.util.List.of(
					LlmMessage.system("You are a connectivity check. Reply with the single word: pong"),
					LlmMessage.user("ping")), 8, 0.0);
			src.sendFeedback(() -> Text.translatable("luminary.command.llm_ping_sent", rt.llm().provider().id()), false);
			rt.llm().ask(request, "ping", (response, error) -> {
				if (response != null) {
					src.sendFeedback(() -> Text.translatable("luminary.command.llm_ping_ok", response.latencyMillis(), response.text().strip()), false);
				} else {
					src.sendError(Text.translatable("luminary.command.llm_ping_failed", error.kind().name(), String.valueOf(error.getMessage())));
				}
			});
			return 1;
		}).orElse(0);
	}

	private static int container(CommandContext<ServerCommandSource> ctx, boolean allow) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(ctx, "pos");
		return runtime(ctx.getSource()).map(rt -> {
			if (allow) {
				rt.npc().authorizeContainer(id, pos.asLong());
				ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.container_allowed", id.toString(), pos.toShortString()), true);
			} else {
				rt.npc().revokeContainer(id, pos.asLong());
				ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.container_denied", id.toString(), pos.toShortString()), true);
			}
			return 1;
		}).orElse(0);
	}

	private static int taskGather(CommandContext<ServerCommandSource> ctx, int maxLogs) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos target = BlockPosArgumentType.getLoadedBlockPos(ctx, "target");
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		return runtime(ctx.getSource()).map(rt -> {
			Result r = rt.npc().assign(id, new NpcAction.GatherWood(target.asLong(), container.asLong(), maxLogs));
			return report(ctx.getSource(), r, "luminary.command.task_assigned", id);
		}).orElse(0);
	}

	private static int taskMove(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos target = BlockPosArgumentType.getLoadedBlockPos(ctx, "target");
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().assign(id, new NpcAction.MoveTo(target.asLong())), "luminary.command.task_assigned", id)).orElse(0);
	}

	private static int taskDeposit(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().assign(id, new NpcAction.Deposit(container.asLong())), "luminary.command.task_assigned", id)).orElse(0);
	}

	private static int taskWithdraw(CommandContext<ServerCommandSource> ctx, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().assign(id, new NpcAction.Withdraw(container.asLong(), item, count)), "luminary.command.task_assigned", id)).orElse(0);
	}

	private static int autonomyServer(CommandContext<ServerCommandSource> ctx, boolean on) {
		return runtime(ctx.getSource()).map(rt -> {
			rt.setAutonomyEnabled(on);
			ctx.getSource().sendFeedback(() -> Text.translatable(on ? "luminary.command.autonomy_server_on" : "luminary.command.autonomy_server_off"), true);
			return 1;
		}).orElse(0);
	}

	private static int autonomyNpc(CommandContext<ServerCommandSource> ctx, boolean on) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			if (rt.npc().dialogue().definitions().npc(id).isEmpty()) {
				ctx.getSource().sendError(Text.translatable(NpcService.REASON_UNKNOWN_NPC));
				return 0;
			}
			rt.npc().roster().ensureRecord(id).autonomy().setEnabled(on);
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable(on ? "luminary.command.autonomy_on" : "luminary.command.autonomy_off", id.toString()), true);
			if (on && !rt.npc().autonomyEnabled()) {
				ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.autonomy_server_is_off"), false);
			}
			return 1;
		}).orElse(0);
	}

	private static int autonomyStatus(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			var auto = rt.npc().roster().ensureRecord(id).autonomy();
			var rules = rt.npc().autonomy().rules();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.autonomy_status", id.toString(),
					rt.npc().autonomyEnabled() ? "on" : "off", auto.enabled() ? "on" : "off",
					auto.decisionsWithin(rules.window(), Instant.now()), rules.maxDecisionsPerWindow(), auto.orders().size(), auto.avoidUntil().size()), false);
			for (var order : auto.orders()) {
				String text = order instanceof dev.luminary.core.npc.autonomy.StandingOrder.StockWood w
						? "stock_wood: keep >= " + w.minLogs() + " logs in " + BlockPos.fromLong(w.containerPos()).toShortString() : order.type();
				ctx.getSource().sendFeedback(() -> Text.literal(" - " + text), false);
			}
			return 1;
		}).orElse(0);
	}

	private static int orderStockWood(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		int min = IntegerArgumentType.getInteger(ctx, "minLogs");
		return runtime(ctx.getSource()).map(rt -> {
			if (rt.npc().dialogue().definitions().npc(id).isEmpty()) {
				ctx.getSource().sendError(Text.translatable(NpcService.REASON_UNKNOWN_NPC));
				return 0;
			}
			if (!rt.npc().roster().ensureRecord(id).authorizedContainers().contains(container.asLong())) {
				ctx.getSource().sendError(Text.translatable(dev.luminary.core.npc.task.ActionValidator.REASON_CONTAINER_UNAUTHORIZED));
				return 0;
			}
			rt.npc().roster().ensureRecord(id).autonomy().addOrder(new dev.luminary.core.npc.autonomy.StandingOrder.StockWood(container.asLong(), min));
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.order_added", id.toString()), true);
			return 1;
		}).orElse(0);
	}

	private static int orderClear(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			rt.npc().roster().ensureRecord(id).autonomy().clearOrders();
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.order_cleared", id.toString()), true);
			return 1;
		}).orElse(0);
	}

	private static int taskSmelt(CommandContext<ServerCommandSource> ctx, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos furnace = BlockPosArgumentType.getLoadedBlockPos(ctx, "furnace");
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().assign(id, new NpcAction.Smelt(furnace.asLong(), container.asLong(), item, count)), "luminary.command.task_assigned", id)).orElse(0);
	}

	private static int npcGear(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			Optional<LuminaryNpcEntity> body = rt.npc().body(id);
			if (body.isEmpty()) {
				ctx.getSource().sendError(Text.translatable(NpcService.REASON_NOT_PRESENT));
				return 0;
			}
			ctx.getSource().sendFeedback(() -> Text.literal(id + ": " + dev.luminary.fabric.npc.GearManager.describe(body.get())), false);
			return 1;
		}).orElse(0);
	}

	private static int npcPlanner(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "planner");
			Optional<dev.luminary.core.npc.intent.IntentPlanner> planner = rt.plannerFor(id);
			if (planner.isEmpty()) {
				ctx.getSource().sendError(Text.translatable("luminary.command.npc_unknown_planner", id));
				return 0;
			}
			rt.setIntentPlanner(planner.get());
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.npc_planner_set", id), true);
			return 1;
		}).orElse(0);
	}

	/** Says something to an NPC from anywhere (no distance rule): the same path a nearby chat line takes. */
	private static int npcIntent(CommandContext<ServerCommandSource> ctx) {
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
			rt.npc().request(body.get(), player, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "text"));
			return 1;
		}).orElse(0);
	}

	private static int memoryList(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			MemoryStore store = rt.npc().dialogue().memory(id);
			java.util.List<MemoryEntry> entries = player == null ? store.all() : store.forPlayer(player.getUuid());
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.memory_count", id.toString(), entries.size(), store.size()), false);
			entries.stream().sorted(java.util.Comparator.comparing(MemoryEntry::at).reversed()).limit(30).forEach(e ->
					ctx.getSource().sendFeedback(() -> Text.literal(" - [" + e.kind() + "/" + e.provenance() + "] " + e.at().toString().substring(0, 16) + " " + e.text()), false));
			return entries.size();
		}).orElse(0);
	}

	private static int memoryNote(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			if (rt.npc().dialogue().definitions().npc(id).isEmpty()) {
				ctx.getSource().sendError(Text.translatable(NpcService.REASON_UNKNOWN_NPC));
				return 0;
			}
			String text = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "text");
			MemoryStore store = rt.npc().dialogue().memory(id);
			store.add(MemoryEntry.of(MemoryKind.STABLE_FACT, null, null, text, Instant.now(), Provenance.AUTHORED), Instant.now());
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.memory_noted", id.toString()), true);
			return 1;
		}).orElse(0);
	}

	private static int taskStatus(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			Optional<NpcTask> task = rt.npc().roster().get(id).flatMap(NpcRoster.NpcRecord::task);
			if (task.isEmpty()) {
				ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.task_none", id.toString()), false);
				return 0;
			}
			NpcTask t = task.get();
			ctx.getSource().sendFeedback(() -> Text.literal(t.action().type() + " " + t.state() + t.failureReason().map(f -> " (" + f + ")").orElse("")
					+ "  broken=" + t.ledger().blocksBroken() + " collected=" + t.ledger().collected() + " deposited=" + t.ledger().deposited()
					+ (t.ledger().withdrawn().isEmpty() ? "" : " withdrawn=" + t.ledger().withdrawn()) + (t.ledger().smelted().isEmpty() ? "" : " smelted=" + t.ledger().smelted()) + t.requestedBy().map(u -> " requestedBy=" + u).orElse("")
					+ "  containers=" + rt.npc().roster().get(id).map(r -> r.authorizedContainers().size()).orElse(0)), false);
			return 1;
		}).orElse(0);
	}

	private static int taskCancel(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			return report(ctx.getSource(), rt.npc().cancelTask(id), "luminary.command.task_cancelled", id);
		}).orElse(0);
	}

	private static int npcList(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			NpcService npc = rt.npc();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.npc_provider_is", npc.dialogue().provider().id()), false);
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.npc_planner_is", npc.planner().id()), false);
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
			rt.npc().talk(body.get(), player);
			return 1;
		}).orElse(0);
	}

	private static int npcProvider(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "provider");
			Optional<DialogueProvider> provider = rt.providerFor(id);
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


	// ------------------------------------------------------------------ slice 15: behaviour, relations, weapon, routine, plans

	private static int npcBehaviour(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			if (rt.npc().dialogue().definitions().npc(id).isEmpty()) {
				ctx.getSource().sendError(Text.translatable(NpcService.REASON_UNKNOWN_NPC));
				return 0;
			}
			ctx.getSource().sendFeedback(() -> Text.literal(id + ": " + rt.npc().behaviour().describe(id)), false);
			return 1;
		}).orElse(0);
	}

	private static int npcRelations(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			LuminaryId id = parseId(ctx);
			var record = rt.npc().roster().ensureRecord(id);
			var rules = rt.npc().roster().rules().relationship();
			Instant now = rt.npc().now();
			ctx.getSource().sendFeedback(() -> Text.literal(id + ": " + record.relationships().all().size() + " players known"), false);
			record.relationships().all().forEach((player, r) -> {
				var d = r.decayed(now, rules);
				String name = record.player(player).map(NpcRoster.PlayerMemory::nameAtLastMeeting).orElse(player.toString());
				ctx.getSource().sendFeedback(() -> Text.literal(String.format(" - %s: %s karma %.0f trust %.0f threat %.0f%s", name, d.standing(rules), d.karma(), d.trust(), d.threat(),
						d.lastEvent().map(e -> " last " + e).orElse(""))), false);
			});
			return 1;
		}).orElse(0);
	}

	private static int npcRevive(CommandContext<ServerCommandSource> ctx) {
		LuminaryId id = parseId(ctx);
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().behaviour().revive(id), "luminary.command.revived", id)).orElse(0);
	}

	private static int weaponReclaim(CommandContext<ServerCommandSource> ctx) {
		LuminaryId id = parseId(ctx);
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().behaviour().reclaimWeapon(id), "luminary.command.weapon_reclaimed", id)).orElse(0);
	}

	private static int weaponTransfer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
		String reason = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "reason");
		return runtime(ctx.getSource()).map(rt -> {
			Result r = rt.npc().behaviour().transferWeapon(id, player, "command:" + ctx.getSource().getName() + ": " + reason);
			if (!r.ok()) {
				ctx.getSource().sendError(Text.translatable(r.reasonKey()));
				return 0;
			}
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.weapon_transferred", id.toString(), player.getGameProfile().getName(), reason), true);
			return 1;
		}).orElse(0);
	}

	private static Optional<dev.luminary.core.npc.routine.PlaceKind> placeKind(CommandContext<ServerCommandSource> ctx) {
		String kind = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "kind").toUpperCase(java.util.Locale.ROOT);
		try {
			return Optional.of(dev.luminary.core.npc.routine.PlaceKind.valueOf(kind));
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendError(Text.literal("unknown place kind " + kind + "; one of " + java.util.Arrays.toString(dev.luminary.core.npc.routine.PlaceKind.values())));
			return Optional.empty();
		}
	}

	private static int placeSet(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(ctx, "pos");
		return runtime(ctx.getSource()).map(rt -> placeKind(ctx).map(kind -> {
			rt.npc().roster().ensureRecord(id).routine().setPlace(kind, pos.asLong());
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.place_set", id.toString(), kind.name(), pos.toShortString()), true);
			return 1;
		}).orElse(0)).orElse(0);
	}

	private static int placeClear(CommandContext<ServerCommandSource> ctx) {
		LuminaryId id = parseId(ctx);
		return runtime(ctx.getSource()).map(rt -> placeKind(ctx).map(kind -> {
			rt.npc().roster().ensureRecord(id).routine().clearPlace(kind);
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable("luminary.command.place_cleared", id.toString(), kind.name()), true);
			return 1;
		}).orElse(0)).orElse(0);
	}

	private static int routine(CommandContext<ServerCommandSource> ctx, boolean on) {
		LuminaryId id = parseId(ctx);
		return runtime(ctx.getSource()).map(rt -> {
			rt.npc().roster().ensureRecord(id).routine().setEnabled(on);
			rt.npc().markDirty();
			ctx.getSource().sendFeedback(() -> Text.translatable(on ? "luminary.command.routine_on" : "luminary.command.routine_off", id.toString()), true);
			return 1;
		}).orElse(0);
	}

	private static int planCancel(CommandContext<ServerCommandSource> ctx) {
		LuminaryId id = parseId(ctx);
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().behaviour().cancelPlan(id), "luminary.command.plan_cancelled", id)).orElse(0);
	}

	private static int planStock(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos target = BlockPosArgumentType.getLoadedBlockPos(ctx, "target");
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		int logs = IntegerArgumentType.getInteger(ctx, "logs");
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().behaviour().startPlan(id,
				dev.luminary.core.npc.plan.PlanTemplates.stockWood(target.asLong(), container.asLong(), logs, rt.npc().now(), ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getUuid() : null)),
				"luminary.command.plan_started", id)).orElse(0);
	}

	private static int planSmelt(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		LuminaryId id = parseId(ctx);
		BlockPos container = BlockPosArgumentType.getLoadedBlockPos(ctx, "container");
		BlockPos furnace = BlockPosArgumentType.getLoadedBlockPos(ctx, "furnace");
		String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
		int count = IntegerArgumentType.getInteger(ctx, "count");
		return runtime(ctx.getSource()).map(rt -> report(ctx.getSource(), rt.npc().behaviour().startPlan(id,
				dev.luminary.core.npc.plan.PlanTemplates.smeltFromStock(container.asLong(), furnace.asLong(), item, count, rt.npc().now(), ctx.getSource().getPlayer() != null ? ctx.getSource().getPlayer().getUuid() : null)),
				"luminary.command.plan_started", id)).orElse(0);
	}

	private static int metrics(CommandContext<ServerCommandSource> ctx) {
		return runtime(ctx.getSource()).map(rt -> {
			var m = rt.npc().behaviour().metrics();
			ctx.getSource().sendFeedback(() -> Text.literal("luminary metrics: " + m.counters().size() + " counters, " + m.latencies().size() + " latencies"), false);
			m.counters().forEach((k, v) -> ctx.getSource().sendFeedback(() -> Text.literal(" - " + k + " = " + v), false));
			m.latencies().forEach((k, v) -> ctx.getSource().sendFeedback(() -> Text.literal(" - " + k + ": n=" + v.count() + " mean=" + v.meanMillis() + "ms max=" + v.maxMillis() + "ms"), false));
			rt.npc().roster().all().forEach((id, r) -> {
				if (!r.capabilities().refused().isEmpty()) {
					ctx.getSource().sendFeedback(() -> Text.literal(" - " + id + " model intents refused: " + r.capabilities().refused()), false);
				}
			});
			return 1;
		}).orElse(0);
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
