package dev.luminary.core.events;

import dev.luminary.core.ids.LuminaryId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventBusTest {
	@Test
	void listenersRunInPriorityOrderAndFailuresAreIsolated() {
		List<String> log = new ArrayList<>();
		List<Throwable> errors = new ArrayList<>();
		EventBus bus = new EventBus((event, listener, error) -> errors.add(error));
		bus.subscribe(DomainEvent.class, 10, e -> log.add("late"));
		bus.subscribe(DomainEvent.DiscoveryObserved.class, 0, e -> { throw new IllegalStateException("boom"); });
		bus.subscribe(DomainEvent.DiscoveryObserved.class, 5, e -> log.add("mid"));
		bus.subscribe(DomainEvent.EvidenceAdded.class, 0, e -> log.add("never"));

		bus.publish(new DomainEvent.DiscoveryObserved(Instant.EPOCH, LuminaryId.of("x"), UUID.randomUUID()));

		assertEquals(List.of("mid", "late"), log);
		assertEquals(1, errors.size());
		assertEquals(1, bus.history().size());
	}
}
