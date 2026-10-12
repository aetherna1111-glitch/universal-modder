package dev.luminary.core.npc.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A loopback HTTP server that answers like Ollama or an OpenAI-compatible endpoint, with programmable behaviour. */
final class MockLlmServer implements AutoCloseable {
	record Seen(String path, Map<String, List<String>> headers, String body) {}

	private final HttpServer server;
	final List<Seen> seen = new ArrayList<>();
	final AtomicInteger hits = new AtomicInteger();
	volatile Function<String, Reply> handler;

	record Reply(int status, String body, long delayMillis) {
		static Reply ok(String body) { return new Reply(200, body, 0); }
	}

	MockLlmServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private void handle(HttpExchange ex) throws IOException {
		hits.incrementAndGet();
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		synchronized (seen) {
			seen.add(new Seen(ex.getRequestURI().getPath(), ex.getRequestHeaders(), body));
		}
		Reply reply = handler.apply(body);
		if (reply.delayMillis() > 0) {
			try {
				Thread.sleep(reply.delayMillis());
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(reply.status(), bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	static String ollamaBody(String content) {
		return "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":" + com.google.gson.JsonParser.parseString("\"" + content.replace("\"", "\\\"") + "\"") + "},\"done\":true}";
	}

	static String openAiBody(String content) {
		return "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + com.google.gson.JsonParser.parseString("\"" + content.replace("\"", "\\\"") + "\"") + "},\"finish_reason\":\"stop\"}]}";
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
