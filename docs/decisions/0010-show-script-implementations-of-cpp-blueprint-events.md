---
id: 0010
title: Show script implementations of C++ blueprint events from the frontend, and hide Rider's warning for them there
status: accepted
date: 2026-09-25
area: editor
applies-to:
  plugin: 0.9.10
  lsp4ij: "0.21"
  language-server: v1.9.4
  rider: "2025.2"
touches:
  - scripts/bundle-lsp.js
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/navigation/CppSourceSearch.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/overrides/ScriptOverrideService.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/overrides/ScriptOverridesCodeVisionProvider.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/overrides/NotImplementedWarningSuppressor.kt
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/navigation/CppBlueprintEventTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/overrides/ScriptOverridesTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/overrides/ScriptOverridesBundleTest.kt
supersedes: []
superseded-by: null
---

# Show script implementations of C++ blueprint events from the frontend, and hide Rider's warning for them there

## TL;DR

Rider says a `BlueprintImplementableEvent` "is not implemented in any blueprint" when script classes
implement it, because ReSharper C++ only looks at `.uasset` files and cannot be told about anything
else. The plugin asks the language server for every script override, shows them on the C++
declaration with a frontend code vision provider, and declines the warning as it arrives on the
frontend. That decision is taken once per highlighter, so the answer has to be cached before the
warning arrives.

## Context

The warning (`CppUEBlueprintImplementableEventNotImplemented`) and the "not implemented in
blueprints" code vision entry both come from `UnrealBlueprintFunctionsDaemonStageProcess` in
`JetBrains.ReSharper.Feature.Services.Cpp.dll`, decompiled from Rider 2026.2.2. Its
implementations are `UEAssetUsagesSearcher.GetGoToInheritorsResults` over the Unreal asset cache.
That searcher is a concrete `[SolutionComponent]` with no provider interface, so nothing else can
contribute implementations. The C++ assemblies are not in any published SDK anyway
([0005](0005-find-cpp-declarations-by-reading-headers.md)).

The backend has no general way to drop a highlighting either. `FilteringHighlightingConsumer`
suppresses only by severity settings and `// ReSharper disable` comments.

The frontend has a way. `com.intellij.rdclient.highlighterSuppressionHandler`
(`FrontendHighlighterSuppressionHandler.shouldSuppress(HighlighterModel, Document)`) is asked by
`FrontendMarkupContributor` about each highlighter the backend sends, before it is added. A
suppressed highlighter is skipped and never asked about again. The warning arrives as a
`DefaultHighlighterModel` whose `info.inspectionId` is the configurable severity id, filled in by
`RiderDefaultHighlighterModelCreatorBase`. Its range is the declarator name. Checked in the 2025.2.5
SDK (`lib/product-client.jar`) and in Rider 2026.2.2 (`lib/intellij.rd.client.jar`). Neither class
is marked `@ApiStatus.Internal`, but neither is documented.

Only the language server knows which C++ class declares the event a `BlueprintOverride` overrides:
that needs the script classes and Unreal's type database together. Measured on a project with about
2,500 `BlueprintOverride`s, starting on the cached type database: the answer settles 10 s after the
server starts, lists 2,349 overrides of C++ events, and takes 20-35 ms per request. The rest
override events declared in script, which have no C++ side.

## Decision

- **Server:** `angelscript/getScriptOverrides`, injected by the bundler, lists every
  `BlueprintOverride` whose event a C++ class declares. Each entry has the class, the event's script
  name, the script class and a location. `settled` says whether the list is complete.
- **Script names:** the client maps C++ names to script names itself (`CppBlueprintEvent.scriptNames`),
  following the AngelScript plugin's `GetScriptNameForFunction`: a `ScriptName`/`ScriptMethod` meta,
  otherwise the `K2_`, `BP_`, `AS_`, `Received_` and `Receive` prefixes dropped. For example,
  `AActor::ReceiveBeginPlay` is overridden as `BeginPlay`.
