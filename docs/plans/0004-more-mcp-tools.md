---
id: plan-0004
title: Give agents C++ declarations, semantic queries and asset lookups for AngelScript
status: open
date: 2026-09-23
area: mcp
depends-on: []
context: ../CONTRIBUTING.md
---

# Give agents C++ declarations, semantic queries and asset lookups for AngelScript

## TL;DR

The plugin serves seven MCP tools through Rider's built-in MCP server - problems, API search, API
details, status, symbol search, references and type hierarchy. One more is worth having: where an
AngelScript symbol is declared in C++. The plugin can already find it; what is missing is the tool
around it. The Blueprints deriving from a script class need no tool of the plugin's: Rider's own
`get_class_hierarchy` finds them, given the script subclasses the type hierarchy tool lists.

## Why it matters

An agent editing AngelScript in an Unreal project spends most of its effort on the boundary with
C++, and that is exactly where Rider's own tools go blind. Checked against a live project on
2026-09-23:

- `search_symbol` returns nothing for script symbols. Searching for a script class by name
  found only a C++ header with a similar name.
- `get_file_problems` rejects `.as` files outright: "File is not included in any project of the
  current solution". Script folders are content roots, not solution projects.
- `get_symbol_info` does work, because it goes through lsp4ij's hover. It gives the signature and
  documentation, but no location.

Without the tools below, an agent greps: for a C++ declaration it has to guess the C++ name from the
script name (namespaced statics, mixins and property accessors all differ), and for references it
cannot tell a same-named member of another class from the one it means.

## What already works

- `AngelScriptMcpToolset` (`mcp/`), registered through the optional dependency in
  `angelscript-mcp.xml`, with `angelscript_status`, `angelscript_get_problems`,
  `angelscript_search_api` and `angelscript_get_api_details`. Landed with this plan.
- `angelscript_search_symbols` over `workspace/symbol` and `workspaceSymbol/resolve`, and
  `angelscript_find_references` over `textDocument/references`, through the server proxy lsp4ij
  hands out. The server finds members only for a query of five letters or more and matches one
  piece of text, so `ScriptSymbolQuery` asks it for the longest piece and filters for the query
  forms `angelscript_search_api` takes. References get their line's text and, from the plugin's
  parse tree, whether they read or write (`AngelScriptAccess`); the file asked about is analysed
  again first, as `angelscript_get_problems` does.
- `angelscript_get_type_hierarchy` over `typeHierarchy/supertypes` and `typeHierarchy/subtypes`.
  Each answers one level, and the server finds the type an item stands for by the name in its
  `data` alone (`TypeHierarchyBundleTest`), so the walk starts from an item made of a name: a
  type C++ declares has no position to prepare one from.
- `AngelScriptDiagnosticsStore` keeps every diagnostic the server publishes, for all files.
- `AngelScriptUnrealStatusService.query()` answers "can the server analyse anything" without
  starting it, and the toolset's `awaitTypes()` starts it and waits for a type database.
- `AngelScriptMcpToolsetTest` runs the MCP server's own reflection over the toolset, so a tool
  signature it cannot handle fails the build instead of silently removing the whole toolset.

## What is missing

1. **`angelscript_find_cpp_declaration(filePath, line, column)`** - the C++ header and line where
   the symbol at a script position is declared. `angelscript/getCppSymbol` already returns the
   class, the member and the ancestry, and `CppSourceSearch` already finds the declaration. What is
   in the way is that `AngelScriptCppNavigationHandler` does both inside a `GotoDeclarationHandler`,
   so the lookup has to be split from the navigation first. The tool has no caret waiting on it, so
   it can run the full search instead of the synchronous budget.

Checked and not needed:

