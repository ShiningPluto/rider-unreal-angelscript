package com.scriptacus.riderunrealangelscript

import com.intellij.openapi.util.IconLoader

/**
 * Icon definitions for the AngelScript plugin.
 */
object AngelScriptIcons {
    /**
     * The API browser tool window. This path is the classic UI's 13x13 icon; the New UI's outlined
     * 16x16 and 20x20 versions under icons/expui are swapped in by AngelScriptIconMappings.json.
     */
    @JvmField
    val ToolWindow = IconLoader.getIcon("/icons/toolWindowApi.svg", AngelScriptIcons::class.java)

    @JvmField
    val ProjectWindow = IconLoader.getIcon("/icons/project-icon-dark-theme.svg", AngelScriptIcons::class.java)

    @JvmField
    val FileIcon = IconLoader.getIcon("/icons/file-icon-dark-theme.svg", AngelScriptIcons::class.java)
}