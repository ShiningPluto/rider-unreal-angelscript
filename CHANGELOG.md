# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## Unreleased

### Added
- The bundled language server version is now visible under Languages & Frameworks | AngelScript, and logged when the server starts. The bundle is an esbuild output with no version marker and the upstream pin lives only in this repository's git metadata, so an installed plugin previously gave no way to tell which language server it was running. The upstream release, commit and commit date are recorded at bundle time and shipped as a plugin resource.
- The Unreal type database is now cached and reused when no editor is running, so highlighting and diagnostics keep working offline. The database only exists inside a running Unreal editor, which previously made analysis unavailable whenever the editor was closed. The cache is saved per project under the IDE system directory; a live editor always overrides it. A saved database cannot reflect engine C++ changed since it was written, so the status bar reports when one is in use, and saving can be turned off under Languages & Frameworks | AngelScript. The setting applies to a running server, except when switching it off while a saved database is loaded: those types have to be discarded, which needs a restart, because the server has no reset for its database and Unreal-provided symbols share the namespace tree with script-declared ones. Types an editor has already sent are unaffected and stay loaded after it closes, as they always have.
- Status bar widget reporting why AngelScript analysis is unavailable. Semantic highlighting and diagnostics both require the C++ type database, which only a running Unreal editor can provide; until now the language server produced nothing and reported nothing in that situation. The widget distinguishes a stopped server, an unconfigured connection port, an editor that is not running, and a type database still loading. Clicking it opens the AngelScript settings.

### Changed
- The language server is now told to look only at the project's script folders, instead of every content root of the solution. Rider counts the nuget cache, the .NET SDK analyzer directories and the whole engine tree among those, and the server scans each of them for `.as` files at startup: measured on a real project that was about two and a half minutes of disk walking that found nothing, while the project's own `Script` folder scans in a tenth of a second. Analysis of the whole project is now available seconds after the server starts rather than minutes, and is correct the first time rather than after a re-resolve. A project whose script the detection does not recognise still falls back to the old behaviour.
- Requires lsp4ij 0.21 or later, which is where the API for choosing the workspace folders arrived.
- Updated the bundled language server to upstream unreal-angelscript v1.9.4 (was v1.9.2). Completion improvements: the cursor now lands inside the parentheses when completing `UCLASS()` and similar macros, named arguments complete across overloads, a class's own members sort above inherited ones, more override snippets appear when completing a return type, and template instantiations no longer complete into the wrong namespace. Delegate bind meta tags now work on `mixin` methods and accept subclassed arguments for wildcard parameters; the `Placeable` and `ArraySizeEnum` specifiers are recognised; "Promote to Member Variable" is offered for new variables used as function arguments; and module dependency tracking no longer misses parameter and return types carrying `ref`/`const` qualifiers.
- Symbols from other modules are no longer reported as needing an import. Upstream removed explicit import statements, which non-automatic imports depended on, so the `… must be imported` diagnostic and the data it carried are gone along with them.

### Fixed
- Symbols declared in other files are no longer missing for the first minutes of a session, and go-to-definition on them no longer stays broken afterwards. The language server scans every workspace folder for `.as` files before it loads anything, and lsp4ij fills those folders from the solution's content roots - in Rider the nuget cache, the .NET SDK analyzer directories and the whole engine tree. Measured on a real project that scan took about two and a half minutes, of which 53 seconds was an engine tree containing no AngelScript at all, and until it finished the server knew only the files that happened to be open. Anything resolved in that window resolved against an almost empty project, and since a resolved module is only revisited when it is edited, it stayed that way: completion recovered once the project loaded, navigation did not. The plugin now names the project's script folders explicitly, each root becomes usable as soon as it has been scanned rather than when the slowest one finishes, and whatever was resolved before a batch of modules loaded is re-resolved when that batch lands.
- Syntax highlighting and diagnostics no longer disappear after the language server restarts. Configuration was only pushed once per project open, so a restarted server kept its default `unrealConnectionPort` of `-1`, never connected to the Unreal editor, and never received the C++ type database. The client now re-sends `workspace/didChangeConfiguration` on every server start.
- Startup no longer logs spurious `LSP server not ready yet (status: null)` warnings. The readiness probe queried the server id `angelscript` while the server is registered as `angelscript-lsp`, so it could never observe the real status.

### Internal
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
