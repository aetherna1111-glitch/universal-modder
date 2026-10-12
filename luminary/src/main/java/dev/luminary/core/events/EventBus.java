package dev.luminary.core.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Synchronous in-process event bus. Listeners run in ascending priority order on the publishing thread (the server
 * thread in production). A listener that throws is reported to {@link ErrorHandler} and does not stop the others.
 * <p>Deliberately not Fabric's {@code Event} so that core tests need no Fabric on the classpath.</p>
 */
public final class EventBus {
	/** Receives listener failures; production wires this to the mod logger. */
	public interface ErrorHandler {
		void onListenerError(DomainEvent event, Object listener, Throwable error);
	}

	private record Subscription<E extends DomainEvent>(Class<E> type, int priority, Consumer<E> listener) {}

	private final List<Subscription<?>> subscriptions = new ArrayList<>();
	private final ErrorHandler errorHandler;
	private final List<DomainEvent> published = new ArrayList<>();

	public EventBus(ErrorHandler errorHandler) {
		this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
	}

	/** Subscribes to {@code type} and its subtypes. Lower {@code priority} runs first. */
	public <E extends DomainEvent> void subscribe(Class<E> type, int priority, Consumer<E> listener) {
		subscriptions.add(new Subscription<>(type, priority, listener));
		subscriptions.sort(Comparator.comparingInt(Subscription::priority));
	}

	public <E extends DomainEvent> void subscribe(Class<E> type, Consumer<E> listener) {
		subscribe(type, 0, listener);
	}

	@SuppressWarnings("unchecked")
	public void publish(DomainEvent event) {
		Objects.requireNonNull(event, "event");
		published.add(event);
		for (Subscription<?> s : List.copyOf(subscriptions)) {
			if (!s.type().isInstance(event)) {
				continue;
			}
			try {
				((Consumer<DomainEvent>) s.listener()).accept(event);
			} catch (RuntimeException e) {
				errorHandler.onListenerError(event, s.listener(), e);
			}
		}
	}

	/** Events published so far, oldest first (useful for tests and the debug command). */
	public List<DomainEvent> history() {
		return List.copyOf(published);
	}
}
