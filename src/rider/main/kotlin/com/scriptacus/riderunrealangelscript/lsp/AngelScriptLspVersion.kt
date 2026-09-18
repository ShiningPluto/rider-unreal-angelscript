package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Which revision of Hazelight's unreal-angelscript language server this plugin bundles.
 *
 * The bundle is an esbuild output carrying no version marker, and the submodule pin lives only in
 * the repository's git metadata, so neither survives into an installed plugin. The version is
 * instead recorded at bundle time by `scripts/bundle-lsp.js` and shipped as a plugin resource.
 *
 * It describes the upstream source only: the bundler also applies this plugin's own patches, so a
 * problem reproducing on the same upstream version under VSCode is a different problem.
 */
object AngelScriptLspVersion {

    private val LOG = Logger.getInstance(AngelScriptLspVersion::class.java)
    private const val RESOURCE = "/js/lsp-version.properties"

    /** Upstream release the bundle was built from, or null if the resource is missing. */
    val version: String?

    /** Upstream commit the bundle was built from, absent when built outside a git checkout. */
    val commit: String?

    /** Date of [commit], in ISO form. */
    val commitDate: String?

    init {
        val properties = Properties()
        try {
            val stream = javaClass.getResourceAsStream(RESOURCE)
            if (stream == null) {
                LOG.warn("Bundled language server version resource $RESOURCE is missing")
            } else {
                stream.use { properties.load(it.reader(StandardCharsets.UTF_8)) }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to read the bundled language server version", e)
        }
        version = properties.getProperty("version").orNull()
        commit = properties.getProperty("commit").orNull()
        commitDate = properties.getProperty("commitDate").orNull()
    }

    /** Human-readable version, for example `v1.9.2 (0b7b872, 2026-02-24)`. */
    val displayText: String
        get() = format(version, commit, commitDate)

    /**
     * Formats whatever was recorded, rather than requiring all of it: the commit and its date are
     * unavailable when the plugin is built from a source archive.
     */
    fun format(version: String?, commit: String?, commitDate: String?): String {
        if (version == null) {
            return "unknown"
        }
        val revision = listOfNotNull(commit, commitDate)
        return if (revision.isEmpty()) "v$version" else "v$version (${revision.joinToString(", ")})"
    }

    private fun String?.orNull(): String? = this?.takeIf { it.isNotBlank() }
}
