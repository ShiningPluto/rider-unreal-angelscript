# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## Unreleased

### Added
- The Unreal AngelScript API tool window lists every class's members, inherited ones included. It used to show only namespaces: `APawn::` held APawn's two static functions and nothing else, and a class's own methods and properties could only be found by searching, never those it inherits. The language server's API requests have no way to list a class's members, so the tool window now reads the type database the server saves. Classes, structs, enums, delegates, namespaces and globals each have a group. A class lists its static members, constructors, its own properties and functions, and then what it inherits, a group per ancestor class, leaving out what a subclass declares again. Rows show argument and return types, so overloads can be told apart. The details pane shows the selected overload's own signature, its documentation with parameters listed, and the C++ name of a function script knows by another name, such as `K2_SetActorLocation` or `ReceiveBeginPlay`. Search covers types, members and namespaces and finds an event by its C++ name; `APawn.Get` or `Math::` searches one type's or namespace's members. What script declares is not in the type database and comes from the language server, with its arguments, return type and documentation, so it is listed and described as Unreal's API is. Script namespaces are listed under "Script namespaces". Functions and variables script declares outside any namespace, or adds to a namespace Unreal declares, are listed after Unreal's, followed in italics by the file that declares them. Script mixins are listed with the class they extend, under "Script mixins", together with those of its supertypes, marked with the supertype they extend. The constructors of script structs are not listed, as script types are not. Search covers what script declares too. What script declares is asked for again whenever the tool window is shown and on Refresh, and the rows that were expanded stay expanded. The tool window shows the database the language server is using, or says why it has none. It reads the database the first time it is opened and then keeps it in memory, about 60 MB for a project with 29,000 Unreal types. Opening it starts the language server if nothing else has, as it always did.

### Changed
- The type database cache setting is now "Use the saved type database until an Unreal editor connects", and only decides whether a saved database is loaded. Every database an editor sends is saved, whatever the setting, because the API tool window reads it. Turning the setting off still means no analysis until an editor connects, and still restarts the language server when a saved database is in use.

### Fixed
- The API tool window's icon no longer disappears into the blue button of the active tool window in the New UI. The platform recolours an active tool window's icon by rewriting the colours on each SVG element, and the icon set its colour once on the root element, which is never rewritten, so it stayed grey on blue.
- The API tool window describes the script function selected rather than another overload of it. The language server names a script function by an id that the next parse of its file replaces, and given an id it no longer knows, it describes the last function of that name, so overloads that had their own documentation all came to show the same one.

### Internal
- The bundled language server answers `angelscript/getScriptDeclarations`, which lists every function and variable script declares in a namespace, mixins included, each with its signature, documentation and file. Upstream's API requests could not serve the tool window: `getAPI` returns only namespaces at the root, skips mixins everywhere, and names a function rather than describing it, and `getAPIDetails` then finds it by an id each parse replaces.

## 1.0.1 - 2026-10-21

### Changed
- The plugin no longer uses API that the IntelliJ Platform or lsp4ij keep for themselves. The JetBrains Marketplace's compatibility check reported it for 1.0.0. Nothing is meant to behave differently.

### Internal
- Completion corrects each item before lsp4ij builds its completion entry. It used to replace that entry with a subclass of lsp4ij's internal `LSPCompletionProposal`, which reached its private members by reflection. See `docs/reference/lsp-completion-workaround.md`.
  - Edits at the same position are merged, so lsp4ij cannot apply them in reverse.
  - An item completed right after `::` is given the range it replaces.
  - A decorator formats what was inserted.
  - lsp4ij 0.21 already places the caret after the inserted text.
- The Find Usages grouping reads whether Rider's "Occurrence Type" grouping is on from the rule Rider's backend describes. It used to ask Rider's grouping provider for its active rules, which is override-only API.
- Editors are opened, and file status followed, without override-only or internal API:
  - Navigation into C++, and to the AngelScript implementations of a blueprint event, opens the editor through `FileEditorManager` instead of calling `Navigatable.navigate`.
  - The Script Files tool window listens for file status changes through `FileStatusManager` instead of the internal `FileStatusListener.TOPIC`.
