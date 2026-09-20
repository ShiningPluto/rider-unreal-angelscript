package com.scriptacus.riderunrealangelscript.navigation

/**
 * Represents C++ symbol information resolved from an AngelScript symbol.
 *
 * @property className The C++ class name (e.g., "AActor"), or empty string for global symbols
 * @property symbolName The C++ symbol name (e.g., "GetActorLocation")
 * @property ancestry The C++ classes that could declare the member: [className] and everything it
 *   inherits from, as the language server reads it out of Unreal's type database. Empty when the
 *   server did not say - an older bundle, or a symbol with no owning type.
 */
data class CppSymbolInfo(
    val className: String,
    val symbolName: String,
    val ancestry: List<String> = emptyList()
)