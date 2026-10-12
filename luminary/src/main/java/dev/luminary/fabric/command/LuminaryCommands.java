package dev.luminary.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.luminary.core.catalog.Attribution;
import dev.luminary.core.catalog.CatalogService;
import dev.luminary.core.catalog.DiscoveryRecord;
import dev.luminary.core.catalog.DiscoveryTemplate;
import dev.luminary.core.catalog.Evidence;
import dev.luminary.core.catalog.Result;
import dev.luminary.core.ids.LuminaryId;
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
												.then(CommandManager.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
														.executes(LuminaryCommands::sample)))))
						.then(CommandManager.literal("verify")
								.requires(src -> src.hasPermissionLevel(3))
								.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
										.executes(ctx -> verify(ctx, false))
										.then(CommandManager.literal("force")
												.requires(src -> src.hasPermissionLevel(4))
												.executes(ctx -> verify(ctx, true)))))));
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
			int count = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count");
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