- `-PverifyIde=<folder of an installed IDE>` lets `verifyPlugin` run the Plugin Verifier the Marketplace runs, against that IDE.

## 1.0.0 - 2026-10-21

First release of this fork, published as AngelScript UE under its own plugin id. It continues from upstream 0.9.10; the entries below 1.0.0 are upstream's releases.

### Highlights
- C++ blueprint events list the AngelScript classes that implement them.
- Find Usages groups AngelScript references into reads and writes.
- AngelScript debugging starts in one step, with a status bar item that says whether the debugger is attached.
- Analysis keeps working from a cached type database while no Unreal editor is running, and the status bar says why analysis is unavailable when it is.
- AngelScript tools for AI agents through Rider's MCP server.
- Script folders are registered the first time a project is opened, so Find Usages finds script references from the first session.
- C++ navigation confirms the declaration it opens, and many constructs the engine accepts are no longer syntax errors.
- Bundled language server updated to unreal-angelscript v1.9.4; requires lsp4ij 0.21 or later.

### Added
- C++ blueprint events show their AngelScript implementations, and Rider no longer warns that a `BlueprintImplementableEvent` implemented in script "is not implemented in any blueprint".
  - Each `BlueprintImplementableEvent` and `BlueprintNativeEvent` with script overrides has an "N AngelScript implementations" entry. Clicking it opens the override, or a list when there are several.
  - Events script knows by another name are matched, e.g. `AActor::ReceiveBeginPlay` overridden as `BeginPlay`.
  - Rider's check reads only `.uasset` files and offers no way to add other implementations, so every event implemented in script was flagged. The overrides now come from the language server, which already has the script classes and Unreal's type database.
  - The warning is hidden as Rider adds it. One already on screen when an override is added or removed stays until the header is reopened or the declaration edited. The code vision entries update on their own.
- Find Usages tells reads from writes in AngelScript. Results are grouped while Group By | Occurrence Type is checked in the Find tool window, the same setting that groups C# and C++ results.
  - "Value write": where a reference is assigned, compound-assigned, incremented or decremented, or declared with a value, `default` statements included.
  - "Value read": wherever else its value is used.
  - "References": calls, types and namespaces.
  - Writes the syntax does not show are listed as reads: passing a variable to an `&out` parameter, and `Items[i] = X`, which is a read of `Items`.
  - The language server reports references as bare locations, and Rider does not group results it did not find itself, so every reference used to be in one list. Each one is now classified from the plugin's own parse tree.
  - The read and write access filters in the Find tool window do not apply to AngelScript yet: lsp4ij creates the usages they act on and cannot be told their access.
  - The highlighting of the symbol under the caret comes from the language server, not from this classification, and counts only assignments as writes.
- AngelScript debugging starts in one step, and a status bar item says whether the debugger is attached: `AngelScript debugger: not set up`, `not attached`, `connecting…` or `attached`.
  - Clicking it opens a menu whose first entry creates the debug configuration if the project has none, and starts it under Debug. The same entry is at the bottom of the Run menu.
  - A missing configuration used to show up only as breakpoints that never hit. Breakpoints can be placed in script files with no configuration at all, and debugging the Unreal editor's C++ looks as if it should hit them.
  - It is separate from the Unreal connection item, because the language server can be connected to the editor with no debugger attached.
  - It appears only while a script file has an enabled breakpoint or a debug session is running. Otherwise it would say `not attached` forever to everyone who never debugs scripts.
  - `attached` means Unreal has answered the debugger, not just that a session is running. A session waiting on an editor that does not respond, for example one stopped in the C++ debugger, reads `connecting…`.
