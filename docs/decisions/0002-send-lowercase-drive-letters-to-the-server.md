---
id: 0002
title: Every URI sent to the language server carries a lower-case drive letter
status: accepted
date: 2026-09-20
area: lsp-client
applies-to:
  plugin: 0.9.10
  lsp4ij: "0.21"
  language-server: v1.9.4
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptFileUriSupport.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptLanguageServerFactory.kt
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptFileUriSupportTest.kt
supersedes: []
superseded-by: null
---

# Every URI sent to the language server carries a lower-case drive letter

## TL;DR

Rider spells a file `file:///D:/...`; the server derives its own URIs through
`URI.parse(root).fsPath`, which lower-cases the drive. The two spellings are the same file to a URI
lookup but **different modules** to the server, so everything this plugin sends - documents,
workspace folders, script roots - goes through `AngelScriptFileUriSupport`, which lower-cases the
drive on the way out and restores it on the way back in.

## Context

The server keeps two mappings of a file:

- by URI, through `NormalizeUri()`, which lower-cases the whole string - so URI lookups find each
  other whatever the case;
- by **module name**, through `getModuleName()`, which strips a workspace root prefix with a
  case-sensitive `startsWith`.

When the drive letters disagree, the prefix does not match, the module is named after its entire
path, and the file the editor has open becomes a second module beside the one the server scanned
from disk. Measured on a real project by driving the server directly: completion after
a script type's `::` returned 56 items where 28 were distinct - every member twice, once per module.

This was originally misdiagnosed as a duplicate-module bug in the server. It is not: it is the
client spelling the same file two ways.

## Decision

`AngelScriptFileUriSupport` (an lsp4ij `FileUriSupportBase`) is installed on the client features and
does three things:

- `getFileUri` / `toString` - lower-case the drive letter on everything sent to the server;
- `findFileByUri` - upper-case it again, because Rider's own VFS lookup wants it that way;
- `normalizedUri(file)` - the same transformation for code that builds URIs itself, which is what
  the workspace folders ([0003](0003-send-only-the-script-folders-as-the-workspace.md)) and the
  `additionalScriptRootFolders` initialization option use.

The drive-letter helpers are public and unit-tested at string level, so the invariant can be checked
without an IDE.

## Alternatives

### Normalise on the server side instead

The bundler could patch `getModuleName` to compare case-insensitively. That is a patch on upstream
behaviour that upstream might legitimately change, and it would paper over a client that speaks
inconsistently - the next feature that builds a URI by hand would still get it wrong.

### Upper-case everything instead

The server would still lower-case its own scanned URIs, so the mismatch would simply move.

## Consequences

- **Any new code that sends a path to the server must go through `normalizedUri`.** A raw
  `LSPIJUtils.toUri(file).toString()` reintroduces the bug, and the symptom - duplicated completion
  items, navigation into a second copy of a module - does not look like a URI problem.
- The plugin is now responsible for a spelling convention the LSP specification does not define, so
  it has to be stated somewhere: here.

## How this breaks

- Completion items appear twice, or go-to-definition lands in a module whose name is a full path.
- The server logs module names that look like `f:.project.script.gameplay.foo` instead of
  `Gameplay.Foo`.
- `AngelScriptFileUriSupportTest` fails, or a new call site skips the helper.

## History

- 2026-09-20 - Extracted from `AngelScriptLanguageServerFactory` into its own file with tests, when
  workspace folders started being built by hand and needed the same spelling.
