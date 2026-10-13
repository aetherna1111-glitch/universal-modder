# luminary-vsfix

A tiny Fabric mod for the Luminary pack (Minecraft 1.20.1, Fabric Loader 0.19.5). It fixes disconnects with:

```
Internal Exception: java.lang.NullPointerException: Cannot invoke
"it.unimi.dsi.fastutil.longs.LongAVLTreeSet$Entry.decBalance()" because "p" is null
```

## Cause

Valkyrien Skies 2.4.11 registers its `valkyrienskies:vs_packet` receivers with Fabric's legacy `PlayChannelHandler`.
Fabric calls those receivers on the Netty I/O thread, and VS runs its packet handlers right there.

The ship-motion handlers call `Entity.setPos`. That moves entities between sections of the world's entity section
storage (`class_5573`, a fastutil `LongAVLTreeSet`) while the game thread changes the same set. The tree breaks
and the connection drops.

VS 2.4.11 is the newest 1.20.1 Fabric build, and no VS setting changes this.

## Fix

- The mod hooks the start of both receivers: `VSFabricNetworking.register$lambda$1` on the server and
  `registerClientNetworking$lambda$0` on the client.
- It copies the payload, because Fabric frees its buffer once the receiver returns.
- It runs the receiver again through the server's or client's executor, which is what vanilla does for every
  packet. Packets keep their arrival order.
- A mixin plugin applies it only to VS 2.4.11, whose Kotlin lambda names it targets.
- Install it on the server and on clients.

## Build and test

```sh
python3 build.py --mc-jar <server-intermediary.jar> --libs <fabric server libraries dir> \
                 --vs-jar <valkyrienskies-120-2.4.11.jar> --fabric-api-jar <fabric-api-0.92.12+1.20.1.jar> --out build
```

`test/` is a server-only harness that is never shipped. It calls VS's server receiver from a non-game thread and
prints the thread on which VS's hooks saw the packet. Build it with `--src test --name luminary-vsfix-test.jar`.

On a Fabric 1.20.1 server with VS 2.4.11:

| | Thread that handled the packet | Payload after the sender's buffer was overwritten |
|---|---|---|
| Without the fix | `Netty Server IO #test` | read on that thread |
| With the fix | `Server thread` | still `[1, 2, 3, 4, 5]` |

The client path was not run, because no client was available. The exported transformed class shows the same
injection there.