- AngelScript tools for AI agents, served by the MCP server built into Rider: `angelscript_get_problems`, `angelscript_search_api`, `angelscript_get_api_details` and `angelscript_status`. They are registered only when the MCP Server plugin is enabled.
  - Rider's own MCP tools cannot see script files: `get_file_problems` rejects them because they belong to no solution project, and `search_symbol` finds none of their symbols.
  - What an agent most needs is inside the language server anyway: whether Unreal accepted the script it hot-reloaded, and which C++ API is bound to script under which name.
  - Problems cover every script file the server has reported on, open or not, including the compile errors Unreal sends.
  - Each result says when its file was last reported on, since diagnostics follow an edit asynchronously.
  - Each result carries the analysis state: without a type database the server reports nothing, and an agent must not read that as clean code.
- The bundled language server version is shown under Languages & Frameworks | AngelScript, and logged when the server starts.
  - The bundle carries no version of its own, so an installed plugin gave no way to tell which language server it ran.
  - The upstream release, commit and commit date are recorded at bundle time and shipped as a plugin resource.
- The Unreal type database is cached and reused while no editor is running, so highlighting and diagnostics keep working offline.
  - The database only exists inside a running Unreal editor, so analysis used to be unavailable whenever the editor was closed.
  - The cache is saved per project under the IDE system directory. A live editor always overrides it.
  - A saved database cannot reflect engine C++ changed since it was written, so the status bar says when one is in use. Saving can be turned off under Languages & Frameworks | AngelScript.
  - The setting applies to a running server, except switching it off while a saved database is loaded. Those types then have to be discarded, which needs a restart: the server has no reset for its database, and Unreal's symbols share the namespace tree with script's.
  - Types an editor has already sent stay loaded after it closes, as they always have.
- A status bar item says why AngelScript analysis is unavailable. Clicking it opens the AngelScript settings.
  - It tells apart a stopped server, an unconfigured connection port, an editor that is not running, and a type database still loading.
  - Semantic highlighting and diagnostics both need the C++ type database, which only a running Unreal editor provides. The language server used to produce nothing and say nothing in that situation.
- Script ignore patterns, and the classes that get a "Create Blueprint" code lens, can be edited under Languages & Frameworks | AngelScript.
  - Both were already sent to the language server, but the page had no editor for either. They kept their defaults, `**/Saved/**` and `**/.plastic/**`, and `AActor` and `UUserWidget`, and the Code Lenses section was hidden altogether.
- A plugin icon, shown in the Plugins list and on the JetBrains Marketplace.

### Changed
- Navigating from AngelScript into C++ confirms the declaration instead of guessing at a file name.
  - It used to look for a header named after the type, `Actor.h` for `AActor`, and match the member name anywhere in it. A type whose header is named after something else was never found, and a member another class in the same header declares could be opened instead.
  - Candidates now come from the content index as well. Nothing is opened until the declaration has been found in it, and a member is searched for inside its own type's body.
  - What Unreal writes is understood: namespace-wrapped enums, `struct alignas(...)`, the UE5 math aliases (`FVector` is a macro-generated name for `TVector` that appears nowhere in `Vector.h`), and the global functions the AngelScript plugin binds by passing their script signature to C++ as a string.
  - Comments, string literals, forward declarations, calls and assignments are no longer mistaken for declarations.
- The language server looks only at the project's script folders, instead of every content root of the solution. Analysis of the whole project is available seconds after the server starts rather than minutes, and is correct the first time rather than after a re-resolve.
  - Rider counts the nuget cache, the .NET SDK analyzer directories and the whole engine tree among the content roots, and the server scans each for `.as` files at startup. On a real project that was about two and a half minutes of disk walking that found nothing, while the project's own `Script` folder scans in a tenth of a second.
  - A project whose script the detection does not recognise falls back to the old behaviour.
- Requires lsp4ij 0.21 or later, which is where the API for choosing the workspace folders arrived.
- The bundled language server is upstream unreal-angelscript v1.9.4 (was v1.9.2).
  - Completing `UCLASS()` and similar macros puts the cursor inside the parentheses.
  - Named arguments complete across overloads.
  - A class's own members sort above inherited ones.
  - More override snippets appear when completing a return type.
  - Template instantiations no longer complete into the wrong namespace.
  - Delegate bind meta tags work on `mixin` methods, and accept subclassed arguments for wildcard parameters.
  - The `Placeable` and `ArraySizeEnum` specifiers are recognised.
  - "Promote to Member Variable" is offered for new variables used as function arguments.
  - Module dependency tracking no longer misses parameter and return types carrying `ref` or `const`.
