import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.jetbrains.plugin.structure.base.utils.isFile
import groovy.ant.FileNameFinder
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.kotlin.dsl.register
import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.intellij.platform.gradle.Constants
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

// Interface for injecting ExecOperations service (Gradle 9/10 compatibility)
interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.11.0"     // See https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
    id("org.jetbrains.grammarkit") version "2023.3.0.3"
}

val isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
extra["isWindows"] = isWindows

val dotnetSolution: String by project
val buildConfiguration: String by project
val productVersion: String by project
val dotnetPluginId: String by project
val riderPluginId: String by project
val publishToken: String by project

allprojects {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

tasks.wrapper {
    gradleVersion = "8.8"
    distributionType = Wrapper.DistributionType.ALL
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}

version = extra["pluginVersion"] as String

tasks.processResources {
    from("dependencies.json") { into("META-INF") }
}

sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
        // Include generated parser/lexer code for basic syntax highlighting
        java.srcDir("src/main/gen")
    }
    test {
        kotlin.srcDir("src/test/kotlin")
        resources.srcDir("src/test/resources")
    }
}

grammarKit {
    tasks.register<GenerateLexerTask>("generateAngelScriptLexer") {
        sourceFile.set(file("src/main/jflex/AngelScript.flex"))
        targetOutputDir.set(file("src/main/gen/com/scriptacus/riderunrealangelscript/lang/lexer"))

        doLast {
            // Normalize line endings to LF (required for cross-platform consistency)
            fileTree("src/main/gen").matching {
                include("**/*.java")
            }.forEach { file ->
                val content = file.readText()
                val normalized = content.replace("\r\n", "\n")
                if (content != normalized) {
                    file.writeText(normalized)
                }
            }
        }
    }
    tasks.register<GenerateParserTask>("generateAngelScriptParser") {
        dependsOn("generateAngelScriptLexer")
        sourceFile.set(file("src/main/bnf/AngelScript.bnf"))
        targetRootOutputDir.set(file("src/main/gen"))
        pathToParser.set("com/scriptacus/riderunrealangelscript/lang/parser")
        pathToPsiRoot.set("com/scriptacus/riderunrealangelscript/lang/psi")

        doLast {
            // Normalize line endings to LF (required for cross-platform consistency)
            fileTree("src/main/gen").matching {
                include("**/*.java")
            }.forEach { file ->
                val content = file.readText()
                val normalized = content.replace("\r\n", "\n")
                if (content != normalized) {
                    file.writeText(normalized)
                }
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn("generateAngelScriptParser")
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val setBuildTool by tasks.registering {
    // Capture project properties at configuration time
    val solution = dotnetSolution
    val config = buildConfiguration
    val rootPath = rootDir
    val isWin = isWindows

    // Get injected ExecOperations at configuration time
    val execOps = project.objects.newInstance<InjectedExecOps>().execOps

    doLast {
        extra["executable"] = "dotnet"
        var args = mutableListOf("msbuild")

        if (isWin) {
            val stdout = ByteArrayOutputStream()
            execOps.exec {
                executable("${rootPath}\\tools\\vswhere.exe")
                args("-latest", "-property", "installationPath", "-products", "*")
                standardOutput = stdout
                workingDir(rootPath)
            }

            val directory = stdout.toString().trim()
            if (directory.isNotEmpty()) {
                val files = FileNameFinder().getFileNames("${directory}\\MSBuild", "**/MSBuild.exe")
                extra["executable"] = files[0]
                args = mutableListOf("/v:minimal")
            }
        }

        args.add(solution)
        args.add("/p:Configuration=${config}")
        args.add("/p:HostFullIdentifier=")
        extra["args"] = args
    }
}

val compileDotNet by tasks.registering {
    dependsOn(setBuildTool)

    // Capture project properties at configuration time
    val rootPath = rootDir

    // Get injected ExecOperations at configuration time
    val execOps = project.objects.newInstance<InjectedExecOps>().execOps

    doLast {
        val executable: String by setBuildTool.get().extra
        val arguments = (setBuildTool.get().extra["args"] as List<*>).toMutableList()
        arguments.add("/t:Restore;Rebuild")
        execOps.exec {
            executable(executable)
            args(arguments)
            workingDir(rootPath)
        }
    }
}

val testDotNet by tasks.registering {
    // Capture project properties at configuration time
    val solution = dotnetSolution
    val rootPath = rootDir

    // Get injected ExecOperations at configuration time
    val execOps = project.objects.newInstance<InjectedExecOps>().execOps

    doLast {
        execOps.exec {
            executable("dotnet")
            args("test", solution,"--logger","GitHubActions")
            workingDir(rootPath)
        }
    }
}

tasks.buildPlugin {
    // Capture project properties at configuration time
    val rootPath = rootDir
    val projectVersion = version
    val projectName = rootProject.name

    // Get injected ExecOperations at configuration time
    val execOps = project.objects.newInstance<InjectedExecOps>().execOps

    doLast {
        val zipFile = file("${layout.buildDirectory.get()}/distributions/${projectName}-${projectVersion}.zip")
        val outputDir = file("${rootPath}/output")

        println("Looking for ZIP file at: ${zipFile.absolutePath}")
        println("ZIP file exists: ${zipFile.exists()}")
        println("Copying to: ${outputDir.absolutePath}")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        copy {
            from(zipFile)
            into(outputDir)
        }

        val copiedFile = file("${outputDir}/${projectName}-${projectVersion}.zip")
        println("Copied file exists: ${copiedFile.exists()}")
        if (!copiedFile.exists()) {
            throw GradleException("Failed to copy plugin ZIP to output directory")
        }

        // TODO: See also org.jetbrains.changelog: https://github.com/JetBrains/gradle-changelog-plugin
        val changelogText = file("${rootPath}/CHANGELOG.md").readText()
        val changelogMatches = Regex("(?s)(-.+?)(?=##|$)").findAll(changelogText)
        val changeNotes = changelogMatches.map {
            it.groups[1]!!.value.replace("(?s)- ".toRegex(), "\u2022 ").replace("`", "").replace(",", "%2C").replace(";", "%3B").replace("\"", "%22")
        }.take(1).joinToString()

        val executable: String by setBuildTool.get().extra
        val arguments = (setBuildTool.get().extra["args"] as List<*>).toMutableList()
        arguments.add("/t:Pack")
        arguments.add("/p:PackageOutputPath=${rootPath}/output")
        arguments.add("/p:PackageReleaseNotes=${changeNotes}")
        arguments.add("/p:PackageVersion=${projectVersion}")
        execOps.exec {
            executable(executable)
            args(arguments)
            workingDir(rootPath)
        }
    }
}

dependencies {
    intellijPlatform {
        // -PlocalRiderSdk=<folder> builds against an already extracted riderRD-<productVersion> instead
        // of the 3 GB SDK Gradle downloads and extracts into its caches. An antivirus that deletes a
        // script it flags from the cached zip leaves Gradle extracting it again from a broken archive.
        val localRiderSdk = providers.gradleProperty("localRiderSdk").orNull
        if (localRiderSdk != null) {
            val sdk = file(localRiderSdk)
            val productInfo = sdk.resolve("product-info.json")
            require(productInfo.isFile) { "localRiderSdk: $sdk has no product-info.json, so it is not an extracted Rider" }
            val sdkVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(productInfo.readText())?.groupValues?.get(1)
            require(sdkVersion == productVersion) { "localRiderSdk: $sdk is Rider $sdkVersion, but the plugin is built against $productVersion" }
            local(sdk)
        } else {
            rider(productVersion) { this.useInstaller = false }
        }
        jetbrainsRuntime()

        // Bundled plugins
        bundledPlugin("org.jetbrains.plugins.textmate")
        bundledPlugin("JavaScript") // For NodeJS interpreter discovery
        bundledPlugin("com.intellij.mcpServer") // Optional at runtime: AngelScript tools for the IDE's MCP server

        // External plugins
        // -PlocalLsp4ij=<path to an lsp4ij plugin zip> builds and runs against a local lsp4ij build instead
        val localLsp4ij = providers.gradleProperty("localLsp4ij").orNull
        if (localLsp4ij != null) {
            localPlugin(file(localLsp4ij))
        } else {
            plugin("com.redhat.devtools.lsp4ij", "0.21.0")
        }

        // Test framework
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // For verifyPlugin; see -PverifyIde below
        pluginVerifier()
    }

    // Add junit-vintage for JUnit 4 tests
    testImplementation("org.junit.vintage:junit-vintage-engine:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// -PverifyIde=<folder of an installed IDE> lets verifyPlugin check the plugin against that IDE with
// the Plugin Verifier the JetBrains Marketplace runs on every upload, so internal and override-only
// API usages show up before a release instead of in the Marketplace's review.
providers.gradleProperty("verifyIde").orNull?.let { verifyIde ->
    intellijPlatform {
        pluginVerification {
            ides {
                local(file(verifyIde))
            }
        }
    }
}

// Match Rider's default heap size of 1.5Gb (default for runIde is 512Mb). An Unreal project
// runs out of that; raise it with -PrunIdeMaxHeap=4g, as much as the Rider you use every day has
val runIdeMaxHeap = providers.gradleProperty("runIdeMaxHeap").getOrElse("1500m")

tasks.runIde {
    maxHeapSize = runIdeMaxHeap
}

tasks.test {
    useJUnitPlatform()
    // -D on the Gradle command line doesn't reach the forked test JVM; forward what the parser utilities read
    listOf("test.file", "corpus.report").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // Exclude formatter and folding tests - they require full Rider infrastructure
    // Functionality should be tested manually in Rider sandbox
    exclude("**/AngelScriptFormatterTest.class")
    exclude("**/AngelScriptFoldingTest.class")
}

tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.patchPluginXml {
    // Identity comes from gradle.properties rather than plugin.xml, so the plugin.xml identity lines
    // stay as upstream has them and merging upstream does not conflict with this fork's identity.
    // The IDE matches marketplace updates by plugin id, so this fork's id keeps upstream builds and
    // this fork's builds from ever overwriting each other. Absent properties leave plugin.xml untouched.
    pluginId.set(providers.gradleProperty("riderPluginId"))
    pluginName.set(providers.gradleProperty("riderPluginName"))
    vendorName.set(providers.gradleProperty("riderPluginVendorName"))
    vendorUrl.set(providers.gradleProperty("riderPluginVendorUrl"))

    // "What's new" is the announcement users read, so it holds only the releases' Highlights. The
    // full entries are mostly for maintainers, and too long for the IDE and the Marketplace.
    changeNotes.set(whatsNew(file("${rootDir}/CHANGELOG.md").readText(), project.version.toString()))
}

/**
 * The plugin's "What's new": the Highlights of the newest releases in [changelog], newest first, as
 * many as fit in [maxChars] characters, and a link to the whole changelog. The newest release with
 * Highlights is always there, and one without, such as a release of internal changes only, is
 * skipped. An Unreleased section is headed with [version], the version being built.
 */
fun whatsNew(changelog: String, version: String, maxChars: Int = 1500): String {
    fun html(markdown: String) = markdown
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    val notes = StringBuilder()
    var length = 0
    for (section in Regex("(?ms)^## (.+?)$(.*?)(?=^## |\\z)").findAll(changelog)) {
        val highlights = Regex("(?ms)^### Highlights$(.*?)(?=^#|\\z)").find(section.groupValues[2])?.groupValues?.get(1)
        val items = highlights.orEmpty().lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
        val itemsLength = items.sumOf { it.length }
        if (items.isEmpty()) {
            continue
        }
        if (length > 0 && length + itemsLength > maxChars) {
            break
        }
        length += itemsLength
        val title = section.groupValues[1].trim().let { if (it == "Unreleased") version else it }
        notes.append("<h3>").append(html(title)).append("</h3><ul>")
        items.forEach { notes.append("<li>").append(html(it)).append("</li>") }
        notes.append("</ul>")
    }
    notes.append("<p><a href=\"https://github.com/ShiningPluto/rider-unreal-angelscript/blob/main/CHANGELOG.md\">All changes</a></p>")
    return notes.toString()
}

// Install npm dependencies in the third-party VSCode extension
val installThirdPartyDeps by tasks.registering(Exec::class) {
    val thirdPartyDir = file("third-party/vscode-unreal-angelscript")
    workingDir(thirdPartyDir)

    val npmCommand = findProperty("npm.executable") as String?
        ?: System.getenv("NPM_EXECUTABLE")
        ?: findNpmInPath()
        ?: throw GradleException("npm not found in PATH. Please install Node.js or set npm.executable property")

    commandLine(npmCommand, "install")

    inputs.file("$thirdPartyDir/package.json")
    outputs.dir("$thirdPartyDir/node_modules")
}

// Install bundler dependencies (esbuild, etc.) in project root
val installBundlerDeps by tasks.registering(Exec::class) {
    workingDir(rootDir)

    val npmCommand = findProperty("npm.executable") as String?
        ?: System.getenv("NPM_EXECUTABLE")
        ?: findNpmInPath()
        ?: throw GradleException("npm not found in PATH. Please install Node.js or set npm.executable property")

    commandLine(npmCommand, "install")

    inputs.file("package.json")
    outputs.dir("node_modules")
}

val buildLsp by tasks.registering(Exec::class) {
    dependsOn(installThirdPartyDeps, installBundlerDeps)
    workingDir(rootDir)

    // Allow override via gradle property or environment variable
    val npmCommand = findProperty("npm.executable") as String?
        ?: System.getenv("NPM_EXECUTABLE")
        ?: findNpmInPath()
        ?: throw GradleException("npm not found in PATH. Please install Node.js or set npm.executable property")

    // This will run both LSP and DAP bundling
    commandLine(npmCommand, "run", "bundle")

    inputs.dir("third-party/vscode-unreal-angelscript/language-server/src")
    inputs.dir("third-party/vscode-unreal-angelscript/extension/src")
    inputs.files("scripts/bundle-lsp.js", "scripts/bundle-dap.js")
    outputs.file("src/rider/main/resources/js/angelscript-language-server.js")
    outputs.file("src/rider/main/resources/js/angelscript-debug-adapter.js")
    outputs.file("src/rider/main/resources/js/lsp-version.properties")
}

// Ensure resources are processed after LSP is bundled
tasks.processResources {
    dependsOn(buildLsp)
}

fun findNpmInPath(): String? {
    val npmExecutable = if (isWindows) "npm.cmd" else "npm"
    val path = System.getenv("PATH") ?: return null

    return path.split(File.pathSeparator)
        .map { File(it, npmExecutable) }
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
}

// Every sandbox the plugin runs in needs the backend assemblies next to the frontend jar
fun PrepareSandboxTask.includeDotNetBackend() {
    dependsOn(compileDotNet)
    dependsOn(buildLsp)

    val outputFolder = "${project.rootDir}/src/dotnet/${dotnetPluginId}/bin/${dotnetPluginId}.Rider/${buildConfiguration}"
    val dllFiles = listOf(
            "$outputFolder/${dotnetPluginId}.dll",
            "$outputFolder/${dotnetPluginId}.pdb",

            // TODO: add additional assemblies
    ).map { project.file(it) }

    dllFiles.forEach { file ->
        from(file) { into("${project.rootProject.name}/dotnet") }
    }

    doLast {
        dllFiles.forEach { file ->
            if (!file.exists()) throw RuntimeException("File $file does not exist")
        }
    }
}

tasks.prepareSandbox {
    includeDotNetBackend()
}

// -PlocalRider=<folder of an installed Rider> adds runLocalRider, which runs the plugin in that Rider
// instead of productVersion. A project whose toolchain is newer than productVersion knows (MSBuild,
// C#) does not load in the sandbox runIde starts, but does in a Rider recent enough.
providers.gradleProperty("localRider").orNull?.let { localRider ->
    intellijPlatformTesting.runIde.register("runLocalRider") {
        localPath = file(localRider)
        task {
            maxHeapSize = runIdeMaxHeap
        }
        prepareSandboxTask {
            includeDotNetBackend()
        }
    }
}

tasks.publishPlugin {
    dependsOn(testDotNet)
    dependsOn(tasks.buildPlugin)
    token.set(publishToken)
    // Only the Rider plugin is published. The ReSharper .nupkg that buildPlugin also packs is not
    // pushed: the plugin needs Rider's frontend, so a ReSharper listing would install nothing usable.
}

val riderModel: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(riderModel.name, provider {
        intellijPlatform.platformPath.resolve("lib/rd/rider-model.jar").also {
            check(it.isFile) {
                "rider-model.jar is not found at $riderModel"
            }
        }
    }) {
        builtBy(Constants.Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)
    }
}

// Skip buildSearchableOptions - we don't provide any settings pages yet
// Re-enable this when we add Configurable settings pages for search discoverability
tasks.buildSearchableOptions {
    enabled = false
}
