# ADR-0007: The language-model layer is an isolated, bounded, optional client

Date: 2026-10-12 · Status: accepted

## Decision

1. `dev.luminary.core.npc.llm` holds the whole model integration: `LlmProvider` (async `complete`, `healthy`,
   counters), `LlmRequest`/`LlmResponse` (bounded arguments, optional JSON schema), the HTTP base with Ollama and
   OpenAI-compatible backends, a `none` provider, JSON extraction and a JSONL journal. It depends on the JDK HTTP
   client and Gson only, so it is unit-tested against a loopback mock server without Minecraft.
2. The game thread never waits on a model. `LlmService.ask` (Fabric layer) submits and later hops the outcome back
   with `MinecraftServer.execute`; callers always handle the failure branch.
3. Hard limits live in the client, not in callers: timeouts, response-size cap enforced while streaming, concurrency
   semaphore with fast `BUSY` failures, exponential cooldown with fast `UNAVAILABLE` failures, loopback-only
   endpoints unless allow-listed, no redirects.
4. Secrets: the API key comes from `LUMINARY_LLM_API_KEY` only; a key in `config/luminary.json` is ignored and
   warned about; the journal records sizes and outcomes, not content, unless `logContent` is explicitly enabled.
5. The default is `provider: none`. A server with no model configured behaves exactly as before this slice.

## Context

The research set required provider independence, a deterministic fallback, no blocking of the server thread, bounded
retries and rate limits, and no secrets in files (`AI_NPC_TECHNICAL_RESEARCH.md` §5, `SECURITY_AND_PERFORMANCE.md`).
Ollama's `/api/chat` fields (`format`, `keep_alive`, `options`, `stream`) were verified against its documentation.

## Alternatives considered

- **A vendor SDK** (OpenAI/Anthropic Java clients): more dependencies in a mod jar and one wire format per vendor;
  the OpenAI-compatible format already covers OpenRouter, vLLM, llama.cpp and LM Studio, and Ollama has its own.
- **Reading the body as an InputStream**: deadlocked the single-threaded client (the reader blocked the thread that
  delivers the bytes); replaced by a bounded `BodySubscriber` that cancels the transfer at the cap.
- **Retrying inside the client**: rejected for now; a retry on a 20 s timeout doubles the stall for the player. The
  cooldown plus the caller's fallback (scripted line) is the retry policy.

## Consequences

- Slices 7–9 (memory, model-backed dialogue, intent planner) only add callers of `LlmService.ask` and validators
  of its output; they cannot widen the model's reach because nothing else is exposed.
- Quality and latency of a real model remain unmeasured until an endpoint is available (owner decision pending).
