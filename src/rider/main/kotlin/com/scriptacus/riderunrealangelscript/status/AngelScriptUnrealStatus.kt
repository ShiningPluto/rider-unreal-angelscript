package com.scriptacus.riderunrealangelscript.status

/**
 * The reason AngelScript analysis is or is not available.
 *
 * Semantic tokens and diagnostics both require the C++ type database, which the language server can
 * only obtain from a running Unreal editor. When it is missing the server produces nothing and says
 * nothing, so these states exist to make the difference visible.
 */
enum class AngelScriptUnrealStatus {
    /** The language server is not running, so nothing has been asked yet. */
    SERVER_STOPPED,

    /** The server is running but has not been told which port to use - it will not even try to connect. */
    NOT_CONFIGURED,

    /** The server has a port but no open socket: the Unreal editor is not running, or not listening. */
    DISCONNECTED,

    /** The socket is open but the type database has not arrived yet. Normal for a few seconds after connecting. */
    LOADING_TYPES,

    /** The type database is loaded; highlighting and diagnostics are available. */
    READY;

    val isAnalysisAvailable: Boolean
        get() = this == READY
}
