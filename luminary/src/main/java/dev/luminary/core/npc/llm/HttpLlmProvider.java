package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Shared HTTP machinery for model backends.
 * <ul>
 *   <li>Own daemon threads: the game thread only submits and receives a future.</li>
 *   <li>Connect and total timeouts; no redirects; response body capped at {@code maxResponseBytes} by a subscriber
 *       that cancels the transfer when the cap is exceeded (no blocking reads on client threads).</li>
 *   <li>At most {@code maxConcurrent} in-flight requests; extra requests fail fast with {@code BUSY}.</li>
 *   <li>Exponential cooldown after transport failures (5 s doubling to 5 min): while cooling down, requests fail
 *       immediately with {@code UNAVAILABLE} so callers fall back without waiting.</li>
 *   <li>Endpoint must be loopback or on the allow-list.</li>
 * </ul>
 * Subclasses only build the request body and parse the response body.
 */
public abstract class HttpLlmProvider implements LlmProvider {
	private static final Duration COOLDOWN_MIN = Duration.ofSeconds(5);
	private static final Duration COOLDOWN_MAX = Duration.ofMinutes(5);

	protected final LlmConfig config;
	private final Clock clock;
	private final HttpClient http;
	private final ExecutorService executor;
	private final Semaphore slots;
	private final LlmStats stats = new LlmStats();
	private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(Instant.EPOCH);
	private final AtomicInteger consecutiveFailures = new AtomicInteger();

	protected HttpLlmProvider(LlmConfig config, Clock clock) {
		this.config = config;
		this.clock = clock;
		if (!config.endpointAllowed()) {
			throw new IllegalArgumentException("endpoint host " + config.endpoint().getHost() + " is neither loopback nor in allowedHosts");
		}
		ThreadFactory factory = r -> {
			Thread t = new Thread(r, "luminary-llm-" + id());
			t.setDaemon(true);
			return t;
		};
		this.executor = Executors.newFixedThreadPool(config.maxConcurrent(), factory);
		this.slots = new Semaphore(config.maxConcurrent());
		this.http = HttpClient.newBuilder()
				.connectTimeout(config.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.executor(executor)
				.build();
	}

	/** The URI, body and extra headers for one request. */
	protected abstract HttpRequest.Builder buildRequest(LlmRequest request);

	/** Extracts the assistant text from a 2xx body; throws {@link LlmException} (BAD_RESPONSE) otherwise. */
	protected abstract String parseText(String body);

	@Override
	public String model() {
		return config.model();
	}

	@Override
	public boolean healthy() {
		return clock.instant().isAfter(cooldownUntil.get());
	}

	@Override
	public LlmStats stats() {
		return stats;
	}

	@Override
	public CompletableFuture<LlmResponse> complete(LlmRequest request) {
		stats.request();
		if (!healthy()) {
			stats.cooldown();
			return failed(new LlmException(LlmException.Kind.UNAVAILABLE, id() + " is cooling down until " + cooldownUntil.get()));
		}
		if (!slots.tryAcquire()) {
			stats.busy();
			return failed(new LlmException(LlmException.Kind.BUSY, id() + " has " + config.maxConcurrent() + " requests in flight"));
		}
		Instant started = clock.instant();
		HttpRequest httpRequest;
		try {
			httpRequest = buildRequest(request)
					.timeout(config.requestTimeout())
					.header("Content-Type", "application/json")
					.build();
		} catch (RuntimeException e) {
			slots.release();
			return failed(new LlmException(LlmException.Kind.BAD_RESPONSE, "cannot build request: " + e.getMessage(), e));
		}
		CompletableFuture<LlmResponse> result = new CompletableFuture<>();
		http.sendAsync(httpRequest, info -> new BoundedBodySubscriber(config.maxResponseBytes()))
				.orTimeout(config.requestTimeout().toMillis() + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
				.whenCompleteAsync((response, error) -> {
					try {
						if (error != null) {
							throw translate(error);
						}
						String body = response.body();
						if (response.statusCode() / 100 != 2) {
							throw new LlmException(LlmException.Kind.HTTP_STATUS, id() + " HTTP " + response.statusCode() + ": " + abbreviate(body));
						}
						String text = parseText(body);
						long latency = Duration.between(started, clock.instant()).toMillis();
						onSuccess(latency);
						result.complete(new LlmResponse(text, JsonExtractor.extractObject(text), latency, id(), config.model()));
					} catch (LlmException e) {
						onFailure(e);
						result.completeExceptionally(e);
					} catch (RuntimeException e) {
						LlmException wrapped = new LlmException(LlmException.Kind.TRANSPORT, id() + ": " + e.getMessage(), e);
						onFailure(wrapped);
						result.completeExceptionally(wrapped);
					} finally {
						slots.release();
					}
				}, executor);
		return result;
	}

	private LlmException translate(Throwable error) {
		Throwable cause = error;
		while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.io.UncheckedIOException
				|| cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof LlmException e) {
			return e;
		}
		if (cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException) {
			return new LlmException(LlmException.Kind.TIMEOUT, id() + " timed out after " + config.requestTimeout().toSeconds() + " s", cause);
		}
		if (cause.getMessage() != null && cause.getMessage().contains("exceeded")) {
			return new LlmException(LlmException.Kind.TOO_LARGE, cause.getMessage(), cause);
		}
		return new LlmException(LlmException.Kind.TRANSPORT, id() + ": " + cause.getMessage(), cause);
	}

	/** Collects at most {@code max} bytes; one byte more cancels the transfer and fails with TOO_LARGE. */
	static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
		private final int max;
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private final CompletableFuture<String> result = new CompletableFuture<>();
		private Flow.Subscription subscription;

		BoundedBodySubscriber(int max) {
			this.max = max;
		}

		@Override
		public CompletableFuture<String> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			for (ByteBuffer b : items) {
				int n = b.remaining();
				if (buffer.size() + n > max) {
					subscription.cancel();
					result.completeExceptionally(new LlmException(LlmException.Kind.TOO_LARGE, "response exceeded " + max + " bytes"));
					return;
				}
				byte[] chunk = new byte[n];
				b.get(chunk);
				buffer.write(chunk, 0, n);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(buffer.toString(StandardCharsets.UTF_8));
		}
	}

	private void onSuccess(long latency) {
		consecutiveFailures.set(0);
		stats.success(latency);
	}

	private void onFailure(LlmException e) {
		stats.failure();
		if (e.kind() == LlmException.Kind.BAD_RESPONSE) {
			return;   // the endpoint answered; a malformed reply is not an outage
		}
		int n = consecutiveFailures.incrementAndGet();
		long millis = Math.min(COOLDOWN_MAX.toMillis(), COOLDOWN_MIN.toMillis() << Math.min(n - 1, 10));
		cooldownUntil.set(clock.instant().plusMillis(millis));
	}

	private static <T> CompletableFuture<T> failed(Throwable t) {
		CompletableFuture<T> f = new CompletableFuture<>();
		f.completeExceptionally(t);
		return f;
	}


	static String abbreviate(String s) {
		return s.length() <= 200 ? s : s.substring(0, 200) + "…";
	}

	protected URI resolve(String path) {
		String base = config.endpoint().toString();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return URI.create(base + path);
	}

	protected static JsonObject messagesJson(LlmRequest request) {
		JsonObject o = new JsonObject();
		com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
		for (LlmMessage m : request.messages()) {
			JsonObject mo = new JsonObject();
			mo.addProperty("role", m.wireRole());
			mo.addProperty("content", m.content());
			arr.add(mo);
		}
		o.add("messages", arr);
		return o;
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}
}