- Symbols from other modules are no longer reported as needing an import. Upstream removed explicit import statements, which non-automatic imports depended on, and the `… must be imported` diagnostic went with them.
- The API browser tool window has an icon of its own, outlined in the New UI style, with versions for dark themes. It used the Unreal Engine logo.

### Fixed
- The language server no longer stops a few seconds after a tab is closed while no script file is open. It now runs for as long as the project is open, unless stopped by hand from the Language Servers tool window.
  - The plugin starts the server when the project opens, before any script is. lsp4ij stops a server that has no open documents, and checks whenever a tab is closed anywhere in the project.
  - Working in C++ with no `.as` file open, the first tab closed stopped the server until a script file was opened. The AngelScript implementations shown on C++ blueprint events stopped updating with it, since they never start the server themselves.
- The AngelScript status bar tooltips wrap at a readable width, with the state as a bold headline over the details. Status bar tooltips only wrap at 80% of the window, so each used to be a single line stretching across most of the screen.
- AngelScript debug configurations can only be started with Debug, not Run. Under Run, lsp4ij starts the debug adapter but never sends it breakpoints, so the session connected to Unreal and looked alive while no breakpoint could hit.
- `access` declarations are no longer syntax errors.
  - The parser expected every entry to pair an access level with a class, `protected AGameMode, private *`, which is not a form the language has. The engine reads one `private` or `protected` base, then the classes, global functions or `*` being granted access, each with optional `inherited`, `readonly` or `editdefaults`.
  - Every real declaration was flagged, and recovering from the error closed the class early, so everything after the declaration was read as if it stood outside the class.
  - Declarations inside structs are accepted too, and one at file scope is an error, as it is to the engine.
- Function attributes after the parameter list are no longer syntax errors: `no_discard`, `allow_discard`, `deprecated` and the rest of what the engine accepts there. Only `const`, `final`, `override` and `property` used to be accepted.
  - They are keywords only in that position, so a variable called `deprecated` is still a variable.
- A stray semicolon in a class or struct body, `void Method() {};` or `int Value;;`, is no longer a syntax error. The engine skips it as an empty declaration.
  - It was the most common thing in real scripts that the parser rejected: 158 of the 187 Split Fiction files that still failed to parse.
- Macro values the engine accepts are no longer syntax errors:
  - Negative numbers: `UIMin = -48`.
  - Negated conditions: `EditCondition = !bFlag`.
  - Unquoted category paths: `Category = Settings|Movement`, which used to be cut short at `Settings`.
  - An empty `Meta = ()`, and `TitleProperty = {Event}`.
  - Still reported: a missing comma between specifiers, and `!=` written for `=`. The engine compiles both, but by taking the next specifier into the value or making `EditCondition !` the key, so what was written is silently lost.
- A ternary in an f-string expression is no longer read as a format specifier when what follows the `:` cannot be one, as in `{bIsMetal ? 'Metal' : 'Crystal'}`. The engine makes the same distinction.
  - A `"` inside an expression ends the f-string, as it does for the engine. An expression missing its `}` is reported where the `}` belongs, rather than taking the code after it into the f-string.
- f-strings no longer break on literal text the engine accepts.
  - A backslash escapes the next character as in any string, so `f"Name: \"{Name}\""` no longer ends at the first `\"`.
  - A lone `}` outside an expression is plain text, rather than a bad character that took the rest of the function down with it.
- "Cannot find declaration to go to" no longer appears while C++ navigation is on its way.
  - The handler knew the symbol was a C++ one and jumped to it from a background thread, but reported no target, so the platform said there was none. It was most visible on the first jump to a symbol, where opening an engine header takes a while.
  - The declaration is now looked up while the caret waits, for up to a quarter of a second, and handed back as a real navigation target. A search that takes longer still finishes in the background.
