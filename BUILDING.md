# Building from Source

This guide covers building the Rider Unreal AngelScript plugin from source.

## Prerequisites

- **Java 21** - [Download from Oracle](https://www.oracle.com/java/technologies/downloads/)
- **Node.js** - [Download from nodejs.org](https://nodejs.org/)
- **.NET SDK** - [Download from Microsoft](https://dotnet.microsoft.com/download)

## Initial Setup

1. Clone the repository with submodules:
   ```bash
   git clone --recurse-submodules https://github.com/ShiningPluto/rider-unreal-angelscript.git
   cd rider-unreal-angelscript
   ```

2. Copy `gradle.properties.template` to `gradle.properties`:
   ```bash
   cp gradle.properties.template gradle.properties
   ```

3. Edit `gradle.properties` to configure paths for your system (optional):
   - Set Java home path if needed
   - Customize build settings if desired
   - Set `localRiderSdk` to a folder holding the Rider SDK, extracted, to build against it instead of
     the 3 GB SDK Gradle downloads and extracts. It must be the `productVersion` the build is set
     to, which the build checks. Extracting `riderRD-<productVersion>.zip` from Gradle's cache
     (`caches/modules-2/files-2.1/com.jetbrains.intellij.rider/riderRD`) gives one. This also keeps
     the build working when an antivirus deletes a script it flags from that zip and breaks it, as
     Kaspersky does with `lib/ReSharperHost/dotnet-sdk.cmd`.

## Building the Plugin

Build the complete plugin distribution:
```bash
./gradlew buildPlugin
```

The plugin ZIP will be created at:
```
build/distributions/Scriptacus.RiderUnrealAngelscript-<version>.zip
```

You can install this ZIP file in Rider via **Settings** → **Plugins** → ⚙️ → **Install Plugin from Disk...**

## Development Tasks

### Running Rider with Plugin

Launch a sandboxed Rider instance with the plugin installed:
```bash
./gradlew runIde
```

This is useful for testing changes during development.

The sandbox runs the Rider version the plugin is built against (`productVersion`). Three optional
properties adapt it to a real Unreal project:

| Property | Effect |
|---|---|
| `-PrunIdeMaxHeap=4g` | Heap of the sandbox Rider. The default, 1500m, is too small for a large Unreal project. |
| `-PlocalRider=<folder of an installed Rider>` | Adds a `runLocalRider` task that runs the plugin in that Rider instead, for a project whose toolchain (MSBuild, C#) is newer than `productVersion` knows. The plugin is still built against `productVersion`. |
| `-PlocalLsp4ij=<path to an lsp4ij plugin zip>` | Builds and runs against a local lsp4ij build instead of the released version, to try an lsp4ij change before it ships. |

For example, with Rider installed by the Toolbox App:
```bash
./gradlew runLocalRider -PlocalRider="$LOCALAPPDATA/Programs/Rider" -PrunIdeMaxHeap=4g
```

### Checking Compatibility Before a Release

The JetBrains Marketplace runs the Plugin Verifier on every upload and reports internal,
override-only, deprecated and experimental API usages. Run it locally against an installed IDE first:
```bash
./gradlew verifyPlugin -PverifyIde="$LOCALAPPDATA/Programs/Rider"
```

### Building Components Individually

**Compile the third-party language server:**
```bash
./gradlew compileThirdPartyLsp
```

This compiles the TypeScript source from the VSCode extension to JavaScript. Happens automatically during `buildPlugin`.

**Bundle the LSP server and debug adapter:**
```bash
./gradlew buildLsp
```

This bundles the compiled language server and debug adapter with esbuild. Happens automatically during `buildPlugin`.

**Build .NET components:**
```bash
./gradlew compileDotNet
```

**Generate parser from grammar:**
```bash
./gradlew generateAngelScriptParser
```

Run this after making changes to `src/main/bnf/AngelScript.bnf`.

**Generate lexer:**
```bash
./gradlew generateAngelScriptLexer
```

Run this after making changes to `src/main/jflex/AngelScript.flex`.

### Running Tests

**Run all tests:**
```bash
./gradlew test testDotNet
```

**Run Kotlin/parser tests only:**
```bash
./gradlew test
```

**Run specific test:**
```bash
./gradlew test --tests "*AngelScriptErrorRecoveryTest*"
```

**Run .NET tests:**
```bash
./gradlew testDotNet
```

Or directly:
```bash
dotnet test Scriptacus.RiderUnrealAngelscript.sln --logger GitHubActions
```

## Project Structure

The plugin consists of three main components:

1. **Kotlin/IntelliJ Platform** (`src/rider/main/kotlin`)
   - IDE integration, LSP client, syntax highlighting
   - Built by Gradle

2. **.NET/ReSharper** (`src/dotnet`)
   - Backend support for ReSharper and Rider
   - Built by Gradle (calls MSBuild/dotnet)

3. **Language Server** (`third-party/vscode-unreal-angelscript`)
   - Bundled LSP server from the VSCode extension
   - Bundled via esbuild (npm scripts)

## Development Workflow

When working on different parts of the plugin:

1. **Grammar changes** (`src/main/bnf/AngelScript.bnf`):
   - Run `./gradlew generateAngelScriptParser`
   - Then `./gradlew buildPlugin` or `runIde`

2. **Lexer changes** (`src/main/jflex/AngelScript.flex`):
   - Run `./gradlew generateAngelScriptLexer`
   - Then `./gradlew buildPlugin` or `runIde`

3. **Kotlin code changes** (`src/rider/main/kotlin`):
   - Run `./gradlew buildPlugin` or `runIde`

4. **.NET changes** (`src/dotnet`):
   - Run `./gradlew compileDotNet`
   - Then `./gradlew buildPlugin` or `runIde`

5. **Language server changes** (`third-party/vscode-unreal-angelscript`):
   - Run `./gradlew compileThirdPartyLsp buildLsp`
   - Or just run `./gradlew buildPlugin` or `runIde` (automatically rebuilds if source changed)

The `prepareSandbox` task (run by `buildPlugin` and `runIde`) orchestrates building all components and assembling them into the plugin distribution.

## Troubleshooting

### Build fails with "Java not found"
Ensure `JAVA_HOME` is set correctly or configure the path in `gradle.properties`.

### Build fails with "Could not resolve third-party/vscode-unreal-angelscript/language-server/out/server.js"
The git submodule wasn't initialized. Run:
```bash
git submodule update --init --recursive
```

Then rebuild:
```bash
./gradlew buildPlugin
```

Gradle will automatically compile the language server and install dependencies.

### Build fails with "npm not found in PATH"
Ensure Node.js is installed and `npm` is available in PATH:
```bash
node --version
npm --version
```

If Node.js is installed but not in PATH, you can set the npm executable location:
```bash
./gradlew buildPlugin -Pnpm.executable=/path/to/npm
```

### .NET build fails
Ensure .NET SDK is installed:
```bash
dotnet --version
```

### Tests fail with parser errors
After grammar changes, always regenerate the parser:
```bash
./gradlew generateAngelScriptParser
```

## Additional Resources

- [CONTRIBUTING.md](CONTRIBUTING.md) - Contribution guidelines and workflow
- [docs/](docs/) - How the plugin works and why
  - [docs/decisions/](docs/decisions/) - The choices behind the parts that are not obvious from the code
  - [docs/reference/](docs/reference/) - Maintenance guides, including [debugging.md](docs/reference/debugging.md)
  - [debugging.md](docs/reference/debugging.md) - Debugging setup
