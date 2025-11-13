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
