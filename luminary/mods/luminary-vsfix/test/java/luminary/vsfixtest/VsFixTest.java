package luminary.vsfixtest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.class_2540;
import net.minecraft.class_3218;
import net.minecraft.class_3222;
import net.minecraft.class_3244;
import net.minecraft.server.MinecraftServer;
import org.valkyrienskies.core.internal.hooks.VsiCoreHooksIn;
import org.valkyrienskies.mod.fabric.common.VSFabricNetworking;

/**
 * Local test only (not shipped): calls VS's server receiver the way Fabric does, from a non-game thread, and
 * reports on which thread VS's hooks saw the packet and whether the payload survived the sender reusing its buffer.
 */
public final class VsFixTest implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> new Thread(() -> run(server), "Netty Server IO #test").start());
    }

    private static void run(MinecraftServer server) {
        try {
            class_3218 overworld = null;
            for (Method m : MinecraftServer.class.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == class_3218.class) {
                    overworld = (class_3218) m.invoke(server);
                    break;
                }
            }
            class_3218 world = overworld;
            FakePlayer player = server.method_5385(() -> FakePlayer.get(world)).join(); // submit(Supplier) on the game thread
            VsiCoreHooksIn hooks = (VsiCoreHooksIn) Proxy.newProxyInstance(VsiCoreHooksIn.class.getClassLoader(),
                    new Class<?>[]{VsiCoreHooksIn.class}, (proxy, method, args) -> {
                        if (method.getName().equals("onReceiveServer")) {
                            ByteBuf data = (ByteBuf) args[0];
                            byte[] got = new byte[data.readableBytes()];
                            data.getBytes(data.readerIndex(), got);
                            System.out.println("VSFIX-TEST onReceiveServer thread=" + Thread.currentThread().getName()
                                    + " payload=" + Arrays.toString(got));
                        }
                        return null;
                    });
            // a method handle resolves only this method; getDeclaredMethod would load every signature in the class,
            // including client classes a dedicated server does not have
            MethodHandle lambda = MethodHandles.privateLookupIn(VSFabricNetworking.class, MethodHandles.lookup())
                    .findStatic(VSFabricNetworking.class, "register$lambda$1", MethodType.methodType(void.class,
                            VsiCoreHooksIn.class, MinecraftServer.class, class_3222.class, class_3244.class,
                            class_2540.class, PacketSender.class));
            byte[] backing = {1, 2, 3, 4, 5};
            class_2540 buf = new class_2540(Unpooled.wrappedBuffer(backing));
            System.out.println("VSFIX-TEST calling the VS receiver from thread=" + Thread.currentThread().getName());
            lambda.invoke(hooks, server, (class_3222) player, (class_3244) null, buf, (PacketSender) null);
            Arrays.fill(backing, (byte) 0); // what a reused/released Netty buffer looks like after the receiver returns
            System.out.println("VSFIX-TEST receiver returned; original buffer overwritten");
        } catch (Throwable t) {
            System.out.println("VSFIX-TEST failed: " + t);
            t.printStackTrace(System.out);
        }
    }
}
