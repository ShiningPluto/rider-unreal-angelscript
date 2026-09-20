package com.scriptacus.riderunrealangelscript.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Matches file paths against the glob patterns from the script ignore settings.
 *
 * Done by translating the glob to a regex rather than through `PathMatcher`, because the paths
 * handed in come from LSP URIs - `file:///f:/Project/Script/Foo.as` has a path of
 * `/f:/Project/Script/Foo.as`, and `Paths.get` rejects that on Windows with "Illegal char <:> at
 * index 3". Every call threw, every throw was swallowed as "no match", and the ignore patterns
 * therefore never ignored anything on Windows while filling the log with warnings.
 *
 * Supported syntax, matching what the language server's globs do:
 * - `*` matches anything within one path segment
 * - `**` matches across segments, and `** /` also matches no segments at all
 * - `?` matches a single character within a segment
 */
object GlobMatcher {

    private val compiled = ConcurrentHashMap<String, Regex>()

    /**
     * Whether [filePath] matches any of [patterns].
     *
     * The path may be a plain file path or the path part of a `file:` URI, with either separator.
     */
    fun matchesAny(filePath: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false

        val path = normalizePath(filePath)
        return patterns.any { pattern -> regexFor(pattern).matches(path) }
    }

    /** `\` to `/`, and no leading slash in front of a Windows drive. */
    private fun normalizePath(filePath: String): String {
        val path = filePath.replace('\\', '/')
        val looksLikeUriDrivePath = path.length > 2 && path[0] == '/' && path[2] == ':'
        return if (looksLikeUriDrivePath) path.substring(1) else path
    }

    private fun regexFor(pattern: String): Regex =
        compiled.getOrPut(pattern) { globToRegex(pattern.replace('\\', '/')) }

    private fun globToRegex(pattern: String): Regex {
        val regex = StringBuilder()
        var i = 0

        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && pattern.startsWith("**/", i) -> {
                    // A leading '**/' must also match a path with nothing in front of the segment.
                    regex.append("(?:.*/)?")
                    i += 3
                }

                c == '*' && pattern.startsWith("**", i) -> {
                    regex.append(".*")
                    i += 2
                }

                c == '*' -> {
                    regex.append("[^/]*")
                    i++
                }

                c == '?' -> {
                    regex.append("[^/]")
                    i++
                }

                else -> {
                    regex.append(Regex.escape(c.toString()))
                    i++
                }
            }
        }

        // Case-insensitive throughout: the patterns name directories like Saved and Intermediate,
        // and the only platform this plugin runs Unreal on does not distinguish their case anyway.
        return Regex(regex.toString(), RegexOption.IGNORE_CASE)
    }
}
