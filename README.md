# MrPot

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

## Supabase prompt analytics tables

The application now persists each prompt run into Supabase/PostgreSQL so you can analyze how the pipeline performs over time.

- `prompt_sessions`: root record for a run, capturing user/session ids, prompts (raw/normalized/system/user/final), intent/language, cache flags, answer, and creation time.
- `prompt_session_steps`: child table keyed by `prompt_sessions.id` that stores each pipeline step name, note, and timestamp for traceability.
- `prompt_session_documents`: child table keyed by `prompt_sessions.id` that records which knowledge-base document ids were returned to the LLM.
- `prompt_session_tags`: element collection for any tags extracted during processing.
- `prompt_session_notices`: element collection for validation notices.

With `spring.jpa.hibernate.ddl-auto=update`, the tables are created/updated automatically against the Supabase Postgres configured in `application.properties`.
