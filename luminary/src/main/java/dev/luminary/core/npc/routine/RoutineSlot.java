package dev.luminary.core.npc.routine;

import dev.luminary.core.npc.presence.Activity;

import java.util.Objects;

/**
 * One block of the day: from {@code startTick} (inclusive) to {@code endTick} (exclusive) in Minecraft day time
 * (0..24000), do {@code activity} at the {@code place}. Slots may wrap midnight ({@code start > end}).
 */
public record RoutineSlot(int startTick, int endTick, Activity activity, PlaceKind place) {
	public RoutineSlot {
		if (startTick < 0 || startTick >= 24000 || endTick < 0 || endTick > 24000) {
			throw new IllegalArgumentException("slot ticks must be in 0..24000");
		}
		Objects.requireNonNull(activity);
		Objects.requireNonNull(place);
	}

	public boolean covers(long timeOfDay) {
		long t = Math.floorMod(timeOfDay, 24000L);
		return startTick <= endTick ? t >= startTick && t < endTick : t >= startTick || t < endTick;
	}
}
