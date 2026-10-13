package luminary.vsfix.mixin;

import java.util.concurrent.Executor;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.class_2540;
import net.minecraft.class_310;
import net.minecraft.class_3222;
import net.minecraft.class_3244;
import net.minecraft.class_634;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.internal.hooks.VsiCoreHooksIn;
import org.valkyrienskies.mod.fabric.common.VSFabricNetworking;

/**
 * Valkyrien Skies 2.4.11 registers its "valkyrienskies:vs_packet" receivers with Fabric's legacy
 * PlayChannelHandler, which Fabric calls on the Netty I/O thread, and VS runs the packet handlers right there.
 * The ship-motion handlers call Entity.setPos, which moves the entity between sections of the world's entity
 * section storage (class_5573, a fastutil LongAVLTreeSet) while the game thread changes the same set, so the
 * tree breaks and the connection drops with "Internal Exception: NullPointerException ... LongAVLTreeSet$Entry
 * .decBalance() because "p" is null".
 *
 * Vanilla handles every packet on the game thread; this does the same for VS: the receiver copies the payload
 * (Fabric frees its buffer once the receiver returns) and re-runs itself through the server's or client's
 * executor, which keeps the packets in arrival order.
 */
@Mixin(value = VSFabricNetworking.class, remap = false)
public abstract class VSFabricNetworkingMixin {
    @Unique
    private static final Logger luminary$LOG = LoggerFactory.getLogger("luminary-vsfix");
    @Unique
    private static final ThreadLocal<Boolean> luminary$DEFERRED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    private static void register$lambda$1(VsiCoreHooksIn hooks, MinecraftServer server, class_3222 player,
                                          class_3244 handler, class_2540 buf, PacketSender sender) {
        throw new AssertionError();
    }

    @Shadow
    private static void registerClientNetworking$lambda$0(VsiCoreHooksIn hooks, class_310 client, class_634 handler,
                                                          class_2540 buf, PacketSender sender) {
        throw new AssertionError();
    }

    @Inject(method = "register$lambda$1", at = @At("HEAD"), cancellable = true)
    private static void luminary$serverOnGameThread(VsiCoreHooksIn hooks, MinecraftServer server, class_3222 player,
                                                    class_3244 handler, class_2540 buf, PacketSender sender,
                                                    CallbackInfo ci) {
        if (luminary$DEFERRED.get()) {
            return; // already re-run on the game thread: let VS handle it
        }
        class_2540 copy = new class_2540(Unpooled.copiedBuffer(buf));
        ((Executor) server).execute(() -> {
            luminary$DEFERRED.set(Boolean.TRUE);
            try {
                register$lambda$1(hooks, server, player, handler, copy, sender);
            } catch (RuntimeException e) {
                luminary$LOG.error("Valkyrien Skies packet from a client failed", e);
            } finally {
                luminary$DEFERRED.set(Boolean.FALSE);
            }
        });
        ci.cancel();
    }

    @Inject(method = "registerClientNetworking$lambda$0", at = @At("HEAD"), cancellable = true)
    private static void luminary$clientOnGameThread(VsiCoreHooksIn hooks, class_310 client, class_634 handler,
                                                    class_2540 buf, PacketSender sender, CallbackInfo ci) {
        if (luminary$DEFERRED.get()) {
            return;
        }
        class_2540 copy = new class_2540(Unpooled.copiedBuffer(buf));
        ((Executor) (Object) client).execute(() -> {
            luminary$DEFERRED.set(Boolean.TRUE);
            try {
                registerClientNetworking$lambda$0(hooks, client, handler, copy, sender);
            } catch (RuntimeException e) {
                luminary$LOG.error("Valkyrien Skies packet from the server failed", e);
            } finally {
                luminary$DEFERRED.set(Boolean.FALSE);
            }
        });
        ci.cancel();
    }
}
