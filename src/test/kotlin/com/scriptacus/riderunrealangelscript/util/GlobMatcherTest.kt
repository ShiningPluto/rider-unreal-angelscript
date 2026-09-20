package com.scriptacus.riderunrealangelscript.util

import com.intellij.testFramework.UsefulTestCase

/**
 * The ignore patterns decide which files the language server is told about and which navigation
 * targets are refused. They were matched through `Paths.get`, which throws on the `/f:/...` paths
 * LSP URIs carry, so on Windows every match threw and was swallowed as "no match".
 */
class GlobMatcherTest : UsefulTestCase() {

    private val defaults = listOf("**/Saved/**", "**/.plastic/**")

    fun testUriStylePathsAreMatched() {
        assertTrue(GlobMatcher.matchesAny("/f:/Project/Saved/Cooked/Foo.as", defaults))
        assertTrue(GlobMatcher.matchesAny("/f:/Project/.plastic/Foo.as", defaults))
    }

    fun testPlainAndWindowsPathsAreMatched() {
        assertTrue(GlobMatcher.matchesAny("F:/Project/Saved/Foo.as", defaults))
        assertTrue(GlobMatcher.matchesAny("F:\\Project\\Saved\\Foo.as", defaults))
        assertTrue(GlobMatcher.matchesAny("/home/user/Project/Saved/Foo.as", defaults))
    }

    fun testScriptFilesAreNotMatched() {
        assertFalse(GlobMatcher.matchesAny("/f:/Project/Script/Gameplay/Doors/Foo.as", defaults))
        // 'Saved' has to be a whole segment: a folder merely starting with it is not build output.
        assertFalse(GlobMatcher.matchesAny("/f:/Project/SavedGames/Foo.as", defaults))
    }

    fun testLeadingDoubleStarMatchesWithNoDirectoryInFront() {
        assertTrue(GlobMatcher.matchesAny("Saved/Foo.as", listOf("**/Saved/**")))
    }

    fun testSingleStarStaysInsideOneSegment() {
        assertTrue(GlobMatcher.matchesAny("F:/Project/Script/Foo.as", listOf("**/Script/*.as")))
        assertFalse(GlobMatcher.matchesAny("F:/Project/Script/Nested/Foo.as", listOf("**/Script/*.as")))
    }

    fun testNoPatternsMatchNothing() {
        assertFalse(GlobMatcher.matchesAny("F:/Project/Saved/Foo.as", emptyList()))
    }
}
