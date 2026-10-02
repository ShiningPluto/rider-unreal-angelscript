---
id: plan-0006
title: Script folders become content roots the first time a project is opened
status: open
date: 2026-10-02
area: editor
depends-on: []
context: plans/0002-notice-script-folders-added-later.md
---

# Script folders become content roots the first time a project is opened

## TL;DR

`AngelScriptContentRootProvider` looks for `Script` folders once, at the first smart mode after the
project opens. On a project Rider has never indexed, that is before the solution has loaded, so it
finds none and registers nothing. Find Usages in the "Solution" scope then drops every AngelScript
reference.

## Why it matters

Everyone who installs the plugin and opens their project hits this on the first session. It is
silent: Find Usages reports nothing in the default scope, and "All Places" shows the references, so
the plugin looks broken in a way that depends on the scope.

Seen on 2026-10-02 in a fresh `runLocalRider` sandbox on a large UE5 project (Rider 2026.2.3). The
daily Rider on the same project works, because its content roots were added in an earlier session
and are kept in the project model (`Content root already exists in module` for every folder).

## What already works

- Once registered, the content roots persist across sessions, so a second open is expected to be
  fine: the index then holds the `.uproject` and `.uplugin` files from the start. Not yet confirmed
  by restarting the sandbox.
- The usage view drops results outside the chosen scope itself: `SearchForUsagesRunnable` checks each
  usage with `UsageViewManagerImpl.isInScope` and only counts the rest as out of scope. lsp4ij does
  not filter by scope at all (the check in `LSPUsageSearcher` is commented out), so registering the
  folders is the whole fix.

## What is missing

Detection has to run again once the solution has loaded, not only at the first smart mode. Options:

- Re-run it when the project model changes (a workspace model or module roots listener). Adding a
  content root changes the model too; `addContentRootIfNotExists` is idempotent, but the listener
  should not rescan on its own change.
- Re-run it when Rider reports the solution as loaded, if there is a usable signal for that.
- Find `.uplugin` files from the filesystem, as `ScriptFolderDetector.detectScriptRoots` already does
  for the project's own `Script` folder, rather than waiting for the index.

## Constraints

- Sandbox log of the failure (`build/idea-sandbox/RD-2025.2.5/log_runLocalRider/idea.log`):
  - 12:31:05 - first scan of the project: 324 files.
  - 12:31:07 - smart mode; detection runs: `Detected 0 Script folders in 12ms`.
  - 12:32:28 - scan of 81 430 files, the solution's content.
  - 12:33:28 - "solution is fully loaded".
- `detectScriptFolders` queries `FilenameIndex` in `GlobalSearchScope.projectScope`, so it only sees
  `.uproject`/`.uplugin` files that are already in the project model and indexed.
- Rider fills the project model in stages for a long time after the first smart mode; the gap above
  was about two and a half minutes on that project.

## History

- 2026-10-02 - Written after Find Usages in the "Solution" scope found no AngelScript references in a
  fresh sandbox while the daily Rider did; traced to the detection log above.
