# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## 1.0.1

### Changed
- The plugin no longer uses API that the IntelliJ Platform or lsp4ij keep for themselves, which the JetBrains Marketplace's compatibility check reported for 1.0.0. Nothing is meant to behave differently.

### Internal
- Completion corrects each item before lsp4ij builds its completion entry, instead of replacing that entry with a subclass of lsp4ij's internal `LSPCompletionProposal` that reached its private members by reflection. Edits at the same position are merged so lsp4ij cannot apply them in reverse, an item completed right after `::` is given the range it replaces, and a decorator formats what was inserted. lsp4ij 0.21 already places the caret after the inserted text. See `docs/reference/lsp-completion-workaround.md`.

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
- C++ blueprint events show their AngelScript implementations, and Rider no longer warns that a `BlueprintImplementableEvent` implemented in script "is not implemented in any blueprint". Rider's check reads only `.uasset` files and offers no way to add other implementations, so every event implemented in script was flagged. Each `BlueprintImplementableEvent` and `BlueprintNativeEvent` with script overrides now has an "N AngelScript implementations" entry; clicking it opens the override, or a list when there are several. Events script knows by another name are matched, e.g. `AActor::ReceiveBeginPlay` overridden as `BeginPlay`. The overrides come from the language server, which already has the script classes and Unreal's type database. The warning is hidden as Rider adds it, so one already on screen when an override is added or removed stays as it is until the header is reopened or the declaration edited. The code vision entries update on their own.
- Find Usages tells reads from writes in AngelScript. A reference is listed under "Value write" where it is assigned, compound-assigned, incremented or decremented, or declared with a value - `default` statements included - and under "Value read" wherever else its value is used; calls, types and namespaces are listed under "References". The language server reports references as bare locations, and Rider does not group results it did not find itself, so every reference used to be in one list, where finding what sets a variable meant reading them all. Each reference is now classified from the plugin's own parse tree, and AngelScript results are grouped by it in the Find tool window while Group By | Occurrence Type is checked, the same setting that groups C# and C++ results by usage type. Writes the syntax does not show are listed as reads: passing a variable to an `&out` parameter, and `Items[i] = X`, which is a read of `Items`. The read and write access filters in the Find tool window do not apply to AngelScript yet, because lsp4ij creates the usages they act on and cannot be told their access. The highlighting of a symbol under the caret comes from the language server, not from this classification, and counts only assignments as writes.
- AngelScript debugging now starts in one step, and a status bar item of its own says whether the debugger is attached: `AngelScript debugger: not set up`, `not attached`, `connecting…` or `attached`. Clicking it opens a menu whose first entry creates the debug configuration if the project has none and starts it under Debug; the same entry is at the bottom of the Run menu. Breakpoints can be placed in script files with no configuration at all, and debugging the Unreal editor's C++ looks as if it should hit them, so a missing configuration used to show up only as breakpoints that never hit. The item is separate from the Unreal connection item because the two are independent: the language server can be connected to the editor with no debugger attached. It appears only while a script file has an enabled breakpoint or a debug session is running, since otherwise it would report `not attached` forever to everyone who never debugs scripts; appearing with the first breakpoint also puts it in front of the user at the moment it matters. `attached` means Unreal has answered the debugger, not just that a debug session is running: a session waiting on an editor that does not respond, for example one stopped in the C++ debugger, reads `connecting…`.
- AngelScript tools for AI agents, served by the MCP server built into Rider: `angelscript_get_problems`, `angelscript_search_api`, `angelscript_get_api_details` and `angelscript_status`. Rider's own MCP tools cannot see script files - `get_file_problems` rejects them because they belong to no solution project, and `search_symbol` finds none of their symbols - and what an agent most needs to know about AngelScript is not in the files anyway, but inside the language server: whether Unreal accepted the script it hot-reloaded, and which C++ API is bound to script under which name. Problems cover every script file the server has reported on, open or not, including the compile errors Unreal sends; each result says when the file was last reported on, since diagnostics follow an edit asynchronously, and carries the analysis state, since without a type database the server reports nothing and an agent must not read that as clean code. The tools are registered only when the MCP Server plugin is enabled.
- The bundled language server version is now visible under Languages & Frameworks | AngelScript, and logged when the server starts. The bundle is an esbuild output with no version marker and the upstream pin lives only in this repository's git metadata, so an installed plugin previously gave no way to tell which language server it was running. The upstream release, commit and commit date are recorded at bundle time and shipped as a plugin resource.
- The Unreal type database is now cached and reused when no editor is running, so highlighting and diagnostics keep working offline. The database only exists inside a running Unreal editor, which previously made analysis unavailable whenever the editor was closed. The cache is saved per project under the IDE system directory; a live editor always overrides it. A saved database cannot reflect engine C++ changed since it was written, so the status bar reports when one is in use, and saving can be turned off under Languages & Frameworks | AngelScript. The setting applies to a running server, except when switching it off while a saved database is loaded: those types have to be discarded, which needs a restart, because the server has no reset for its database and Unreal-provided symbols share the namespace tree with script-declared ones. Types an editor has already sent are unaffected and stay loaded after it closes, as they always have.
- Status bar widget reporting why AngelScript analysis is unavailable. Semantic highlighting and diagnostics both require the C++ type database, which only a running Unreal editor can provide; until now the language server produced nothing and reported nothing in that situation. The widget distinguishes a stopped server, an unconfigured connection port, an editor that is not running, and a type database still loading. Clicking it opens the AngelScript settings.
- Script ignore patterns and the classes that get a "Create Blueprint" code lens can be edited under Languages & Frameworks | AngelScript. Both settings were already sent to the language server, but the page had no editor for either, so they kept their defaults - `**/Saved/**` and `**/.plastic/**`, `AActor` and `UUserWidget` - and the Code Lenses section was hidden altogether.
- A plugin icon, shown in the Plugins list and on the JetBrains Marketplace.

