---
id: plan-0001
title: Let script root folders be configured
status: open
date: 2026-09-20
area: lsp-client
depends-on: []
context: decisions/0003-send-only-the-script-folders-as-the-workspace.md
---

# Let script root folders be configured

## TL;DR

Detection finds the `Script` folder next to a `.uproject` or `.uplugin`. Script kept anywhere else -
outside the project directory, in a shared library checkout, in a plugin the index does not reach -
is invisible, and the user has no way to say where it is.

## Why it matters

When detection finds nothing at all the plugin falls back to Rider's content roots, so such a
project still works, slowly. When detection finds *something* but misses a second location, that
second location is simply not analysed and nothing says so.

## What already works

`ScriptFolderDetector.detectScriptRoots` returns:

- `<project base>/Script`, resolved without the index, because the server can start while the IDE is
  still indexing;
- every `Script` folder next to a `.uproject` or `.uplugin`, once the index can answer.

Both senders use it: the workspace folders (`AngelScriptWorkspaceFolderFeature`) and
`initializationOptions.additionalScriptRootFolders` (`AngelScriptConnectionProvider`).

## What is missing

A project-level setting holding extra paths:

- add the list to `AngelScriptLspSettings` and a row in the settings UI;
- union it into `ScriptFolderDetector.detectScriptRoots`, which both senders already read.

## Constraints

- **The roots are read once, at initialize.** Unlike `unrealConnectionPort`, which the server
  re-reads on every configuration push, changing the folders means restarting the server. The
  settings UI has to say so, the way the type cache setting already does for the one transition that
  needs a restart.
- Paths must be sent through `AngelScriptFileUriSupport.normalizedUri` - see
  [decisions/0002](../decisions/0002-send-lowercase-drive-letters-to-the-server.md).
- Adding roots costs startup time: every root is globbed before analysis is complete. A setting that
  invites users to add the engine tree would undo
  [decisions/0003](../decisions/0003-send-only-the-script-folders-as-the-workspace.md).

## History

- 2026-09-20 - Written when detection landed; the detection half is done, the configuration half is
  not.
