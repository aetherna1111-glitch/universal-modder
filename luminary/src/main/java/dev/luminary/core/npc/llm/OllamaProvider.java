package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.time.Clock;

/**
 * Ollama's {@code POST /api/chat} with {@code stream:false}. A JSON schema is passed as {@code format} (structured
 * outputs); {@code keep_alive} keeps the model loaded between NPC turns. Facts from ollama/docs/api.md (2026-10).
 */
public final class OllamaProvider extends HttpLlmProvider {
	public OllamaProvider(LlmConfig config, Clock clock) {
		super(config, clock);
	}

	@Override
	public String id() {
		return LlmConfig.PROVIDER_OLLAMA;
	}

	@Override
	protected HttpRequest.Builder buildRequest(LlmRequest request) {
		JsonObject body = messagesJson(request);
		body.addProperty("model", config.model());
		body.addProperty("stream", false);
		body.addProperty("keep_alive", config.keepAlive().toSeconds() + "s");
		request.jsonSchema().ifPresent(schema -> body.add("format", schema));
		JsonObject options = new JsonObject();
		options.addProperty("temperature", request.temperature());
		options.addProperty("num_predict", request.maxOutputTokens());
		options.addProperty("num_ctx", config.contextTokens());
		body.add("options", options);
		return HttpRequest.newBuilder(resolve("/api/chat")).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
	}

	@Override
	protected String parseText(String body) {
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			if (o.has("error")) {
				throw new LlmException(LlmException.Kind.REFUSED, "ollama: " + o.get("error").getAsString());
			}
			return o.getAsJsonObject("message").get("content").getAsString();
		} catch (LlmException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new LlmException(LlmException.Kind.BAD_RESPONSE, "ollama: unexpected body " + abbreviate(body), e);
		}
	}
}
