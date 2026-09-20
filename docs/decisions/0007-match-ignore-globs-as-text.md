---
id: 0007
title: Match ignore globs against the path text, not through java.nio PathMatcher
status: accepted
date: 2026-09-20
area: lsp-client
applies-to:
  plugin: 0.9.10
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/util/GlobMatcher.kt
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/util/GlobMatcherTest.kt
supersedes: []
superseded-by: null
---

# Match ignore globs against the path text, not through java.nio PathMatcher

## TL;DR

`GlobMatcher` fed the path to `Paths.get`, which on Windows rejects the `/f:/Project/...` form that
LSP URIs carry. Every match threw, every throw was caught and read as "does not match", so the
script ignore patterns had **never** excluded anything on Windows - while logging two warnings per
navigation. The glob is now translated to a regex and matched against the path text.

## Context

Callers pass what a `file:` URI gives them: `java.net.URI(uri).path` on
`file:///f:/Project/Script/Foo.as` is `/f:/Project/Script/Foo.as`. `Paths.get` on Windows throws
`InvalidPathException: Illegal char <:> at index 3`. The exception was caught and logged at WARN,
and the method returned false.

Found in the user's `idea.log`, two warnings per go-to-declaration:

```
WARN - GlobMatcher: Exception matching pattern '**/Saved/**' against
'/f:/Project/.../MyLibrary.as': Illegal char <:> at index 3
```

The setting it powers (`scriptIgnorePatterns`, default `**/Saved/**` and `**/.plastic/**`) decides
which files the language server is told about and refuses navigation into build output. Neither had
been happening.

## Decision

Translate the glob to a regex once per pattern and cache it:

- `**/` matches any number of directories **including none**, so `**/Saved/**` also matches
  `Saved/Foo.as`;
- `**` matches across separators, `*` and `?` stay inside one segment;
- everything else is literal;
- matching is case-insensitive, and the path is normalised first: backslashes to forward slashes,
  and a leading slash in front of a Windows drive removed.

## Alternatives

### Keep PathMatcher and strip the leading slash

Fixes the crash but keeps a platform-dependent matcher whose `/` handling differs between Windows
and POSIX, and which cannot be unit-tested for the other platform's behaviour. The glob dialect we
want is the language server's, not the one the JDK implements for the local file system.

### Normalise at every call site

Two call sites today, and the next one would get it wrong the same way.

## Consequences

- The ignore patterns now really exclude - on Windows for the first time. A user with an unusual
  pattern may see files disappear from analysis that used to be analysed by accident.
- Matching is case-insensitive everywhere, including on case-sensitive file systems. Deliberate: the
  patterns name directories like `Saved` and `Intermediate`.
- `GlobMatcher` no longer touches the file system, so it is unit-testable on any platform.

## How this breaks

- Files that should be ignored are analysed, or the reverse. `GlobMatcherTest` covers the URI form,
  both separators, segment boundaries and the leading `**/`.
- A pattern syntax the translation does not implement (character classes, braces) silently matches
  nothing.

## History

- 2026-09-20 - Found in the log while diagnosing C++ navigation; the patterns had been inert since
  they were introduced.