### Changed
- Navigating from AngelScript into C++ now confirms the declaration instead of guessing at a file name. The lookup used to ask the file name index for a header named after the type - `AActor` meant `Actor.h` - and then match the member name anywhere inside it, which meant a type whose header is named after something else was never found, and a member declared by a different class in the same header was opened instead of the right one. Candidates now come from the content index as well, nothing is opened until the declaration has been located in it, and a member is searched for inside its own type's body. What Unreal actually writes is understood along the way: namespace-wrapped enums, `struct alignas(...)`, the UE5 math aliases where `FVector` is a macro-generated name for `TVector` that appears nowhere in `Vector.h`, and the global functions the AngelScript plugin binds by passing their script signature to C++ as a string. Comments, string literals, forward declarations, calls and assignments are no longer mistaken for declarations.
- The language server is now told to look only at the project's script folders, instead of every content root of the solution. Rider counts the nuget cache, the .NET SDK analyzer directories and the whole engine tree among those, and the server scans each of them for `.as` files at startup: measured on a real project that was about two and a half minutes of disk walking that found nothing, while the project's own `Script` folder scans in a tenth of a second. Analysis of the whole project is now available seconds after the server starts rather than minutes, and is correct the first time rather than after a re-resolve. A project whose script the detection does not recognise still falls back to the old behaviour.
- Requires lsp4ij 0.21 or later, which is where the API for choosing the workspace folders arrived.
- Updated the bundled language server to upstream unreal-angelscript v1.9.4 (was v1.9.2). Completion improvements: the cursor now lands inside the parentheses when completing `UCLASS()` and similar macros, named arguments complete across overloads, a class's own members sort above inherited ones, more override snippets appear when completing a return type, and template instantiations no longer complete into the wrong namespace. Delegate bind meta tags now work on `mixin` methods and accept subclassed arguments for wildcard parameters; the `Placeable` and `ArraySizeEnum` specifiers are recognised; "Promote to Member Variable" is offered for new variables used as function arguments; and module dependency tracking no longer misses parameter and return types carrying `ref`/`const` qualifiers.
- Symbols from other modules are no longer reported as needing an import. Upstream removed explicit import statements, which non-automatic imports depended on, so the `… must be imported` diagnostic and the data it carried are gone along with them.
- The API browser tool window has an icon of its own, outlined in the New UI style, with versions for dark themes. It used the Unreal Engine logo.

