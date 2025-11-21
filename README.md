# MrPot

MrPot is a Spring Boot service that prepares LLM-friendly prompts using a reactive processing
pipeline. It combines input validation, templating, retrieval-augmented generation (RAG), and
lightweight caching to deliver prompt drafts and metadata that downstream chat surfaces can use
immediately.

## At a glance
- **Tech stack:** Java 21, Spring Boot WebFlux, Reactor, LangChain4j, PostgreSQL (Supabase), Kafka
  baseline configuration, OpenAPI/Swagger UI at `/swagger`.
- **Primary flow:** `POST /v1/prompt/prepare` runs the processor pipeline and returns the rendered
  prompts, detected intent/language, knowledge-base doc references, and step logs.
- **Streaming demo:** `GET /v1/prompt/stream` emits dummy `StepEvent` items over SSE for UI wiring.

## How the pipeline works
The processing chain is orchestrated by `PromptPipeline`, which executes processors in a stable
order and records step metadata.

Default processors (in-order):
1. **UnifiedCleanCorrectProcessor** – normalizes/cleans text and seeds the `ProcessingContext`.
2. **IntentClassifierProcessor** – infers the user intent and language, attaching tags/entities.
3. **CommonResponseProcessor** – builds a baseline system prompt and user prompt fields.
4. **PromptCacheLookupProcessor** – checks cache hits to short-circuit later steps when possible.
5. **PromptTemplateProcessor** – renders prompts from `prompt_templates.json` with detected intent and
   placeholders.
6. **LangChain4jRagProcessor** – enriches context via `LangChain4jRagService`, which queries
   `KbSearchService` for pgvector-backed snippets, assembles KB context, and calls the configured
   ChatModel.
7. **PromptCacheRecordProcessor** – persists successful prompt outputs to the cache for reuse.

Validation happens up-front via `ValidationService`, and each processor adds a `StepLog` entry so
clients can render the pipeline trace. Cache hits can bypass template rendering to save tokens.

## Project structure
```
src/main/java/com/example/datalake/mrpot/
├── MrPotApplication.java             # Spring Boot entrypoint
├── config/                           # OpenAI/LangChain4j and Supabase property holders
├── controller/                       # REST controllers (prepare endpoint, SSE demo)
├── dao/                              # JDBC/Repository helpers for KB and keywords
├── model/                            # Domain objects (ProcessingContext, KbDocument/Snippet, etc.)
├── processor/                        # TextProcessor implementations in the pipeline
├── request/response/                 # API DTOs
├── service/                          # Pipeline orchestration, RAG and cache services
├── util/                             # Prompt rendering, cache key helpers, code fences
└── validation/                       # Input validation stages and error handling

src/main/resources/
├── application.properties            # Ports, Swagger paths, datasource, Kafka, LangChain4j settings
├── keywords_map.json                 # Keyword lexicon for intent classification
└── prompt_templates.json             # System/user/final prompt templates keyed by intent
```

## Configuration
- **Datasource:** Supabase Postgres URL/credentials are configured in `application.properties`; adjust
  for your environment or override via environment variables.
- **LangChain4j:** Provide `OPENAI_API_KEY` and optional `OPENAI_MODEL`, `OPENAI_TEMPERATURE`,
  `OPENAI_MAX_OUTPUT_TOKENS` env vars to customize the chat model used by `LangChain4jRagService`.
- **Kafka:** Baseline producer/consumer properties are included; set `KAFKA_BOOTSTRAP_SERVERS` when
  enabling messaging integrations.
- **Swagger UI:** Available at `/swagger`; raw docs at `/v3/api-docs` and `/v3/api-docs.yaml`.

## Workflows
### Swagger UI
Run the app (`mvn spring-boot:run`) and open [http://localhost:8080/swagger](http://localhost:8080/swagger)
to explore the generated OpenAPI docs. The UI exposes the grouped `prompt` APIs under `/v1/**`
and the actuator endpoints under `/actuator/**`.

### Format code
```bash
mvn spotless:apply
```

### Run locally
```bash
mvn spring-boot:run
```
(Overrides such as `PORT`, `SPRING_PROFILES_ACTIVE`, and `OPENAI_API_KEY` can be set as env vars.)

### Build (skip tests)
```bash
mvn clean install -DskipTests
```

### Call the API
Prepare a prompt session:
```bash
curl -X POST http://localhost:8080/v1/prompt/prepare \
  -H 'Content-Type: application/json' \
  -d '{"query": "Tell me about vector search", "userId": "demo-user"}'
```

Stream demo step events:
```bash
curl http://localhost:8080/v1/prompt/stream?q=hello
```

## Testing
Run unit tests (default Maven phase):
```bash
mvn test
```
