---
id: 0005
title: Find C++ declarations by reading headers, because Rider's C++ index is out of reach
status: accepted
date: 2026-09-20
area: cpp-navigation
applies-to:
  plugin: 0.9.10
  rider: "2025.2"
  language-server: v1.9.4
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/navigation/CppSourceSearch.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/navigation/AngelScriptCppNavigationHandler.kt
  - scripts/bundle-lsp.js
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/navigation/CppSourceSearchTest.kt
supersedes: []
superseded-by: null
---

# Find C++ declarations by reading headers, because Rider's C++ index is out of reach

## TL;DR

Navigating from AngelScript to C++ has two stages: the language server says *which* C++ symbol this
is, and the plugin finds *where* it is declared. Stage two cannot use Rider's C++ symbol table - it
lives in the backend and no published SDK exposes it - so it reads headers, with enough structure to
tell a declaration from a mention and enough context to tell the right class's member from another
class's member of the same name.

## Context

Stage one is `angelscript/getCppSymbol`, injected by the bundler: it returns the owning C++ type,
the member name, and (since 2026-09-20) the type's C++ ancestry, read out of Unreal's type database.
Stage two used to ask `FilenameIndex` for a header named after the type - `AActor` meant `Actor.h` -
and then match the member name anywhere in that file. On a real project that produced:

- `FTransform::GetLocation` opening a project header that declares its own `GetLocation`;
- `FTransformFragment::SetTransform` opening a header declaring another struct's
  `SetTransform`;
- nothing at all for properties, which were never matched because only names followed by `(` were;
- nothing for global functions, whose only declaration is the signature string the AngelScript
  plugin binds them with.

### Why Rider's C++ symbol table cannot be used

Checked at 2025.2.5:

- ReSharper C++ ships only inside Rider: `lib/ReSharperHost/JetBrains.ReSharper.Cpp.dll`,
  `JetBrains.ReSharper.Feature.Services.Cpp.dll`, `JetBrains.ReSharperCpp.RiderPlugin.dll`.
- No published SDK package carries reference assemblies for it. `JetBrains.ReSharper.SDK`,
  `JetBrains.ReSharper.SDK.Internal`, `JetBrains.Rider.SDK` and `JetBrains.Rider.RiderSDK.Internal`
  contain no `*Cpp*` assembly at all.
- The frontend has no C++ PSI either: `plugins/rider-cpp` is debugger, run configurations, file
  types and Unreal project files. C++ code insight is entirely backend, over the RD protocol.

Referencing the DLLs out of a local Rider install would compile, but it binds the plugin to
unpublished internals with no API contract, breaks on Rider updates, and makes the build depend on a
machine-specific path.

There *is* a name-based frontend entry point: `com.jetbrains.rdclient.gotoType.ProtocolGotoSymbolContributor`
implements `ChooseByNameContributor` over the protocol - the machinery behind Go to Symbol, which
does cover C++ in an Unreal solution. It was not taken: it is an internal package, every query is a
backend round trip (so it cannot run inside the synchronous budget of
[0006](0006-answer-goto-declaration-synchronously.md)), the base class is built around a popup
session, and it answers by name, which still leaves the class filtering to us. It remains the
obvious escalation if reading headers turns out not to be enough.

## Decision

`CppSource` (in `CppSourceSearch.kt`) reads a file once, marks where comments and string literals
are, and answers three questions: where a type is declared, where a member is declared **inside a
given type's body**, and which type encloses a given offset.

It knows what Unreal actually writes: `UCLASS`/`USTRUCT`/`UENUM`/`UFUNCTION`/`UPROPERTY`,
namespace-wrapped enums (`namespace EDrawDebugTrace { enum Type ... }`), `struct alignas(...) T`,
the UE5 large-world aliases (`FVector` is a macro-made name for `TVector`, which is why the plugin
also searches for the `T`-prefixed template), properties as well as functions, and the script
signatures bound from C++ as string literals in `Bind_*.cpp`.

`AngelScriptCppNavigationHandler` orders the search by how certain each step is:

1. files that name **both** the type and the member - the index is asked for each word and the
   answers intersected, which is what makes a declaring file findable when neither its name nor its
   path says anything about the type;
2. files that look like they declare the type at all (file-name index plus content index, ranked:
   headers before sources, a name matching the type before one sharing a word with it before the
   rest, `Public/` and `Classes/` before `Private/`, never build output);
3. the member declared outside its type - inherited, or bound onto an engine type from elsewhere -
   accepted only when the class the declaration sits in is the type or one of its bases;
4. failing all of that, the type's own declaration: the right type in the right file beats a
   same-named member of something unrelated.

Steps 1 and 2 confirm the type is really declared in the file and search the member inside that
type's body, so another class in the same header cannot be mistaken for it. Nothing is opened until
the declaration has been found in it, so ranking only decides the order files are read in, never the
outcome.

## Alternatives

### Ask the Unreal editor to navigate (the existing `UNREAL_ENGINE` strategy)

Still available as a setting. It opens the file in whatever IDE the editor is configured for, which
is not Rider, and it needs a running editor.

### Derive the locations from the type database

The database Unreal sends has no file information - types carry documentation and members, not a
header path.

### Verify candidates through Rider's backend

The user's suggestion, and the reason it was not needed: the only information that distinguishes
`FTransformFragment::SetTransform` from another struct's `SetTransform` is which class body
the line sits in, and that is computable from the text in a couple of milliseconds. A backend round
trip per candidate would cost more and buy the same answer.

## Consequences

- **Overloads are not distinguished.** The first declaration of the name inside the owning type
  wins. Fixing it means carrying the resolved overload's parameter types through `getCppSymbol` -
  the type database has them.
- Accuracy depends on the frontend content index covering C++ files. If it ever stops, the
  file-name candidates carry the search alone and recall drops to what it was before.
- `getCppSymbol` now returns `ancestry`; `GetTypeByName` is therefore in the bundler's
  `INJECTED_REFERENCES` ([0001](0001-patch-the-language-server-at-bundle-time.md)).
- The search reads whole files. It is capped: 12 candidates per step, 4 MB per file, and the
  synchronous path has a deadline ([0006](0006-answer-goto-declaration-synchronously.md)).

## How this breaks

- Navigation lands in the wrong file. The log line `Found <Type>::<Member> in <path>` says which
  step produced it; a target whose enclosing class is not the type means step 3 accepted something
  it should not have.
- Navigation stops finding anything for a category of symbol - most likely a C++ idiom `CppSource`
  does not parse. `CppSourceSearchTest` pins the ones that are known.
- A new Unreal version changes how a type is spelled (another alias scheme like large-world
  coordinates) and the type stops being found at all.

## History

- 2026-09-20 - Rewritten from the file-name-guessing version. Verified against a real UE5 tree:
  `AActor` and its members, `FHitResult`, `UActorComponent`, `TVector`, `TTransform`,
  `EDrawDebugTrace`, the `Print*` bindings in `Bind_Logging.cpp`, and a project
  `UBlueprintFunctionLibrary`.
- 2026-09-20 - Added the type/member index intersection, the enclosing-class check and the ancestry
  from the type database, after two measured wrong jumps.