- **Cache:** `ScriptOverrideService` fetches the whole list when the server starts, repeats until it
  is settled, and fetches again 2 s after an edit to a script document or a change to an `.as` file
  on disk.
- **Code vision:** `ScriptOverridesCodeVisionProvider` reads the header text with `CppSource`, since
  the frontend has no C++ PSI. It shows "N AngelScript implementations" on each event with overrides;
  clicking opens the implementation, or a list when there are several.
- **Warning:** `NotImplementedWarningSuppressor` declines that one inspection id when the event under
  its exact range has script implementations. When the range is stale after an edit, it declines
  nothing.

## Alternatives

### Feed the implementations to ReSharper C++

Not possible, see Context. Referencing the C++ assemblies out of a Rider install was already
rejected in [0005](0005-find-cpp-declarations-by-reading-headers.md). Even with them, there is no
extension point to contribute to.

### Index the scripts on the frontend instead of asking the server

A file index of `BlueprintOverride` methods and script base classes gives the script side, but not
where the chain meets C++. A script class deriving from a C++ subclass of the declaring class needs
C++ inheritance, which only the type database has. The server already holds both.

### Have the server report the C++ name

The type database carries `unrealname` only for events whose script name differs. The server's
parse keeps it only when a `ufunction` key is present, which engine events lack: in the project
above, 407 of 1,470 events carry `unrealname` and 230 carry `ufunction`. Reading it for events
would change `findMethodByUnrealName` and the delegate-binding diagnostics built on it.

### Hide Rider's "not implemented in blueprints" code vision entry too

It is true, so it stays. The script implementations get an entry of their own next to it.

### Turn the inspection off

That also loses the warning for events that really are implemented nowhere.

## Consequences

- A warning already on screen does not update when an override is added or removed. Rider asks
  about a highlighter only when the backend sends it, which happens when the header is reopened or
  the declaration edited. The code vision entries do update.
- Warnings that arrive before the server has answered are shown. At startup the answer comes long
  before ReSharper's asset cache, which the warning waits for.
- Events in engine headers get code vision too (`AActor::ReceiveBeginPlay` has dozens of script
  implementations in a typical project). Rider does not warn in those files, so nothing is hidden
  there.
- The server has to be running. It starts with the project, see `AngelScriptLspInitializer`.
- `FrontendHighlighterSuppressionHandler`, `DefaultHighlighterModel` and `HighlightInfoModel` come
  from Rider's `rdclient` and generated protocol model. Their names and signatures are what this
  depends on.

## How this breaks

- The entry shows implementations but the warning stays after reopening the header. Either the
  handler is no longer asked, or the inspection id changed. Check that some jar under `lib/` still
  declares `rdclient.highlighterSuppressionHandler` and that `FrontendMarkupContributor` still calls
  it. Check `HIGHLIGHTING_ID` on `CppUEBlueprintImplementableEventNotImplementedWarning`. With debug
  logging on for `NotImplementedWarningSuppressor`, every warning it hides is logged.
- No entries anywhere. `idea.log` has no "Script overrides of C++ blueprint events" line, so the
  request failed: `ScriptOverridesBundleTest`, or a name the handler calls that the bundle no longer
  defines at the top level.
- Entries missing only for renamed events. The AngelScript plugin changed its naming rule; compare
  `GetScriptNameForFunction` in `Helper_FunctionSignature.h` with `CppBlueprintEvent.scriptNames`.

## History

- 2026-09-25 - Taken after script implementations were reported as "not implemented in any
  blueprint" on a real project. Checked against its headers and scripts through the bundled server,
  but not yet in a running Rider.
- 2026-09-26 - Confirmed in Rider 2026.2.2 on that project: the entries show on the declarations,
  clicking them opens the implementations, and the warning is gone from events implemented in
  script. The tooltip names at most five classes, after one naming all 41 overriders of an event ran
  across the screen.
