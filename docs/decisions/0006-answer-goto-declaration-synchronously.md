---
id: 0006
title: Answer go-to-declaration while the caret waits, and hand back a real target
status: accepted
date: 2026-09-20
area: cpp-navigation
applies-to:
  plugin: 0.9.10
  rider: "2025.2"
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/navigation/AngelScriptCppNavigationHandler.kt
verified-by: []
supersedes: []
superseded-by: null
---

# Answer go-to-declaration while the caret waits, and hand back a real target

## TL;DR

Returning `null` from `getGotoDeclarationTargets` and navigating later from a background thread
means the platform reports "Cannot find declaration to go to" - it has no way to know a jump is
coming. The search now runs inside the handler with a 250 ms budget and returns a navigable target;
only a search that overruns falls back to navigating in the background.

## Context

The handler knew the symbol was a C++ one, spawned the search on a pooled thread and returned
`null`. To the platform `null` means "this handler found nothing"; with no other handler producing a
target it showed the hint immediately, and the jump landed a moment later.

It was reported as "the first jump to a function usually shows the message". The first jump is
simply the slow one - a cold engine header has to be read and an editor tab opened - so the hint has
time to be seen. Measured from the plugin's own log on a real project: first lookup for a symbol
1-80 ms, repeat lookups ~10 ms, because the file that held the declaration is cached per symbol.

## Decision

- `findDeclarationWithin` runs the search on the calling thread inside a read action, with a
  deadline checked between candidate files. Budget: 250 ms.
- Found in time: return a target and let the platform navigate.
- Not found in time, or the Unreal-editor strategy is selected: fall back to the previous behaviour,
  a background search that navigates itself.

The target is a `FakePsiElement` (`CppDeclarationTarget`) holding the file and the offset, because
C++ has no PSI on Rider's frontend to return. It answers `navigate`, `getTextOffset`, `getTextRange`
and `getContainingFile`, which between them cover both routes the platform takes to open a target -
calling `Navigatable.navigate`, or building a request from the containing file and the text offset.
Its parent is the target's own `PsiFile`: the platform short-circuits a target whose containing file
is the one the user is in into a jump inside the current editor. When no `PsiFile` can be made for
the target, the handler returns `null` and the background path takes over, rather than returning
something the platform cannot open.

Rider does the same thing for its own backend-resolved symbols:
`com.jetbrains.rdclient.gotoType.ProtocolNavigationItem` is a `FakePsiElement` overriding the same
four members.

## Alternatives

### Keep it fully asynchronous and live with the message

What shipped first. The message is wrong - a declaration was found - and it trains the user to
distrust the feature.

### Return an empty array instead of null

The platform treats "no targets" the same either way; the hint still appears.

### Navigate ourselves and suppress the platform's hint

There is no API for a handler to say "handled, do not report". Producing a target is the supported
way to say it.

### Do the whole search synchronously with no budget

An uncached lookup that has to read a dozen engine headers off a cold disk would block the UI for as
long as that takes. The budget bounds it to 250 ms plus whichever file was being read.

## Consequences

- The EDT does up to 250 ms of index and file work on a go-to-declaration over a C++ symbol, on top
  of the two LSP calls the handler already makes (200 ms for the ignore-pattern check, 300 ms for
  `getCppSymbol`).
- The same handler is consulted for Ctrl-hover underlining, so hovering over C++ symbols also pays
  for a lookup. The per-symbol cache makes repeats cheap; if hovering ever feels heavy, the honest
  fix is to do the synchronous search only for a real click.
- `CppDeclarationTarget` depends on how the platform opens targets. If both routes change, the
  symptom is "no message and no jump" - worse than the message it removed, and worth reverting to
  the background path if seen.

## How this breaks

- "Cannot find declaration to go to" comes back: the search is overrunning its budget (check the
  gap between `C++ symbol:` and `Found ...` in the log) or `PsiManager` cannot make a file for the
  target.
- Nothing happens at all on click: the platform no longer uses either navigation route.
- Typing or hovering in an AngelScript file feels sticky: the budget is too generous for the machine
  or the project.

## History

- 2026-09-20 - Taken after the message was reported on first jumps; the fully asynchronous behaviour
  it replaces is still the fallback path.
