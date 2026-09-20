---
id: plan-0002
title: Notice script folders that appear while the server runs
status: open
date: 2026-09-20
area: lsp-client
depends-on: [plan-0001]
context: decisions/0003-send-only-the-script-folders-as-the-workspace.md
---

# Notice script folders that appear while the server runs

## TL;DR

Add a plugin with its own `Script` folder and nothing in it is analysed until the language server
restarts. The same is true of any `.as` file created outside the editor - a branch switch, a pull, a
generator - unless the user happens to open it.

## Why it matters

Asked directly: "if I add a `Script` folder under a plugin, do its AngelScript files get processed?"
The answer today is no, and nothing says so - the files simply have no symbols, which looks like a
broken analyser rather than a stale workspace.

## What already works

- A file the user opens is analysed whether or not it sits under a root: `onDidOpenTextDocument`
  creates and parses the module. It is only named after its whole path, since `getModuleName` finds
  no root prefix to strip - harmless now that upstream v1.9.4 dropped explicit imports.
- Restarting the server, or reopening the project, picks the folder up, provided the `.uplugin` is
  in the index scope by then.
- On the Rider side, `AngelScriptContentRootProvider` watches the VFS and registers a `Script`
  folder as a content root when one is created next to a `.uproject`/`.uplugin`.

## What is missing

Either of these, and they solve different halves:

- **Restart the server when the detected root set changes.** The only option for a genuinely new
  root. Needed anyway for [plan-0001](0001-configurable-script-roots.md), which has the same
  "read once at initialize" problem.
- **Register a file watcher from the client** for `**/*.as` and let the server's existing handler
  take individual files. Covers new files under known roots without a restart - the more common
  case - but cannot add a root.

## Constraints

- **The server has no way to learn about new roots.** It keeps `RootUris` from the initialize call,
  declares no `workspace` capability, and has no `onDidChangeWorkspaceFolders`.
- **The server registers no file watchers.** `connection.onDidChangeWatchedFiles` *is* implemented -
  it loads or reloads the module and resolves it when the queues are idle - but upstream feeds it
  from the VSCode client's `synchronize.fileEvents: '**/*.as'`, which is client-side configuration
  with no lsp4ij equivalent. lsp4ij gates `workspace/didChangeWatchedFiles` on patterns registered
  through `client/registerCapability` (`LSPFileListener.isMatchFilePattern` ->
  `FileSystemWatcherManager`), and the server registers none, so it receives nothing. Registering
  from the client side is therefore the plugin's job.
- **The Rider-side watcher has its own gap**: it reacts to a created folder named `Script` or a
  created `.uproject`/`.uplugin`, so a whole plugin directory arriving at once produces a single
  create event for the plugin folder and is missed. Reopening the project is the current cure.

## History

- 2026-09-20 - Written after the behaviour was traced through both the server and lsp4ij.
