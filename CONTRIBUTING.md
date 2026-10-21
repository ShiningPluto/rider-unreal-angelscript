# Contributing to AngelScript UE

Thank you for your interest in contributing! This document provides guidelines and instructions for developing and contributing to this plugin.

## Development Setup

### Prerequisites

Before you begin, ensure you have the following installed:

- **Java 21** - [Download from Adoptium](https://adoptium.net/)
- **Node.js** - [Download from nodejs.org](https://nodejs.org/)
- **.NET SDK** - [Download from Microsoft](https://dotnet.microsoft.com/download)
- **JetBrains Rider** (2025.2.5 or newer) - For testing the plugin

### Initial Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/ShiningPluto/rider-unreal-angelscript.git
   cd rider-unreal-angelscript
   ```

2. **Configure build properties**:
   ```bash
   cp gradle.properties.template gradle.properties
   ```

   Edit `gradle.properties` and set `org.gradle.java.home` to your Java 21 installation path:
   - Example (Windows): `C:/Program Files/Java/jdk-21`
   - Example (macOS): `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`
   - Example (Linux): `/usr/lib/jvm/java-21-openjdk`


3. **Build the plugin**:
   ```bash
   ./gradlew buildPlugin
   ```

## Project Structure

The plugin is a hybrid multi-platform project with three main components:

### 1. Kotlin/IntelliJ Platform (`src/rider/main/kotlin`)

The IDE integration layer providing:
- Language file type registration
- LSP client integration via lsp4ij
- Syntax highlighting and semantic token mapping
- Parser and lexer definitions

**lsp4ij version:** pinned in `build.gradle.kts` (`plugin("com.redhat.devtools.lsp4ij", ...)`).
lsp4ij is a separate Marketplace plugin that users update independently, and `plugin.xml` cannot
express a version constraint on it, so the pin is both what we compile against and the effective
minimum. **0.21 or later**, for `LSPWorkspaceFolderFeature` - on an older lsp4ij the plugin's
client features fail to load and the server never starts. When raising it, check the classes the
plugin extends against the new version: `LSPCompletionProposal` in particular is subclassed with
reflection into its private members, see [reference/lsp-completion-workaround.md](docs/reference/lsp-completion-workaround.md).

**lsp4j exists twice.** The Rider SDK ships lsp4j 0.21 in `lib/`, lsp4ij bundles its own 1.0, and
the plugin compiles against the first but runs against the second - lsp4ij's are the objects it is
handed. The two agree on almost everything the plugin touches, which is what makes the exception
dangerous: `Diagnostic.getMessage()` returns a `String` in 0.21 and an `Either<String, MarkupContent>`
in 1.0, so a direct call compiles cleanly and fails at runtime with `NoSuchMethodError`
(`AngelScriptDiagnosticsStore` reads it reflectively for that reason). The unit tests resolve lsp4j
the same way as the IDE, so they catch such a call when something exercises it. When using an lsp4j
member for the first time, compare its signature in both jars.

**Key files:**
- `AngelScriptFileType.kt` - File type registration
- `AngelScriptLanguage.kt` - Language definition
- `AngelScriptConnectionProvider.kt` - LSP server connection management
- `AngelScriptSemanticTokensColorsProvider.kt` - Syntax highlighting

### 2. .NET/ReSharper (`src/dotnet`)

Backend support for ReSharper and Rider:
- Zone definitions for ReSharper component system
- Built as separate .NET assemblies

**Key files:**
- `IAngelscriptRiderPluginZone.cs` - ReSharper zone definition
- `*.csproj` - Project files for each .NET component

### 3. Language Server (`third-party/vscode-unreal-angelscript`)

External LSP server bundled from the vscode-unreal-angelscript extension:
- TypeScript/JavaScript language server
- Bundled to single JS file via esbuild
- Patched at bundle time for Rider (see [Patching Strategy](#patching-strategy))

## Development Workflow

### Building Components

**Build everything:**
```bash
./gradlew buildPlugin
```

**Build only .NET components:**
```bash
./gradlew compileDotNet
```

**Bundle LSP server:**
```bash
npm run bundle
```

**Generate parser/lexer from grammar:**
```bash
./gradlew generateAngelScriptParser generateAngelScriptLexer
```

### Running and Testing

**Launch Rider with the plugin in a sandbox:**
```bash
./gradlew runIde
```

**Run Kotlin tests:**
```bash
./gradlew test
```

**Run .NET tests:**
```bash
./gradlew testDotNet
```
Or directly:
```bash
dotnet test Scriptacus.RiderUnrealAngelscript.sln
```

### Grammar Changes

If you modify the grammar files, you must regenerate the parser and lexer:

1. **Edit grammar files:**
   - `src/main/bnf/AngelScript.bnf` - Parser grammar (Grammar-Kit format)
   - `src/main/jflex/AngelScript.flex` - Lexer specification (JFlex format)

2. **Regenerate parser/lexer:**
   ```bash
   ./gradlew generateAngelScriptParser generateAngelScriptLexer
   ```

3. **Generated files** (do not edit manually):
   - `src/main/gen/com/scriptacus/riderunrealangelscript/lang/parser/`
   - `src/main/gen/com/scriptacus/riderunrealangelscript/lang/psi/`
   - `src/main/gen/com/scriptacus/riderunrealangelscript/lang/lexer/`

### LSP Server Changes

The bundled language server and debug adapter are built from the `third-party/vscode-unreal-angelscript` submodule, an unmodified checkout of [Hazelight's repository](https://github.com/Hazelight/vscode-unreal-angelscript) pinned to a specific commit. Upstream carries no git tags, so the pin is a raw SHA of a `Publish version X` commit. Everything Rider needs is applied at bundle time instead of being committed into the checkout, which keeps bumping the pin a matter of re-running the bundler.

#### Patching Strategy

The build scripts (`scripts/bundle-lsp.js` and `scripts/bundle-dap.js`) automatically patch the bundled JavaScript after esbuild completes:

**LSP Server Patching** (`scripts/bundle-lsp.js`):
- **Drive letter normalisation**: Lowercases the drive letter of diagnostic URIs before they are sent, since Rider and the server disagree on its case
- **Deferred Unreal connection**: Starts with an unconfigured port so the server dials the editor only once the real port arrives, instead of connecting on the default first
- **Streamed workspace scan**: Loads each workspace root as soon as it has been scanned, instead of waiting for the slowest one. `AngelScriptWorkspaceFolderFeature` keeps the roots down to the script folders, so this mainly covers projects that fall back to Rider's content roots, where the slowest root is minutes away and unrelated to AngelScript
- **Re-resolve after a batch loads**: Re-resolves whenever newly loaded modules have drained through the queues, since modules resolved before them cache their failed cross-module lookups until the file is edited
- **C++ navigation handlers**: Adds `angelscript/getCppSymbol` and `angelscript/navigateToCpp`
- **Unreal status handler**: Adds `angelscript/getUnrealStatus` and `angelscript/setTypeCacheEnabled`, which back the status bar widget
- **Type database cache**: Persists the database the editor sends and replays it when no editor is running

The transport is not patched: the server selects stdio itself when no Node IPC channel is present.

**Debug Adapter Patching** (`scripts/bundle-dap.js`):
- **VSCode API Stubbing**: Replaces `import * as vscode` with stubs from `scripts/vscode-stub.js`
- **Unreal Engine Compatibility**: Injects `unreal.setDataBreakpoints([])` during initialization to match VSCode's DAP client behavior
- **Why needed**: Unreal Engine expects this message; LSP4IJ (Rider's DAP client) follows DAP spec strictly and only sends it when user creates data breakpoints

Why the patches live in the bundler rather than in the checkout, and what the `✓` lines are actually
checking, is recorded in
[decisions/0001](docs/decisions/0001-patch-the-language-server-at-bundle-time.md).

#### Making Changes

1. **Edit the patch** in `scripts/bundle-lsp.js` or `scripts/bundle-dap.js` rather than the submodule checkout, which stays at its upstream revision
2. **Rebuild the bundles**: `npm run bundle` (or `npm run bundle:lsp` / `npm run bundle:dap` individually)
3. **Check every patch reports `✓`**: the patches are regexes over the bundled output, so an upstream rename turns one into a silent no-op. A `⚠` line means the patch did not apply and the bundle must not be shipped. One of the lines checks the opposite direction - that every name the *injected* code calls is defined in the finished bundle - because a patch can apply perfectly and still reference something the bundle no longer has, which fails only at runtime and only in a way the client reads as "nothing here".
4. **Bundled outputs**:
   - LSP: `src/rider/main/resources/js/angelscript-language-server.js`
   - DAP: `src/rider/main/resources/js/angelscript-debug-adapter.js`
5. **Commit both** the script changes and the bundled outputs

#### Best Practices

1. **Keep patches minimal**: Only patch what's necessary for Rider compatibility
2. **Document patches**: Add comments explaining why each patch is needed
3. **Test thoroughly**: Verify LSP features and debugging after rebundling
4. **Commit together**: Always commit source changes and bundled outputs in the same commit

## Documentation

[docs/README.md](docs/README.md) describes how this repository documents itself. In short:

- **[docs/decisions/](docs/decisions/)** holds one file per decision - the choice, the alternatives,
  the evidence, and what would invalidate it. Add one when a choice constrains future work, when you
  rejected an alternative for a reason the code does not show, when a failure was invisible, or when
  a measurement drove the design. Not for ordinary bug fixes.
- **[docs/reference/](docs/reference/)** holds maintenance guides for mechanisms too intricate for
  comments.
- **[docs/plans/](docs/plans/)** holds work that is understood but not done, so the investigation
  behind it is not repeated by whoever picks it up.

Read [decisions/0001](docs/decisions/0001-patch-the-language-server-at-bundle-time.md) and
[decisions/0003](docs/decisions/0003-send-only-the-script-folders-as-the-workspace.md) before
changing anything about how the language server is built or started.

## Code Style

### Kotlin

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Use IntelliJ IDEA's auto-formatting (Ctrl+Alt+L)

### C#/.NET

- Follow [C# coding conventions](https://docs.microsoft.com/en-us/dotnet/csharp/fundamentals/coding-style/coding-conventions)
- Use PascalCase for types and public members
- Use camelCase for private fields with `_` prefix
- Add XML documentation comments for public APIs

### Commit Messages

- Use clear, descriptive commit messages
- Start with a verb in imperative mood (e.g., "Add", "Fix", "Update", "Remove")
- Keep the first line under 72 characters
- Add detailed explanation in the body if needed

Example:
```
Add support for inlay hints configuration

Implemented user-configurable inlay hints for parameter names
and type information. Settings are synced with LSP server
configuration protocol.
```

## Submitting Changes

### Pull Request Process

1. **Fork the repository** and create a feature branch:
   ```bash
   git checkout -b feature/my-new-feature
   ```

2. **Make your changes** following the code style guidelines

3. **Test thoroughly**:
   - Run all tests: `./gradlew test testDotNet`
   - Test manually in sandbox: `./gradlew runIde`
   - Verify with a real Unreal Engine AngelScript project if possible

4. **Commit your changes** with clear commit messages

5. **Push to your fork**:
   ```bash
   git push origin feature/my-new-feature
   ```

6. **Create a Pull Request** with:
   - Clear description of changes
   - Reference any related issues
   - Screenshots/GIFs for UI changes
   - Test results

### Pull Request Guidelines

- Keep PRs focused on a single feature or fix
- Update documentation if you change user-facing behavior
- Add a [decision record](docs/README.md#decision-records) if the change makes a choice that later
  work has to live with, or corrects one that is already recorded
- Add tests for new functionality
- Ensure all tests pass
- Respond to review feedback promptly

## Reporting Issues

When reporting bugs or requesting features, please include:

- **Plugin version** (from Settings → Plugins)
- **Rider version** (from Help → About)
- **Operating system** and version
- **Steps to reproduce** (for bugs)
- **Expected vs. actual behavior** (for bugs)
- **Logs** (if applicable, from Help → Show Log in Explorer)

## Questions?

- **Issues**: [GitHub Issues](https://github.com/ShiningPluto/rider-unreal-angelscript/issues)

## License

By contributing to this project, you agree that your contributions will be licensed under the MIT License.
