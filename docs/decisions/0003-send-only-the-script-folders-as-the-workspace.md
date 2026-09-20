---
id: 0003
title: Send the language server only the folders that hold AngelScript
status: accepted
date: 2026-09-20
area: lsp-client
applies-to:
  plugin: 0.9.10
  lsp4ij: "0.21"
  language-server: v1.9.4
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptWorkspaceFolderFeature.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptConnectionProvider.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/project/ScriptFolderDetector.kt
verified-by: []
supersedes: []
superseded-by: null
---

# Send the language server only the folders that hold AngelScript

## TL;DR

lsp4ij's default workspace is every content root of the solution, which in Rider means the nuget
cache, the .NET SDK analyzer directories and the whole engine tree. The server scans each one for
`.as` files before it loads anything. We override `LSPWorkspaceFolderFeature` and send the detected
`Script` folders instead. **This is why the plugin requires lsp4ij 0.21** - that is where
`getInitialWorkspaceFolders` arrived.

## Context

Measured on a real project (1133 `.as` files, engine and project on the same drive), with the
default content roots:

| | |
|---|---|
| Workspace roots sent | 12 |
| Total scan before anything loads | ~2.5 minutes |
| Of which: engine tree | 53 s, 0 `.as` files found |
| Of which: project directory | 86 s |
| The project's own `Script` folder alone | ~0.1 s |

The cost was not only the wait. The type cache loads about six seconds in, and anything resolved
before the project finished loading resolves against an almost empty project. Resolution is cached
on the module and only invalidated by an edit, so completion recovered when the project eventually
loaded but **navigation stayed broken for the rest of the session** - the symbol list that
go-to-definition reads is recorded at resolve time.

With only the script folders:

| | 12 content roots | script folders only |
|---|---|---|
| Files queued | t=8.4 s | t=1.6 s |
| First resolve | t=8.0 s, cross-module symbol missing | t=8.1 s, already correct |

## Decision

- `AngelScriptWorkspaceFolderFeature.getInitialWorkspaceFolders` returns the folders from
  `ScriptFolderDetector.detectScriptRoots`, spelled through
  [`AngelScriptFileUriSupport.normalizedUri`](0002-send-lowercase-drive-letters-to-the-server.md).
- `detectScriptRoots` is the union of an index-free probe of `<project base>/Script` - the server
  can start while the IDE is still indexing - and every `Script` folder next to a `.uproject` or
  `.uplugin` once the index can answer.
- When detection finds nothing, fall back to `super.getInitialWorkspaceFolders`, i.e. the old
  behaviour. A project laid out in a way we do not recognise is better served slowly than not at
  all.
- The same folders are also sent as `initializationOptions.additionalScriptRootFolders`. The server
  ignores roots it already has, so this is a no-op while the feature applies, and the slow-but-
  correct path if a future lsp4ij stops applying it.

## Alternatives

### Leave the workspace alone and fix the server's scan

Partly done anyway ([0004](0004-keep-analysis-usable-during-the-initial-load.md)): each root is
loaded as soon as it has been scanned. It makes the wait survivable but does not remove it - the
engine tree still gets walked, for nothing.

### Use `additionalScriptRootFolders` alone

That was the first attempt, before the lsp4ij API was available. It adds the script folders but
cannot remove the content roots, so the server still spends minutes scanning them.

### Let the user configure the roots

Still wanted, for script kept outside the project; recorded in
[plans/0001](../plans/0001-configurable-script-roots.md). It is a complement to detection, not a
replacement - most projects should not have to configure anything.

## Consequences

- **The plugin requires lsp4ij 0.21 or later.** lsp4ij is a separate Marketplace plugin that users
  update independently, and `plugin.xml` cannot express a version constraint on it, so this is only
  a documented requirement and a compile-time pin in `build.gradle.kts`.
- Module names are now relative to the script root (`Gameplay.HandCart.Foo` rather than
  `Script.Gameplay.HandCart.Foo`). Harmless - upstream v1.9.4 dropped explicit imports, so a module
  name is an internal identity - but it is a visible change in logs.
- The roots are read **once, at initialize**. A `Script` folder that appears later (a new plugin,
  a branch switch) is invisible until the server restarts.
  [plans/0002](../plans/0002-notice-script-folders-added-later.md) has the two ways to close that.
- Anything that needs the server to know about a file outside the script folders has to say so
  explicitly.

## How this breaks

- The LSP console's `Workspace roots:` line lists more than the script folders - detection failed
  and the fallback took over.
- A project's AngelScript is not analysed at all: the layout is one detection does not recognise
  *and* the fallback content roots do not contain it.
- lsp4ij changes or removes `LSPWorkspaceFolderFeature`; the client features then fail to load and
  the server never starts.

## History

- 2026-09-20 - Taken after measuring the content-root scan; `additionalScriptRootFolders` kept as a
  fallback rather than removed.