### Fixed
- The language server no longer stops a few seconds after a tab is closed while no script file is open. The plugin starts it when the project opens, before any script is, and lsp4ij stops a server that has no open documents - a check it makes whenever a tab is closed anywhere in the project, not only on files the server serves. Working in C++ with no `.as` file open, the first tab closed stopped the server, and it stayed stopped until a script file was opened; the AngelScript implementations shown on C++ blueprint events stopped updating with it, since they never start the server themselves. The server now runs for as long as the project is open, unless it is stopped by hand from the Language Servers tool window.
- The AngelScript status bar tooltips wrap at a readable width, with the state as a bold headline over the details. Status bar tooltips only wrap at 80% of the window, so each one used to be a single line of several sentences stretching across most of the screen.
- AngelScript debug configurations can no longer be started with Run, only with Debug. Under Run, lsp4ij starts the debug adapter but skips sending it breakpoints, so the session connected to Unreal and looked alive while no breakpoint could ever hit.
- `access` declarations are no longer reported as syntax errors. The parser expected every entry to pair an access level with a class - `protected AGameMode, private *` - which is not a form the language has: the engine reads one `private` or `protected` base, followed by the classes, global functions or `*` being granted access, each with optional `inherited`, `readonly` or `editdefaults`. Every real declaration was flagged, and recovering from the error closed the class early, so everything after the declaration was read as if it stood outside the class. Declarations inside structs are accepted too, and one at file scope is now an error, as it is to the engine.
- Function attributes after the parameter list other than `const`, `final`, `override` and `property` are no longer syntax errors: `no_discard`, `allow_discard`, `deprecated` and the rest of what the engine accepts in that position. They are keywords only there, so a variable called `deprecated` is still a variable.
- A stray semicolon in a class or struct body - `void Method() {};`, `int Value;;` - is no longer a syntax error. The engine skips it as an empty declaration, and it is the most common thing in real scripts that the parser rejected: in the Split Fiction scripts it accounted for 158 of the 187 files that still failed to parse.
- Macro values the engine accepts are no longer syntax errors: negative numbers (`UIMin = -48`), negated conditions (`EditCondition = !bFlag`), unquoted category paths (`Category = Settings|Movement`, which was cut short at `Settings`), an empty `Meta = ()` and `TitleProperty = {Event}`. A missing comma between specifiers and `!=` written for `=` are still reported: the engine compiles both, but by taking the next specifier into the value or making `EditCondition !` the key, so what was written is silently lost.
- A ternary in an f-string expression is no longer read as a format specifier when what follows the `:` cannot be one, as in `{bIsMetal ? 'Metal' : 'Crystal'}`; the engine makes the same distinction. A `"` inside an expression ends the f-string, as it does for the engine, so an expression missing its `}` is reported where the `}` belongs, rather than taking the code after it into the f-string.
- f-strings no longer break on literal text the engine accepts. A backslash escapes the next character as in any string, so `f"Name: \"{Name}\""` no longer ends at the first `\"`, and a lone `}` outside an expression is plain text rather than a bad character that took the rest of the function down with it.
- "Cannot find declaration to go to" no longer appears while C++ navigation is on its way. The handler knew the symbol was a C++ one and jumped to it from a background thread, but it reported no target to the platform, which then said there was none - most visibly on the first jump to a symbol, where opening an engine header takes long enough for the message to sit there. The declaration is now looked up while the caret waits, within a quarter of a second, and handed back as a real navigation target; a search that runs over that budget still finishes in the background as before.
- C++ navigation checks which class a declaration belongs to before opening it. A member is only accepted when the class body it sits in is the type the symbol came from or one of its base classes, which the language server now reports alongside the symbol by walking Unreal's type database. `FTransformFragment::SetTransform` used to open a project header declaring another struct's `SetTransform`, because the line itself says nothing about which class it belongs to. The declaring file is also found now rather than guessed at: the index is asked for the files naming the type and the files naming the member, and the ones answering to both are searched first - `FTransformFragment` is named by hundreds of files in a Mass-based project, and by a handful with `SetTransform` beside it. When the member genuinely cannot be found, navigation goes to the type's own declaration instead of to something unrelated.
- C++ navigation no longer lands in an unrelated file that happens to declare a member of the same name. `FTransform::GetLocation` went to a project header with its own `GetLocation`, because `FTransform` is an alias that appears in no file named after it and the fallback search was free to take any declaration anywhere. Each name a type can have is now looked up with its own candidate list, a file whose name shares a word with the type outranks one that does not, and the fallback prefers files that mention the type at all.
- Script ignore patterns work on Windows. They were matched with `Paths.get`, which rejects the `/f:/Project/...` paths that LSP URIs carry, so every match threw, every throw was read as "does not match", and no pattern ever excluded anything - while each navigation logged two warnings. Matching is now done on the path text directly.
- Navigating from AngelScript to C++ works again. It has been broken since 0.9.10, when the bundling pipeline moved to upstream's esbuild layout: that layout hoists the language server's functions to the top level instead of exposing them as module namespaces, so the injected `angelscript/getCppSymbol` and `angelscript/navigateToCpp` handlers called a `scriptsymbols` that no longer existed and failed at runtime with "scriptsymbols is not defined". The patch still applied cleanly and the bundler still reported success, and a failed request is indistinguishable from "this is not a C++ symbol" to the client, so both navigation strategies simply did nothing at all. The bundler now checks that every name the injected code calls is defined in the finished bundle.
- Go to declaration on a C++ property now goes somewhere. Only names followed by `(` were ever matched, so a member variable - `Actor.bHidden`, `Actor.Tags` - had nothing to navigate to, and neither did a global function, since those exist only as the signature string the AngelScript plugin binds them with.
- Symbols declared in other files are no longer missing for the first minutes of a session, and go-to-definition on them no longer stays broken afterwards. The language server scans every workspace folder for `.as` files before it loads anything, and lsp4ij fills those folders from the solution's content roots - in Rider the nuget cache, the .NET SDK analyzer directories and the whole engine tree. Measured on a real project that scan took about two and a half minutes, of which 53 seconds was an engine tree containing no AngelScript at all, and until it finished the server knew only the files that happened to be open. Anything resolved in that window resolved against an almost empty project, and since a resolved module is only revisited when it is edited, it stayed that way: completion recovered once the project loaded, navigation did not. The plugin now names the project's script folders explicitly, each root becomes usable as soon as it has been scanned rather than when the slowest one finishes, and whatever was resolved before a batch of modules loaded is re-resolved when that batch lands.
- Syntax highlighting and diagnostics no longer disappear after the language server restarts. Configuration was only pushed once per project open, so a restarted server kept its default `unrealConnectionPort` of `-1`, never connected to the Unreal editor, and never received the C++ type database. The client now re-sends `workspace/didChangeConfiguration` on every server start.
- Startup no longer logs spurious `LSP server not ready yet (status: null)` warnings. The readiness probe queried the server id `angelscript` while the server is registered as `angelscript-lsp`, so it could never observe the real status.
- Script folders become content roots the first time a project is opened, so Find Usages in the default "Solution" scope finds AngelScript references from the first session. The folders were looked for once, at the first smart mode, which on a project Rider has never indexed comes minutes before the solution has loaded: the .uproject and .uplugin files were not in the project yet, nothing was found, and the default scope dropped every script reference while "All Places" listed them. Detection now runs again whenever the project roots change or indexing finishes.

### Internal
- Grammar changes are measured against the Split Fiction scripts: `CorpusParseReport` parses the whole corpus and writes the failing files, so two runs can be diffed, and `ValidateProductionFilesTest` no longer skips files as "Hazelight extensions" - what it skipped was syntax the public plugin has, and the grammar had wrong. Decision record 0008 says where grammar rules come from and how a change is checked.
- `ParseFileDebugger` can now be pointed at a file. The `-Dtest.file` it documents was set on the Gradle JVM and never reached the forked test JVM, so it always parsed its default file; the test task now forwards the property.
- The repository now keeps decision records under `docs/decisions/`, one file per choice, with the alternatives that were rejected and the evidence that rejected them. `docs/README.md` describes when to write one and how; the deep-dive guides moved to `docs/reference/`. Most of what is hard about this plugin is where the IntelliJ platform, lsp4ij and a language server built for VSCode disagree, and that is knowledge a code comment cannot hold.
- Dropped the stdio transport patch from the LSP bundler. Upstream now falls back to stdio by itself whenever no Node IPC channel is present, which is always the case for how the plugin launches the server, so the patch only rewrote a branch that is never taken. The bundler warns if upstream ever stops making that choice.

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
