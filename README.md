# MrPot

## Project structure & workflow
- **Spring Boot entry point:** `MrPotApplication` boots the application and wires controllers, processors, and services.
- **Prompt preparation API:** `POST /v1/prompt/prepare` runs the `PromptPipeline` processors to normalize input, classify intent, render prompts, and optionally reuse cached prompts before returning a `PrepareResponse` payload.
- **Processing pipeline:** `PromptPipeline` executes a deterministic sequence of `TextProcessor` stages (clean/correct, intent detection, common response check, prompt cache lookup, template selection, LangChain4j RAG, cache record) while preserving an ordered `ProcessingContext` audit trail.
- **RAG generation:** `LangChain4jRagProcessor` delegates to `LangChain4jRagService` to retrieve knowledge-base snippets, assemble a bounded prompt (system prompt + KB context + question), and request a chat completion; results and document IDs are recorded on the context.
- **Knowledge-base search:** `SupabaseKbSearchService` performs `ILIKE` searches against `kb_documents`, extracts concise snippets around matched keywords, and falls back to recent documents when no matches exist.
- **Streaming demo:** `GET /v1/prompt/stream` emits server-sent `StepEvent` updates to illustrate incremental processing.

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
