---
id: 0004
title: Load each workspace root as it is scanned, and re-resolve when a batch lands
status: accepted
date: 2026-09-20
area: lsp-bundle
applies-to:
  plugin: 0.9.10
  language-server: v1.9.4
touches:
  - scripts/bundle-lsp.js
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptInitialLoadTest.kt
supersedes: []
superseded-by: null
---

# Load each workspace root as it is scanned, and re-resolve when a batch lands

## TL;DR

Two patches in `scripts/bundle-lsp.js`. The first loads a root's files as soon as *that* root has
been globbed, instead of waiting for the slowest root. The second re-resolves modules once a batch
of newly loaded ones has drained through the queues, because a module that resolved before its
dependencies existed caches the failure until someone edits the file.

## Context

Upstream counts the outstanding globs and only starts loading when the last one finishes
(`GlobsRemaining <= 0`). That is reasonable for VSCode, where the workspace is the project. Under
Rider it meant the whole project waited for an engine tree scan that found nothing - see
[0003](0003-send-only-the-script-folders-as-the-workspace.md) for the numbers.

The second half is subtler and survives even with a fast workspace. Resolution is cached on the
module and invalidated only by an edit. Upstream re-resolves everything when a type database
arrives from the editor - but a project running off the saved type cache receives that database
once, about six seconds in, before most of the project has been loaded. So the file the user had
open resolved against an almost empty project and stayed that way: completion recovered (it reads
the type database live) while go-to-definition did not (it reads the symbol list recorded at resolve
time). That is exactly what was reported: "same-file symbols work, C++ symbols work, symbols from
other `.as` files have no completion and no navigation".

The first attempt keyed the re-resolve on "all roots have been scanned". Tracing showed it waiting
about two minutes - the wrong signal, since it is the slow root that we were trying to stop waiting
for in the first place.

## Decision

Patch one, at the glob callback:

- mark `__asModulesLoadedSinceResolve` when a root's glob returned files;
- tick the queues immediately unless they are already being serviced, rather than waiting for
  `GlobsRemaining <= 0`.

Patch two, at the point where the queue servicing settles:

- when the queues have drained *and* modules were loaded since the last re-resolve *and* the type
  database is present, log and call `ReResolveAllModules()`.

Measured cost of the re-resolve on the same project: ~330 ms of CPU, about a second of wall time,
against a parse of 1949 ms for 1134 modules. Cheap enough to do per batch.

## Alternatives

### Re-resolve on a timer

Simple, but either too eager (repeated work while loading) or too late, and it keeps running long
after the project is stable.

### Re-resolve only the open file

What upstream effectively does when the database arrives. It fixes completion for the file in front
of the user and leaves every other module holding its failed lookups - which is the state that made
navigation stay broken.

### Invalidate resolution when a module is added

Precise, but it means understanding which modules depend on which, inside generated code, in a
regex patch. The batch re-resolve gets the same result for a known, small cost.

## Consequences

- The server does measurably more work during startup than upstream does: a re-resolve per batch of
  loaded modules. Bounded, because batches stop when loading stops.
- The patches sit on two specific upstream shapes (the glob callback and the `IsServicingQueues`
  reset). `AngelScriptInitialLoadTest` reads the bundled output and fails if either is gone, since
  the symptom is silent.
- With the workspace narrowed to script folders, the first patch mostly matters for projects that
  fall back to content roots. It stays because that fallback is exactly where the wait is worst.

## How this breaks

- `npm run bundle` reports a `⚠` for either patch, or `AngelScriptInitialLoadTest` fails.
- The LSP console never logs `[initialLoad] A batch of modules finished loading; re-resolving`.
- Cross-file symbols stop being found until the file is edited - the original symptom.

## History

- 2026-09-20 - Both patches added; the re-resolve trigger changed from "all roots scanned" to
  "modules loaded and the queues have drained" after tracing showed the first waiting ~2 minutes.