- **`angelscript_find_assets(className)`**, the Blueprints and data assets deriving from a script
  class, over the asset database the editor sends the server (`GetAssetsImplementing`). Rider's
  tools answer the same question better; tried on a live project on 2026-11-03:
  - `get_class_hierarchy` and `search_assets` (whose default source is Rider's index of `.uasset`
    files) find the Blueprints deriving from a script class, given its name as script writes it,
    without the editor running. By their code they follow a Blueprint's own subclasses too; the
    project had none to try. They miss a Blueprint under a script *subclass*: they follow
    subclasses through the C++ symbol index only. For a class with three Blueprints of its own and
    four under its script subclasses, they listed three; asked for a subclass, they found its.
  - They miss data assets, which are not Blueprints. `search_assets` with `source: "editor"`
    finds those through the editor's asset registry, but only given the class name without its
    prefix: with it, the class is not found, the filter is dropped, and unrelated assets come back
    labelled with the class asked for. Editor mode never lists Blueprints, whose asset class is
    `Blueprint`.
  - The server's database is no better and needs the editor connected: it records a Blueprint
    under its nearest script ancestor only, and is looked up by exact name, so it misses the
    Blueprints under a subclass too. The editor's `NativeParentClass` tag names that ancestor,
    since the engine counts script classes as native.
  - `ue_execute_python` with the asset registry's `get_derived_class_names` finds all of them,
    with the editor running.

  So `angelscript_get_type_hierarchy` lists a class's script subclasses, and its description sends
  agents to `get_class_hierarchy` with each, and to editor-mode `search_assets` for data assets.

- **Debugging.** The DAP session runs through lsp4ij on the platform's XDebugger, which is what
  Rider's `xdebug_*` tools drive. Try those against an AngelScript session before building
  anything.
- **Opening assets or creating Blueprints** (`angelscript.openAssets`, `angelscript.createBlueprint`).
  These are side effects in the editor, and UnrealLink's `ue_execute_python` already does editor
  automation more generally.
- **Rename.** The server supports it, but applying a cross-file `WorkspaceEdit` from an agent is a
  bigger risk than the grep-and-edit it replaces. Revisit if agents keep getting renames wrong.
- **Hover, completion, highlighting, folding, inlay hints**: covered by `get_symbol_info`, or not
  meaningful to an agent.

## Constraints

- **Positions.** Tools take and return 1-based lines and columns like Rider's own tools; LSP is
  0-based.
- **URIs.** Anything sent to the server goes through `AngelScriptFileUriSupport.normalizedUri`
  ([decision 0002](../decisions/0002-send-lowercase-drive-letters-to-the-server.md)); anything
  coming back may be in any of three spellings, which `AngelScriptDiagnosticsStore.pathFromUri`
  already handles.
- **lsp4j exists twice.** The plugin compiles against the SDK's lsp4j 0.21 and runs against lsp4ij's
  1.0 - see CONTRIBUTING.md. Compare the signature of every lsp4j member a new tool touches in both
  jars; `Diagnostic.getMessage()` is the one known to differ.
- **An array parameter must be a `List`.** vscode-jsonrpc calls a handler with an array of params
  spread over its arguments, and lsp4j wraps a request's one parameter in an array of its own only
  if it is a collection. A Gson `JsonArray` is not one: sent as `getAPIDetails`'s parameter, the
  handler got only the first element of the item, and `angelscript_get_api_details` found nothing
  for any item until it stopped asking the server.
- **Nothing answers without a type database.** The server's own API requests wait for one
  indefinitely, so check the state before sending, as `awaitTypes()` does.
- **The asset database is live-only.** It arrives from a connected editor and, unlike the type
  database, is not cached, so `angelscript_find_assets` must say when it has no editor rather than
  return an empty list.
- **C++ declarations** are found by reading headers, not through Rider's C++ index
  ([decision 0005](../decisions/0005-find-cpp-declarations-by-reading-headers.md)); overloads and
  inherited members are still imprecise ([plan-0003](0003-cpp-navigation-precision.md)).
- **Documentation is truncated upstream.** The language server keeps only the last line of a block of
  `//` comments as a declaration's documentation (`comment_documentation` in the upstream
  `angelscript.pegjs` overwrites instead of appending), so hover, `get_symbol_info` and API details
  show one line where the source has several. `/** */` comments survive whole.

## History

- 2026-09-23 - Written when the first four tools landed, from the survey of what the built-in tools
  cover for AngelScript.
- 2026-11-03 - `angelscript_search_symbols` and `angelscript_find_references` landed, checked
  against the bundled server: members are told apart by their class, and a method's overrides and
  `Super::` calls are found.
- 2026-11-03 - `angelscript_get_type_hierarchy` landed; `angelscript_find_assets` dropped after
  Rider's asset tools were tried against a live project.
