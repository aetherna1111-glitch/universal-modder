package dev.luminary.fabric.integration;

import dev.luminary.core.integration.ThaumcraftBridge;
import dev.luminary.core.npc.memory.MemoryKind;
import dev.luminary.fabric.Luminary;
import dev.luminary.fabric.LuminaryRuntime;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Fabric entrypoint {@code thaumcraft-observer} declared in {@code fabric.mod.json}. The Thaumcraft port calls it
 * with plain maps (no shared classes in either direction), on the server thread, after it recorded the change.
 * Luminary turns each event into progression signals and an observation every NPC remembers.
 */
public final class ThaumcraftObserverEntrypoint implements Consumer<Map<String, Object>> {
	@Override
	public void accept(Map<String, Object> event) {
		Optional<LuminaryRuntime> rt = LuminaryRuntime.get();
		if (rt.isEmpty()) {
			return;
		}
		if (!rt.get().server().isOnThread()) {
			rt.get().server().execute(() -> apply(rt.get(), event));
		} else {
			apply(rt.get(), event);
		}
	}

	/** Applies one event; returns the translated observation when the event was understood. */
	public static Optional<ThaumcraftBridge.Observation> apply(LuminaryRuntime rt, Map<String, Object> event) {
		Optional<ThaumcraftBridge.Observation> translated = ThaumcraftBridge.translate(event);
		if (translated.isEmpty()) {
			Luminary.LOGGER.debug("[luminary] ignored thaumcraft event {}", event == null ? null : event.get("type"));
			return translated;
		}
		ThaumcraftBridge.Observation o = translated.get();
		for (ThaumcraftBridge.SignalDelta d : o.signals()) {
			long current = rt.progression().state().signal(d.signal());
			long next = d.mode() == ThaumcraftBridge.Mode.INCREMENT ? current + d.value() : Math.max(current, d.value());
			rt.progression().setSignal(d.signal(), next, ThaumcraftBridge.SOURCE);
		}
		rt.npc().observeForAll(MemoryKind.OBSERVATION, o.memory().player(), o.memory().topic(), o.memory().text());
		return translated;
	}
}
