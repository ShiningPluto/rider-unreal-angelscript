package com.scriptacus.riderunrealangelscript.lsp

import com.intellij.testFramework.UsefulTestCase

/**
 * The drive letter decides whether the server sees an opened file and the file it scanned from
 * disk as one module or two: `getModuleName()` strips the workspace root prefix with a
 * case-sensitive `startsWith`, while every URI the server derives itself comes from
 * `URI.parse(root).fsPath`, which lower-cases the drive. Measured on a real project, the mismatch
 * doubled every completion item and named the scanned modules after their whole path.
 */
class AngelScriptFileUriSupportTest : UsefulTestCase() {

    fun testDriveLetterIsLoweredForTheServer() {
        assertEquals(
            "file:///f:/Work/Games/MyProject/Script",
            AngelScriptFileUriSupport.lowercaseDriveLetter("file:///F:/Work/Games/MyProject/Script")
        )
    }

    fun testDriveLetterIsRestoredForRider() {
        assertEquals(
            "file:///F:/Work/Games/MyProject/Script/Foo.as",
            AngelScriptFileUriSupport.uppercaseDriveLetter("file:///f:/Work/Games/MyProject/Script/Foo.as")
        )
    }

    /** Only the drive letter: the rest of the path is case-sensitive on the server's side. */
    fun testPathCaseIsLeftAlone() {
        val uri = "file:///d:/Game/MixedCase/PathName.as"
        assertEquals(uri, AngelScriptFileUriSupport.lowercaseDriveLetter(uri))
    }

    /** Anything that is not a Windows drive URI must pass through untouched. */
    fun testNonDriveUrisAreUntouched() {
        for (uri in listOf("file:///home/user/project/Foo.as", "file:///", "untitled:Untitled-1", "")) {
            assertEquals(uri, AngelScriptFileUriSupport.lowercaseDriveLetter(uri))
            assertEquals(uri, AngelScriptFileUriSupport.uppercaseDriveLetter(uri))
        }
    }
}
