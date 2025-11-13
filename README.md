# MrPot

## Code Formatting

This project uses the Google Java Style. To reformat all production and test
sources, run the [Spotless](https://github.com/diffplug/spotless) plugin via the
Maven wrapper:

```bash
./mvnw spotless:apply
```

Run this before committing to keep the formatting consistent.

## Build and Test

Use the Maven wrapper to compile the project and execute the unit test suite:

```bash
./mvnw clean verify
```