- C++ navigation checks which class a declaration belongs to before opening it.
  - A member is only accepted inside the body of the symbol's type or one of its base classes. The language server reports those alongside the symbol, from Unreal's type database.
  - `FTransformFragment::SetTransform` used to open a project header declaring another struct's `SetTransform`, because the line itself does not say which class it belongs to.
  - The declaring file is found rather than guessed: the files naming both the type and the member are searched first. In a Mass-based project hundreds of files name `FTransformFragment`, and a handful have `SetTransform` beside it.
  - When the member cannot be found, navigation goes to the type's own declaration instead of to something unrelated.
- C++ navigation no longer lands in an unrelated file that happens to declare a member of the same name.
  - `FTransform::GetLocation` went to a project header with its own `GetLocation`: `FTransform` is an alias that appears in no file named after it, and the fallback search took any declaration anywhere.
  - Each name a type can have is now looked up with its own candidate list, and a file whose name shares a word with the type ranks higher. The fallback prefers files that mention the type at all.
- Script ignore patterns work on Windows.
  - They were matched with `Paths.get`, which rejects the `/f:/Project/...` paths that LSP URIs carry. Every match threw and every throw was read as "does not match", so no pattern excluded anything, and each navigation logged two warnings.
  - Matching is now done on the path text.
- Navigating from AngelScript to C++ works again. It had been broken since 0.9.10.
  - 0.9.10 moved bundling to upstream's esbuild layout, which hoists the language server's functions to the top level. The injected `angelscript/getCppSymbol` and `angelscript/navigateToCpp` handlers called a `scriptsymbols` that no longer existed, and failed with "scriptsymbols is not defined".
  - The patch still applied and the bundler reported success, and the client cannot tell a failed request from "not a C++ symbol", so navigation silently did nothing.
  - The bundler now checks that every name the injected code calls is defined in the finished bundle.
- Go to declaration on a C++ property now goes somewhere. Only names followed by `(` were matched, so a member variable such as `Actor.bHidden` or `Actor.Tags` had no target. Neither did a global function, since those exist only as the signature string the AngelScript plugin binds them with.
- Symbols declared in other files are no longer missing for the first minutes of a session, and go-to-definition on them no longer stays broken afterwards.
  - The language server scans every workspace folder for `.as` files before it loads anything, and lsp4ij filled those folders from the solution's content roots: in Rider, the nuget cache, the .NET SDK analyzer directories and the whole engine tree. On a real project the scan took about two and a half minutes, 53 seconds of it in an engine tree with no AngelScript at all.
  - Until it finished, the server knew only the open files. Anything resolved in that window resolved against an almost empty project and stayed that way until edited: completion recovered once the project loaded, navigation did not.
  - The plugin now names the project's script folders, each root is usable as soon as it has been scanned, and what was resolved before a batch of modules loaded is re-resolved when that batch lands.
- Syntax highlighting and diagnostics no longer disappear after the language server restarts. Configuration was only pushed once per project open, so a restarted server kept its default `unrealConnectionPort` of `-1` and never connected to the Unreal editor. The client now sends `workspace/didChangeConfiguration` on every server start.
- Startup no longer logs spurious `LSP server not ready yet (status: null)` warnings. The readiness probe asked for the server id `angelscript`, while the server is registered as `angelscript-lsp`.
- Script folders become content roots the first time a project is opened, so Find Usages in the default "Solution" scope finds AngelScript references from the first session.
  - The folders were looked for once, at the first smart mode. On a project Rider has never indexed, that comes minutes before the solution has loaded: the `.uproject` and `.uplugin` files were not in the project yet, and nothing was found. The default scope dropped every script reference, while "All Places" listed them.
  - Detection now runs again whenever the project roots change or indexing finishes.

### Internal
- Grammar changes are measured against the Split Fiction scripts. Decision record 0008 says where grammar rules come from and how a change is checked.
  - `CorpusParseReport` parses the whole corpus and writes the failing files, so two runs can be diffed.
  - `ValidateProductionFilesTest` no longer skips files as "Hazelight extensions": what it skipped was syntax the public plugin has, and the grammar had wrong.
