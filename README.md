# MrPot

## Project structure & workflow
- **Spring Boot entry point:** `MrPotApplication` boots the application and wires controllers, processors, and services.
- **Prompt preparation API:** `POST /v1/prompt/prepare` runs the `PromptPipeline` processors to normalize input, classify intent, render prompts, and optionally reuse cached prompts before returning a `PrepareResponse` payload.
- **Processing pipeline:** `PromptPipeline` executes a deterministic sequence of `TextProcessor` stages (clean/correct, intent detection, common response check, prompt cache lookup, template selection, LangChain4j RAG, cache record) while preserving an ordered `ProcessingContext` audit trail.
- **RAG generation:** `LangChain4jRagProcessor` delegates to `LangChain4jRagService` to retrieve knowledge-base snippets, assemble a bounded prompt (system prompt + KB context + question), and request a chat completion; results and document IDs are recorded on the context.
- **Knowledge-base search:** `SupabaseKbSearchService` performs `ILIKE` searches against `kb_documents`, extracts concise snippets around matched keywords, and falls back to recent documents when no matches exist.
- **Streaming demo:** `GET /v1/prompt/stream` runs the same processing pipeline as `/prepare`, emits enriched `StepEvent` updates, and finishes with the full `PrepareResponse`.

## Key technologies
- **Java 17** with **Spring Boot 3** for web (MVC/WebFlux), validation, and data access.
- **LangChain4j** (OpenAI chat, embeddings, pgvector, reactor helpers) for LLM and retrieval-augmented generation.
- **PostgreSQL** (with optional `kb_documents` table) plus **pgvector** integration for knowledge storage.
- **Spring Kafka** for messaging support and **Actuator** for operational endpoints.
- **LanguageTool** and **Google Cloud Translate** for language detection, correction, and translation utilities.
- **Spotless** (Google Java Format) for consistent code formatting.

## Key features
- **Deterministic text-processing pipeline** with validation notices, cache awareness, and per-stage step logging to aid debugging.
- **Prompt caching** that normalizes cache keys and tracks frequency/last-seen timestamps to avoid redundant prompt assembly.
- **Knowledge-base snippet retrieval** that enforces total-character budgets and surfaces contributing document IDs for transparency.
- **Prompt rendering utilities** to enforce system/user/final prompt presence and ensure combined prompts stay within character limits.
- **Server-sent event demo** producing ordered `StepEvent` updates for UI consumption or testing SSE handling.

## Formatting

Format Java sources using the Google Java Format profile via Spotless:

```bash
mvn spotless:apply
```

## Build

Build the project while skipping tests:

```bash
mvn clean install -DskipTests
```

If Maven cannot download dependencies because the default Central repository is blocked, configure an accessible mirror or pre-populate the required artifacts in your local `~/.m2` cache before running the commands above.

## Test locally

Run the full unit/integration test suite with the Maven wrapper (requires JDK 17+):

```bash
./mvnw test
```

If you prefer to suppress download progress logs, add `-q` (quiet) to the command. The wrapper automatically downloads the right Maven version; if that fails because your network blocks Maven Central, point `~/.m2/settings.xml` to a reachable mirror or pre-seed the dependencies.

To verify the SSE thinking-steps flow end to end, start the application and call the **GET** streaming demo (the endpoint rejects POST requests) which now mirrors `/prepare`:

```bash
./mvnw spring-boot:run
# In another shell
curl -N "http://localhost:8080/v1/prompt/stream" \
  --get --data-urlencode 'q=Hello!' \
  --data-urlencode "userId=local-test"
```

> **Tip (zsh/Oh-My-Zsh):** `!` triggers history expansion. If you see `dquote>` prompts or the command never executes, escape the exclamation (`q=Hello\!`) or use single quotes as shown above.

You should see `event: step-event` entries (one per processing stage) followed by a `prepare-response` event containing the same payload returned by `/v1/prompt/prepare`, and finally a `done` event.
