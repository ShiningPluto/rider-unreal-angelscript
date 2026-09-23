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

The plugin serves four MCP tools through Rider's built-in MCP server - problems, API search, API
details and status. Three more are worth having, in this order: where an AngelScript symbol is
declared in C++, semantic queries over script (symbols, references, type hierarchy), and which
Blueprint assets derive from a script class. Each is backed by something the plugin or the language
server can already do; what is missing is the tool around it.

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
- `AngelScriptDiagnosticsStore` keeps every diagnostic the server publishes, for all files.
- `AngelScriptUnrealStatusService.query()` answers "can the server analyse anything" without
  starting it, and the toolset's `serverWithTypes()` starts it and waits for a type database.
- `AngelScriptMcpToolsetTest` runs the MCP server's own reflection over the toolset, so a tool
  signature it cannot handle fails the build instead of silently removing the whole toolset.

## What is missing

1. **`angelscript_find_cpp_declaration(filePath, line, column)`** - the C++ header and line where
   the symbol at a script position is declared. `angelscript/getCppSymbol` already returns the
   class, the member and the ancestry, and `CppSourceSearch` already finds the declaration. What is
   in the way is that `AngelScriptCppNavigationHandler` does both inside a `GotoDeclarationHandler`,
   so the lookup has to be split from the navigation first. The tool has no caret waiting on it, so
   it can run the full search instead of the synchronous budget.
2. **Semantic queries** - `angelscript_search_symbols(query)` over `workspace/symbol`,
   `angelscript_find_references(filePath, line, column)` over `textDocument/references`, and
   supertypes/subtypes over the type hierarchy requests. All are implemented by the server already;
   the tool sends them through the lsp4ij server item as the API tools do.
3. **`angelscript_find_assets(className)`** - the Blueprint assets that derive from a script class,
   for judging the impact of a change before making it. The server has
   `GetAssetsImplementing(className)`, but only the code lens reaches it, so it needs a custom
   request added in `scripts/bundle-lsp.js`, with its `✓` check.

Checked and not needed:

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
- **Nothing answers without a type database.** The server's own API requests wait for one
  indefinitely, so check the state before sending, as `serverWithTypes()` does.
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