- `ParseFileDebugger` can be pointed at a file. The `-Dtest.file` it documents was set on the Gradle JVM and never reached the forked test JVM; the test task now forwards it.
- Decision records are kept under `docs/decisions/`, one file per choice, with the alternatives that were rejected and the evidence that rejected them.
  - `docs/README.md` describes when to write one and how. The deep-dive guides moved to `docs/reference/`.
  - Most of what is hard about this plugin is where the IntelliJ platform, lsp4ij and a language server built for VSCode disagree, and a code comment cannot hold that.
- The stdio transport patch is dropped from the LSP bundler.
  - Upstream falls back to stdio by itself whenever no Node IPC channel is present, which is always so for how the plugin launches the server. The patch only rewrote a branch that is never taken.
  - The bundler warns if upstream ever stops making that choice.

## 0.9.10 - 2026-04-22

### Changed
- Raised minimum supported Rider version to 2025.2 (build 252). Earlier Rider versions are no longer supported; users on 2024.x / 2025.1 should remain on 0.9.9.
- Updated bundled language server to unreal-angelscript v1.9.2 (upstream bug fixes: nested namespace resolution, parse throttling, float literal parsing, property-marked function autocompletion, and more).

### Fixed
- Upstream LSP fixes carried over from v1.9.2: API reference now shows static functions in namespaces that shadow a type; nested namespace resolution no longer overwrites top-level namespaces.

### Internal
- Migrated LSP bundling pipeline to track upstream's esbuild-based build layout.
- Bumped IntelliJ Platform Gradle Plugin (2.11.0) and Grammar-Kit (2023.3.0.3) toolchain versions.

## 0.9.9 - 2026-01-14

### Fixed
- Fixed parsing errors when UCLASS/UPROPERTY/UFUNCTION macro attributes contain multi-line strings
- Unified constructor and function calls into single `call_expression` PSI node, removing parser ambiguity

## 0.9.8 - 2026-01-09

### Fixed
- F-string lexer now handles unexpected characters gracefully during incremental editing
- Added catch-all rules to prevent lexer state corruption when typing edge cases like `f"}"` or `f"{"`
- Generated parser/lexer files now use consistent LF line endings on Windows

## 0.9.7 - 2026-01-07

### Added
- **Granular Semantic Token Color Customization**
  - All 19 LSP semantic token types now customizable in color scheme settings
  - Distinguish actor/component/struct/event/delegate types with independent colors
  - Color settings organized into collapsible groups for easier navigation
  - Settings preview shows semantic highlighting without requiring Unreal Engine connection

### Performance
- Fixed "Slow operations prohibited on EDT" errors during plugin startup
- Script folder detection and tool window loading now run in background threads
- No more UI freezes during indexing or plugin initialization

## 0.9.6 - 2026-01-07

### Fixed
- Improved AS script folder detection performance

## 0.9.5 - 2026-01-06

### Added
- **Improved File Search Priority**
  - AngelScript files now appear with proper priority in "Navigate to File" (Ctrl+Shift+N)
  - Script folders registered as module content roots instead of library roots
  - Files now rank above fuzzy C++ matches when they have exact or better name matches
  - Automatic detection of Script folders next to .uproject and .uplugin files
  - Dynamic updates when Script folders are created or deleted

### Performance
- **Eliminated UI Freezes During Plugin Initialization**
  - Converted library root detection to async background computation
  - getAdditionalProjectLibraries() now returns in <10ms (was blocking 2-5 seconds)
  - VFS traversal 30-50% faster by skipping build/cache directories
  - Implemented lazy cache pattern to avoid EDT blocking
  - Added automatic cancellation of stale computations

### Technical
- Modernized folder detection with Kotlin coroutines
- Implemented EDT thread safety for module modifications
- Content roots persist across IDE restarts (stored in .idea/)
- Defensive logging for cache hits and computation timing

## 0.9.4 - 2026-01-05

