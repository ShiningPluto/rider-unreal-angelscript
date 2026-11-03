---
id: 0013
title: Have the server analyse a file angelscript_get_problems is asked for, and know it did from a status request sent after it
status: accepted
date: 2026-11-03
area: mcp
applies-to:
  plugin: 1.0.3
  lsp4ij: "0.21"
  language-server: v1.9.4
  rider: "2025.2"
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/mcp/AngelScriptMcpToolset.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/status/AngelScriptUnrealStatusService.kt
  - scripts/bundle-lsp.js
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/mcp/ReanalyseFileBundleTest.kt
supersedes: []
superseded-by: null
---

# Have the server analyse a file angelscript_get_problems is asked for, and know it did from a status request sent after it

## TL;DR

Given `filePath`, `angelscript_get_problems` sends the server `workspace/didChangeWatchedFiles` for
that file and then `angelscript/getUnrealStatus`. The server handles messages one at a time and the
file event synchronously, so the status answer means the file has been read again and analysed, and
any diagnostics it changed have been recorded. The status's `pendingScripts`, added for this, says
whether the server could analyse at all. The store's entry for the file - or its absence - is then
the current result.

## Context

- The server registers no file watchers and lsp4ij forwards file changes only for patterns a server
  registers ([plan-0002](../plans/0002-notice-script-folders-added-later.md)). A file an agent
  edits outside the editor stays as the server last read it until it is opened, so the tool
  returned the problems from before the edit.
- The server pushes diagnostics only when they change (`UpdateScriptModuleDiagnostics` in
  `ls_diagnostics.ts` returns early when they equal the last ones and Unreal has none). A file that
  never had problems is never pushed, so the tool had no `updatedAt` for it, and "no problems" looked
  the same as "not analysed yet".
- Every handler that analyses - open, change, file event - does so only while `ParseQueue` and
  `LoadQueue` are empty, which they are not during the initial load. Nothing exposed them.
- vscode-jsonrpc takes one message off its queue per tick (`processMessageQueue`). The file event
  handler reads the file with `fs.readFileSync`, parses, resolves and pushes without deferring. lsp4j
  hands the client notifications and responses on one reader thread, in order, and
  `AngelScriptLanguageClient.publishDiagnostics` records into the store on that thread.

## Decision

- `getUnrealStatus` gains `pendingScripts: ParseQueue.length + LoadQueue.length`; the bundler checks
  both names resolve. The status tool and the problems tool's `analysis` report it.
- For an asked-for file: a status request first, to stop if scripts are queued; a synchronous VFS
  refresh, which reloads an unmodified open document; a commit of its document on the EDT, which has
  lsp4ij queue the change; for a file open in an editor, 200 ms for lsp4ij's queue to send it; the
  file event, `Changed`, with the URI `AngelScriptFileUriSupport.normalizedUri` gives; and a second
  status request as the barrier. With no scripts queued before and after - the queues only shrink
  while loading - the file was analysed, and its `updatedAt` is that time.
- A file matching a script ignore pattern is not sent: the plugin keeps those away from the server
  so that copies under `Saved/` do not become duplicate modules. The tool says so in `note`.

## Alternatives

### Reporting the file as created, to make the server push

Upstream sets `alwaysSend` for created and deleted files, but the early return for unchanged
diagnostics comes before it, so a file whose problems stayed the same was still not pushed, and
waiting for a push timed out in the commonest case.

### A request that analyses a file and returns its diagnostics

A bundle patch of a few lines, and the most direct answer. The existing status request already
serves as a barrier once it reports the queues, and keeps the patch surface to one field
([0001](0001-patch-the-language-server-at-bundle-time.md)).

### Flushing lsp4ij's pending changes instead of waiting 200 ms

`DocumentContentSynchronizer.flushPendingChanges` does exactly this, but is reached through
`LanguageServerItem.getServerWrapper()`, which is `@ApiStatus.Internal`; even lsp4ij's on-type
formatting uses it that way. Releases are verified free of internal API use.

### A file watcher for every script

What plan-0002 proposes, and what would keep the server current for every external change. It is
the larger change and does not tell the tool when the analysis has happened, which it needs anyway.

## Consequences

- Every `get_problems` call with `filePath` re-reads and re-resolves one module on the server, and
  waits 200 ms more for a file open in an editor.
- Other files that use what the edited file declares are not analysed again, as upstream does not
  for any single-file change.
- A file the server did not know, such as one an agent created, becomes a module.
- The barrier rests on the server handling the file event synchronously and messages in order;
  an upstream change to either makes answers stale again, silently.

## How this breaks

- `ReanalyseFileBundleTest` fails: the file event handler defers its work, stops reading from disk,
  or the status lost `pendingScripts`.
- An agent edits a closed file and gets the old problems with a fresh `updatedAt`: check the file
  event's URI against the module the server has for the file (a second module means a URI spelling
  mismatch, [0002](0002-send-lowercase-drive-letters-to-the-server.md)).
- Answers for open files lag one edit behind: lsp4ij took longer than 200 ms to send the change.

## History

- 2026-11-03: Taken.
