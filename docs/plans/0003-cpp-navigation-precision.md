---
id: plan-0003
title: Tell overloads and inherited members apart in C++ navigation
status: open
date: 2026-09-20
area: cpp-navigation
depends-on: []
context: decisions/0005-find-cpp-declarations-by-reading-headers.md
---

# Tell overloads and inherited members apart in C++ navigation

## TL;DR

Navigation finds the right class and the right member name. It cannot tell two overloads apart, it
follows an inheritance chain only as far as Unreal's type database reports it, and its per-symbol
cache is never invalidated deliberately.

## Why it matters

Overloads are common in the engine API: `SetActorLocation` alone has several. Landing on the first
one is usually close enough to read, but it is wrong, and it is wrong silently.

## What already works

See [decisions/0005](../decisions/0005-find-cpp-declarations-by-reading-headers.md) for the search
itself. In short: the declaring file is found through the file-name and content indexes, a member is
only accepted inside its own type's body, and a member found outside its type is only accepted when
the class it sits in is the type or one of its bases.

## What is missing

### Overloads

`angelscript/getCppSymbol` returns the type and the member name and throws the rest away. The type
database has the parameter list of every method - the plugin already reads `ancestry` from it the
same way. Carrying the resolved overload's parameter types through, and matching them against the
parameter list on the declaration line, would pick the right one.

The matching does not have to be exact: parameter **count** alone separates most Unreal overloads,
and the declaration line is already being parsed far enough to find the name.

### Inheritance beyond the type database

`ancestry` is what Unreal's database reports, which is the script-visible chain. `FTransformFragment`
comes back as `["FTransformFragment"]` although C++ has it deriving from `FMassFragment`, so a member
declared in `FMassFragment` would be rejected by the enclosing-class check and fall through to the
type's own declaration. Reading the base list from the header - it is right there on the declaration
line - would extend the chain where the database stops.

### Cache invalidation

`declarationFileCache` maps `class|member` to the file the declaration was found in, and nothing
calls `clearCaches()`. Entries self-heal when the declaration is no longer in the cached file, so the
cost is a wasted read rather than a wrong jump, but a file deleted or moved is noticed only by
accident.

## Constraints

- **Rider's C++ symbol table is not available**; the evidence is in
  [decisions/0005](../decisions/0005-find-cpp-declarations-by-reading-headers.md). Anything here has
  to be done by reading headers or by extending what the language server reports.
- **The synchronous path has a 250 ms budget**
  ([decisions/0006](../decisions/0006-answer-goto-declaration-synchronously.md)). Matching parameters
  costs nothing extra - the file is already being read - but a design that needs another index query
  per candidate does not fit.
- Changing `getCppSymbol`'s payload means a bundler patch; add any new upstream name to
  `INJECTED_REFERENCES` ([decisions/0001](../decisions/0001-patch-the-language-server-at-bundle-time.md)).

## History

- 2026-09-20 - Written when the search was rewritten; these are the limits it knowingly leaves.