### Added
- **Method Separators**
  - Visual separators between class methods and functions in the editor
  - Improves code readability with horizontal lines between method declarations
  - Automatically enabled for AngelScript files

### Fixed
- **Parser Error Recovery**
  - Fixed handling of ternary operators in formatted strings (e.g., `f"{value:condition ? 'Y' : 'N'}"`)
  - Improved error recovery with pinning on expression start and format separator
  - Parser now gracefully handles complex format expressions

### Performance
- **Script Files Tool Window**
  - Optimized tree cell rendering with 50% reduction in VCS status queries
  - Single status check per cell instead of separate checks for text and icon

## 0.9.3 - 2026-01-04

### Added
- **C++ Navigation**
  - Navigate from AngelScript symbols to C++ source code
  - Two navigation strategies:
    - Rider text search: Direct navigation in Rider using header file search with UE naming convention support
    - Unreal Engine delegation: Opens C++ source in configured IDE via RiderLink
  - Configurable in Settings → Tools → AngelScript → Navigation
  - Intelligent header file ranking (Public/ > Classes/ > Runtime/ > Private/)
  - Performance caching for repeated lookups

- **Script Ignore Patterns**
  - Filter files from LSP server using glob patterns
  - Default patterns exclude build artifacts (**/Saved/**) and VCS metadata (**/.plastic/**)
  - Prevents duplicate modules and improves LSP performance
  - Blocks navigation to excluded directories

- **VCS Integration**
  - Script Files tool window now shows VCS status colors
  - Async loading prevents EDT blocking
  - Auto-updates when file status changes

### Changed
- **Completion Icons**
  - Updated completion item icons for scripting language semantics
  - Method → Function icon (matches AngelScript terminology)
  - Field → Property icon (matches AngelScript terminology)

- **Icon System**
  - Added light/dark theme icon support for project and file icons

### Fixed
- Custom completion proposal fixes lsp4ij's additionalTextEdits ordering bug with stable sort

## 0.9.2 - 2026-01-02

### Fixed
- **Code Formatter Improvements**
  - Fixed brace alignment for methods with access specifiers (private, protected, public)
    - Braces now correctly align to the start of the method declaration instead of the return type
  - Fixed block content indentation to use consistent indent levels
    - Content inside braces no longer aligns to arbitrary positions based on PSI tree structure
  - Example: `private void Foo() { }` now formats correctly with braces aligned to "private"

- **Scoped Identifier Completion**
  - Fixed autocomplete for scoped identifiers (e.g., `FVector::ZeroVector`)
  - Completion now correctly replaces only the identifier after `::` instead of the entire scoped path
  - Example: `FVector::Z<TAB>` now completes to `FVector::ZeroVector` (was incorrectly `FVectorZeroVector`)
  - Handles both complete (`::`) and incomplete (`:`) scope resolution operators

### Changed
- **Grammar and Parser Updates**
  - Updated grammar to better handle scoped identifiers and member access expressions
  - Improved PSI structure for identifier references and scope resolution
  - Regenerated parser and updated all test expectations to match new PSI structure

### Technical
- Excluded formatter tests from automated CI runs (requires full Rider infrastructure)
- Added test data files for manual formatter verification

## 0.9.1 - 2026-01-01

### Added
- **Tool Window Features**
  - 'Always Select Opened File' toggle in AngelScript Files tool window
    - Automatically scrolls to currently opened file in the tree
    - Persists setting across IDE restarts
  - 'Auto-Switch Tool Windows' feature
    - Automatically switches between AngelScript Files and Project windows
    - Switches to AngelScript Files when opening .as/.ash files
    - Switches to Project window when opening other files
    - Persists setting across IDE restarts
  - Both features accessible via Behavior submenu in gear menu
- Automated release workflow for CI/CD

### Fixed
- AngelScript Files tool window now correctly displays standalone Script folders (not part of .uproject)
- Autocomplete cursor positioning corrected
- Release workflow improvements with proper permissions and error handling

### Documentation
- Updated debugging documentation

## 0.9.0 - 2024-12-01
- First release
