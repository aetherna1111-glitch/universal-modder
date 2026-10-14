package dev.luminary.core.npc.routine;

import dev.luminary.core.npc.presence.Activity;

import java.util.List;
import java.util.Optional;

/**
 * An ordered day. The current slot is a pure function of the world's time of day, so an interruption (a
 * conversation, a task, a fight, an unloaded chunk) needs no bookkeeping: when the NPC is free again it simply
 * resumes whatever the clock says. Walking is always real pathfinding; a missing place means the slot is skipped.
 */
public record DailyRoutine(List<RoutineSlot> slots) {
	public DailyRoutine {
		slots = List.copyOf(slots);
	}

	/** Lumin's default day: study in the morning, write at midday, investigate in the afternoon, rest at night. */
	public static final DailyRoutine DEFAULT = new DailyRoutine(List.of(
			new RoutineSlot(0, 4000, Activity.READING, PlaceKind.STUDY),
			new RoutineSlot(4000, 7000, Activity.WRITING, PlaceKind.DESK),
			new RoutineSlot(7000, 11500, Activity.INVESTIGATING, PlaceKind.SITE),
			new RoutineSlot(11500, 13000, Activity.READING, PlaceKind.STUDY),
			new RoutineSlot(13000, 24000, Activity.RESTING, PlaceKind.HOME)));

	public Optional<RoutineSlot> at(long timeOfDay) {
		for (RoutineSlot s : slots) {
			if (s.covers(timeOfDay)) {
				return Optional.of(s);
			}
		}
		return Optional.empty();
	}
}
