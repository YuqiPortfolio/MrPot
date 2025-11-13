# MrPot

## Code Formatting

This project uses the Google Java Style. You can reformat all production and
test sources with the following command (requires `clang-format`, which is
preinstalled in the development container):

```bash
find src -name '*.java' -print0 | xargs -0 clang-format -style=Google -i
```

Run this before committing to keep the formatting consistent.
