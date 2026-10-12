package dev.luminary.core.npc.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;
import java.time.Clock;

/**
 * Any server speaking the OpenAI chat-completions wire format ({@code POST {endpoint}/chat/completions}; the
 * endpoint is expected to include the version prefix, e.g. {@code https://host/v1}). Bearer auth from
 * {@link LlmConfig#apiKey()} when present. Structured output is requested with {@code response_format:
 * {type: json_object}} plus the schema spelled out to the model; the caller validates the result.
 */
public final class OpenAiCompatibleProvider extends HttpLlmProvider {
	public OpenAiCompatibleProvider(LlmConfig config, Clock clock) {
		super(config, clock);
	}

	@Override
	public String id() {
		return LlmConfig.PROVIDER_OPENAI;
	}

	@Override
	protected HttpRequest.Builder buildRequest(LlmRequest request) {
		LlmRequest effective = request;
		if (request.jsonSchema().isPresent()) {
			java.util.List<LlmMessage> msgs = new java.util.ArrayList<>(request.messages());
			msgs.add(LlmMessage.system("Respond with a single JSON object matching this JSON schema and nothing else: " + request.jsonSchema().get()));
			effective = new LlmRequest(msgs, request.jsonSchema(), request.maxOutputTokens(), request.temperature());
		}
		JsonObject body = messagesJson(effective);
		body.addProperty("model", config.model());
		body.addProperty("max_tokens", request.maxOutputTokens());
		body.addProperty("temperature", request.temperature());
		body.addProperty("stream", false);
		if (request.jsonSchema().isPresent()) {
			JsonObject format = new JsonObject();
			format.addProperty("type", "json_object");
			body.add("response_format", format);
		}
		HttpRequest.Builder b = HttpRequest.newBuilder(resolve("/chat/completions")).POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		config.apiKey().ifPresent(key -> b.header("Authorization", "Bearer " + key));
		return b;
	}

	@Override
	protected String parseText(String body) {
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			if (o.has("error")) {
				JsonObject err = o.get("error").isJsonObject() ? o.getAsJsonObject("error") : null;
				throw new LlmException(LlmException.Kind.REFUSED, "openai: " + (err != null && err.has("message") ? err.get("message").getAsString() : o.get("error").toString()));
			}
			return o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
		} catch (LlmException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new LlmException(LlmException.Kind.BAD_RESPONSE, "openai: unexpected body " + abbreviate(body), e);
		}
	}
}
