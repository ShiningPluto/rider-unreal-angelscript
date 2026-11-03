---
id: 0012
title: Serve the MCP API tools from the type database the API tool window reads, and search what a named type inherits
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
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/mcp/ApiItems.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/mcp/ApiDetailsMarkdown.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/typedb/UnrealTypeDatabase.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/typedb/ScriptDeclarations.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/typedb/UnrealDeclaration.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/toolwindow/ApiNode.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptLanguageServer.kt
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/mcp/ApiItemsTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/mcp/ApiDetailsMarkdownTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/typedb/UnrealTypeDatabaseTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/toolwindow/ApiBrowserTest.kt
supersedes: []
superseded-by: null
---

# Serve the MCP API tools from the type database the API tool window reads, and search what a named type inherits

## TL;DR

`angelscript_search_api` and `angelscript_get_api_details` search and describe what the API tool
window does - the saved type database and the server's script declarations
([0011](0011-browse-the-api-from-the-saved-type-database.md)) - instead of upstream's API requests.
Details are written as markdown by a renderer of their own, sharing only the declaration text with
the window's HTML. `Type.word`, when Type is a type's whole name, also searches what that type
inherits, in both. A ref names an item by owner, separator and signature, so it survives searches.

## Context

Measured on 2026-11-03 against a project's saved database: 29,368 types, read in 321 ms.

- `angelscript_get_api_details` failed for every item it was given. It sent the ref as a Gson
  `JsonArray`; lsp4j 1.0 wraps a request's one parameter in an array only if it is a collection
  (`MessageTypeAdapter.handleParameter`), and vscode-jsonrpc calls a handler with an array of params
  spread over its arguments, so the handler got `"property"` for a ref and answered nothing.
- Upstream's `GetAPISearch` (`api_docs.ts`) requires every word in one name, the member's or its
  container's, and a container that matches returns all of its members: `Actor` returned 4,107
  items. It has no `Type.member` form - `AActor.GetActorLocation` and `Math::Lerp` returned nothing -
  and searches a class's own members only, never inherited ones (`forEachSymbol(..., false)`).
- Its refs for namespace functions carry no overload id, so every overload of `FMath::Lerp` had the
  same ref, and `GetAPIDetails` described the last one. A method's id is replaced by the next parse
  of its file, as 0011 describes.
- The window's search already had `Type.member`, overloads apart, and script results, over the same
  data.

## Decision

- The toolset waits for the server to have types as before (`awaitTypes`), then activates
  `UnrealTypeDatabaseService` and waits for it to have read the file; it asks
  `angelscript/getScriptDeclarations` on every call, since script changes without the database doing
  so. Without that answer, Unreal's API is still searched and the result says so.
- `UnrealTypeDatabase.search` takes the script declarations and ranks both together for MCP. The
  window keeps its two lists.
- A search result is an `UnrealSearchHit`: the symbol, and the type the query named when the symbol
  is inherited. With a type's whole name, case aside, before a `.`, what it inherits is searched
  after what it declares, nearest supertype first, minus what a closer class declares again
  (`membersInheritedFrom`), and so are the script mixins of its supertypes. Not for a name merely
  containing the query: `Actor.Get` matched 675 items on the project above, and every class with
  Actor in its name would repeat AActor's members. Not for `::`, which names namespaces and statics.
- An inherited result is named through the type searched and says where it is declared: the window
  notes `from AActor` and reveals it in that type's "Inherited from" group; MCP gives
  `inheritedFrom`. Its ref is the declaring type's member.
- Refs: `["type", name]`, `["namespace", name]`, `["function" | "property", owner, separator, key]`,
  where the key is a function's name with argument types and `const`. `ApiItems.resolve` finds the
  item again in the database or the script declarations.
- `ApiDetailsMarkdown` writes the declaration in a code block, the comment as the database has it,
  and a list of facts: blueprint event, mixin, C++ name, declaring file; for a type, its supertypes,
  member counts, statics, mixins, constructors, or an enum's values. The declaration text comes from
  `UnrealDeclaration`, which the window's `ApiDetailsHtml` uses too.
- `getAPI`, `getAPISearch` and `getAPIDetails` left `AngelScriptLanguageServer`: nothing sends them.

## Alternatives

### Fixing the parameter and keeping upstream's requests

A one-line fix made `getAPIDetails` answer. Search would still have returned thousands of items for
a class's name and nothing for `Type.member`, overloads would still have shared refs, and the tools
would have disagreed with the window over the same API.

### One structure rendered to both HTML and markdown

The window's `DocComment` parses a comment straight into HTML, joining hand-wrapped lines into
paragraphs for a narrow pane. A format-neutral model would have meant rewriting that and its tests
for an output that wants the opposite: an agent reads the result as text, never rendered, and loses
nothing from the comment as written. Only the declaration and a handful of facts are shared, and the
facts are listed twice, in `ApiDetailsHtml` and `ApiDetailsMarkdown`.

### Converting the window's HTML to markdown

It would carry the paragraph joining over, and break on whatever the HTML gains next.

### Inheritance for every type whose name contains the query

See above: the same members once per matching class.

## Consequences

- The MCP tools hold the parsed database for the rest of the session once used, about 60 MB for the
  project above, as the window does. The first call waits for it to be read.
- Every MCP search or details call sends the script declarations: every script function and
  variable in a namespace, with documentation.
- A fact added to the window's details has to be added to `ApiDetailsMarkdown` as well.
- Refs from before this change, and any ref after a rename, resolve to nothing; the tool says to
  search again.
- A request added to `AngelScriptLanguageServer` with an array as its one parameter must take a
  `List`, for the reason in Context.

## How this breaks

- `ApiItemsTest.testEveryKindOfItemIsFoundAgainByItsRef` fails: a kind of item is listed under a
  ref `resolve` cannot follow, such as one whose owner moved between a type and a namespace.
- `angelscript_search_api` fails with "was not read within 30s": `UnrealTypeDatabaseService` did not
  follow the server to `Loaded`. Check its log line `Read N Unreal types from ...`, and 0011's
  signals.
- Results always carry the `note` about script declarations: `angelscript/getScriptDeclarations`
  fails or times out; see `ApiBrowserTest.testBundledServerListsScriptDeclarations`.

## History

- 2026-11-03: Taken.
