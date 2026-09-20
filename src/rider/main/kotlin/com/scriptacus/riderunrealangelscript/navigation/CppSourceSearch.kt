package com.scriptacus.riderunrealangelscript.navigation

/**
 * Reads C++ source text well enough to point at a declaration.
 *
 * Rider's own C++ symbol table is out of reach for a plugin: ReSharper C++ ships only inside Rider
 * (`lib/ReSharperHost/JetBrains.ReSharper.Cpp.dll`) and none of the published SDK packages carry
 * reference assemblies for it, so a backend component has nothing to compile against. What is left
 * is reading the headers - but with enough structure to avoid the mistakes plain text matching
 * makes: this knows where comments and string literals are, tells a forward declaration from a real
 * one, and looks for a member inside its own class body instead of anywhere in the file. That last
 * point matters most in Unreal, where one header routinely declares several classes that all have a
 * `GetName` or a `Tick`.
 *
 * Offsets are into [text] as loaded with `LoadTextUtil`, whose line separators are already
 * normalised to `\n`, so they line up with editor document offsets.
 */
class CppSource(private val text: String) {

    /** False wherever a character belongs to a comment or a string/char literal. */
    private val isCode: BooleanArray = codeMask(text)

    /**
     * The offset of [name] in its own declaration, or -1.
     *
     * Forward declarations (`class AActor;`), `friend` declarations and mentions in comments are
     * skipped; a declaration carrying `UCLASS`/`USTRUCT`/`UENUM` wins over one that does not.
     */
    fun findTypeDeclaration(name: String): Int {
        var best = -1
        var bestScore = Int.MAX_VALUE

        for (match in typeDeclarationRegex(name).findAll(text)) {
            val start = match.range.first
            if (!isCode[start]) continue
            if (isTypeMention(match.range.last + 1)) continue
            if (isFriendDeclaration(start)) continue

            val score = if (hasMacroBefore(start, TYPE_MACROS)) 0 else 1
            if (score < bestScore) {
                bestScore = score
                best = match.groups[1]!!.range.first
                if (score == 0) break
            }
        }
        return if (best >= 0) best else findTypeAlias(name)
    }

    /**
     * Where [name] is introduced as an alias, or -1.
     *
     * UE5 turned the core math types into aliases - `using FVector = UE::Math::TVector<double>;` -
     * so looking only for `class`/`struct` misses exactly the types script code names most often.
     */
    private fun findTypeAlias(name: String): Int {
        for (match in aliasRegex(name).findAll(text)) {
            if (!isCode[match.range.first]) continue
            val declared = match.groups[1] ?: match.groups[2] ?: continue
            return declared.range.first
        }
        return -1
    }

    /**
     * The braces of [name]'s body, `{` to `}` inclusive, or null when the type is not declared here
     * or is only forward declared.
     */
    fun findTypeBody(name: String): IntRange? {
        val declaration = findTypeDeclaration(name)
        if (declaration < 0) return null

        // Between the name and the body come base classes and macros; a ';' means there is no body.
        var open = declaration + name.length
        while (open < text.length) {
            if (isCode[open]) {
                if (text[open] == ';') return null
                if (text[open] == '{') break
            }
            open++
        }
        if (open >= text.length) return null

        var depth = 0
        var i = open
        while (i < text.length) {
            if (isCode[i]) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return open..i
                }
            }
            i++
        }
        // Unbalanced braces: better to search the rest of the file than to give up.
        return open until text.length
    }

    /**
     * The offset of [member] where it is declared, or -1.
     *
     * When [owner] names a type declared in this file the search is confined to that type's body,
     * so a member of another class in the same header cannot be mistaken for it. A member declared
     * under `UFUNCTION`/`UPROPERTY` wins over one that is not; anything that reads as a call, an
     * assignment or a comment is rejected rather than ranked.
     */
    fun findMemberDeclaration(owner: String?, member: String): Int {
        val body = owner?.takeIf { it.isNotEmpty() }?.let { findTypeBody(it) }
        val from = body?.first ?: 0
        val until = body?.last ?: (text.length - 1)

        var best = -1
        var bestScore = Int.MAX_VALUE

        for (match in Regex("\\b" + Regex.escape(member) + "\\b").findAll(text, from)) {
            val at = match.range.first
            if (at > until) break
            if (!isCode[at]) continue

            val score = scoreMemberDeclaration(at, match.range.last + 1)
            if (score < bestScore) {
                bestScore = score
                best = at
                if (score == 0) break
            }
        }
        return best
    }

    /**
     * The name of the innermost type whose body contains [offset], or null when the offset is not
     * inside one.
     *
     * This is the check that tells a declaration of the right member from a declaration of a member
     * that merely has the same name: `SetTransform` inside some other struct is not
     * `FTransformFragment::SetTransform`, and nothing about the line itself says so.
     */
    fun enclosingTypeAt(offset: Int): String? {
        val scopes = ArrayList<String?>()
        var i = 0

        while (i < offset && i < text.length) {
            if (isCode[i]) {
                when (text[i]) {
                    '{' -> scopes.add(typeNameBefore(i))
                    '}' -> scopes.removeLastOrNull()
                }
            }
            i++
        }
        return scopes.lastOrNull { it != null }
    }

    /** The type a `{` opens the body of, or null when it opens a function body, an `if`, a list. */
    private fun typeNameBefore(brace: Int): String? {
        var start = brace - 1
        while (start >= 0) {
            if (isCode[start] && (text[start] == ';' || text[start] == '{' || text[start] == '}')) break
            start--
        }
        return TYPE_HEAD.find(text.substring(start + 1, brace))?.groupValues?.get(1)
    }

    /**
     * Whether [name] appears here as code at all.
     *
     * Used to tell a file that is about a type from one that merely happens to declare something
     * with the same member name, which is the difference between landing on `FTransform::Rotator`
     * and on some plugin's unrelated `GetRotator`.
     */
    fun mentions(name: String): Boolean =
        Regex("\\b" + Regex.escape(name) + "\\b").findAll(text).any { isCode[it.range.first] }

    /**
     * Where [name] is bound to script from C++, or -1.
     *
     * Global functions and mixin methods often have no C++ declaration under that name at all: the
     * AngelScript plugin binds them by handing the *script* signature over as a string -
     * `FAngelscriptBinds::BindGlobalFunction("void Print(const FString& Text)", ...)` - so that
     * literal is the closest thing to a declaration there is, and it sits right above the lambda
     * that implements it.
     */
    fun findScriptBinding(name: String): Int {
        for (match in bindingRegex(name).findAll(text)) {
            val nameStart = match.groups[1]!!.range.first
            // Inside a literal by definition; anything in real code is a job for the search above.
            if (isCode[nameStart]) continue
            if (!hasBindCallBefore(match.range.first)) continue
            return nameStart
        }
        return -1
    }

    private fun hasBindCallBefore(at: Int): Boolean {
        val from = maxOf(0, at - MACRO_LOOKBEHIND)
        return BIND_CALL.containsMatchIn(text.substring(from, at))
    }

    /** Lower is better; [Int.MAX_VALUE] means "not a declaration". */
    private fun scoreMemberDeclaration(nameStart: Int, nameEnd: Int): Int {
        if (isQualifiedUse(nameStart)) return Int.MAX_VALUE

        val lineStart = text.lastIndexOf('\n', nameStart).let { if (it < 0) 0 else it + 1 }
        // Drop a macro sitting on the same line, so its parentheses are not read as a call.
        val before = UNREAL_MACRO_CALL.replace(text.substring(lineStart, nameStart), " ")
        if (STATEMENT_KEYWORDS.containsMatchIn(before)) return Int.MAX_VALUE
        // A '(' before the name means the name is an argument or part of a call, not a declaration.
        if (before.contains('(')) return Int.MAX_VALUE

        return when (nextCodeChar(nameEnd)) {
            '(' -> when {
                hasMacroBefore(nameStart, FUNCTION_MACROS) -> 0
                hasTypeBefore(before) -> 1
                else -> Int.MAX_VALUE
            }
            // A field: 'int32 Health;', 'float Speed = 600.f;', 'uint8 bHidden : 1;', 'FName Names[4];'
            ';', '=', ':', '[' -> when {
                hasMacroBefore(nameStart, PROPERTY_MACROS) -> 0
                hasTypeBefore(before) -> 2
                else -> Int.MAX_VALUE
            }
            else -> Int.MAX_VALUE
        }
    }

    /** `Foo.Bar`, `Foo->Bar` and `Foo::Bar` are uses of Bar, never its declaration. */
    private fun isQualifiedUse(nameStart: Int): Boolean {
        var i = nameStart - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        if (i < 0) return false
        if (text[i] == '.') return true
        if (i >= 1 && text[i] == '>' && text[i - 1] == '-') return true
        if (i >= 1 && text[i] == ':' && text[i - 1] == ':') return true
        return false
    }

    /**
     * Whether what precedes the name on its line reads as a type: `virtual void `, `TArray<int32> `,
     * `ENGINE_API FVector `. An empty prefix is not enough - that is how an assignment starts.
     */
    private fun hasTypeBefore(before: String): Boolean {
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return false
        return TYPE_TAIL.containsMatchIn(trimmed)
    }

    /**
     * Whether one of [macros] introduces the member at [at]: it has to appear shortly before, in
     * code, with no `;` or `}` in between - those would end the member the macro really belongs to.
     */
    private fun hasMacroBefore(at: Int, macros: Regex): Boolean {
        val from = maxOf(0, at - MACRO_LOOKBEHIND)
        val window = text.substring(from, at)
        val match = macros.findAll(window).lastOrNull() ?: return false
        if (!isCode[from + match.range.first]) return false
        return window.substring(match.range.last + 1).none { it == ';' || it == '}' }
    }

    private fun isFriendDeclaration(at: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
        return text.substring(lineStart, at).contains("friend")
    }

    /**
     * True when the type name is being referred to rather than declared: `class AActor;` is a
     * forward declaration and `class AActor* Owner;` an elaborated type specifier, while a real
     * declaration continues into `:`, `final` or `{`.
     */
    private fun isTypeMention(from: Int): Boolean {
        val next = nextCodeChar(from) ?: return true
        return next in TYPE_MENTION_CHARS
    }

    private fun nextCodeChar(from: Int): Char? {
        var i = from
        while (i < text.length) {
            if (isCode[i] && !text[i].isWhitespace()) return text[i]
            i++
        }
        return null
    }

    companion object {
        private const val MACRO_LOOKBEHIND = 400

        private val TYPE_MACROS = Regex("U(?:CLASS|STRUCT|ENUM|INTERFACE)\\s*\\(")
        private val FUNCTION_MACROS = Regex("UFUNCTION\\s*\\(")
        private val PROPERTY_MACROS = Regex("UPROPERTY\\s*\\(")
        private val UNREAL_MACRO_CALL = Regex("\\bU(?:FUNCTION|PROPERTY|CLASS|STRUCT|ENUM|INTERFACE|META)\\s*\\([^)]*\\)")

        /** What can follow a type name when it is only being mentioned, never when it is declared. */
        private val TYPE_MENTION_CHARS = setOf(';', '*', '&', ',', ')')

        /** `return X`, `if (X`, ... - the name is being used, whatever follows it. */
        private val STATEMENT_KEYWORDS =
            Regex("\\b(?:return|if|else|while|for|switch|case|new|delete|throw|co_return)\\b")

        /** An identifier, `>`, `*` or `&` right before the name is a type; `,` or `=` is not. */
        private val TYPE_TAIL = Regex("(?:[\\w>*&\\]])\\s*$")

        /** `FAngelscriptBinds::BindGlobalFunction(`, `Binds.Method(`, and everything like them. */
        private val BIND_CALL = Regex("\\bBind\\w*\\s*\\(")

        /** The declaration head a `{` can belong to, with the declared name captured. */
        private val TYPE_HEAD = Regex(
            "\\b(?:class|struct|union|namespace|enum\\s+class|enum)" +
                "\\s+(?:(?:\\w+_API|alignas\\s*\\((?:[^()]|\\([^()]*\\))*\\))\\s+)*(\\w+)"
        )

        private fun bindingRegex(name: String) =
            Regex("\"[^\"\\n]*?\\b(" + Regex.escape(name) + ")\\s*\\(")

        private fun aliasRegex(name: String): Regex {
            val escaped = "\\b" + Regex.escape(name) + "\\b"
            return Regex("\\busing\\s+($escaped)\\s*=|\\btypedef\\b[^;\\n]*?($escaped)\\s*;")
        }

        private fun typeDeclarationRegex(name: String) = Regex(
            // 'namespace' among them because Unreal wraps blueprint-facing enums in one:
            // 'namespace EDrawDebugTrace { enum Type { ... } }'.
            "(?:\\b(?:class|struct|union|namespace)\\b|\\benum\\s+class\\b|\\benum\\b)" +
                // What can sit between the keyword and the name: export macros and alignment,
                // 'struct alignas(alignof(TQuat<T>)) TTransform' being a real engine declaration.
                "\\s+(?:(?:\\w+_API|(?:alignas|MS_ALIGN)\\s*\\((?:[^()]|\\([^()]*\\))*\\))\\s+)*" +
                "(\\b" + Regex.escape(name) + "\\b)"
        )

        /**
         * Marks comments and literals so nothing below has to guess. One pass, because the
         * alternative - scanning backwards for a comment opener from every match - is quadratic on
         * headers that mention a common name a hundred times.
         */
        private fun codeMask(text: String): BooleanArray {
            val mask = BooleanArray(text.length) { true }
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                        while (i < text.length && text[i] != '\n') mask[i++] = false
                    }

                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                        val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                        while (i < end) mask[i++] = false
                    }

                    c == '"' || c == '\'' -> {
                        mask[i++] = false
                        while (i < text.length && text[i] != c && text[i] != '\n') {
                            // An escape swallows the next character, so \" does not close the literal.
                            if (text[i] == '\\' && i + 1 < text.length) mask[i++] = false
                            mask[i++] = false
                        }
                        if (i < text.length && text[i] == c) mask[i++] = false
                    }

                    else -> i++
                }
            }
            return mask
        }
    }
}

/**
 * Choosing which file to read, before [CppSource] reads it.
 *
 * Unreal's own navigation (`SourceCodeNavigation.cpp`) prefers `Public/` and `Classes/` over
 * `Private/`, and so does this; the rest is about not walking into build output.
 */
object CppFileRanking {

    private val BUILD_DIRECTORIES = listOf("Saved", "Intermediate", "Binaries", "DerivedDataCache")
    private val HEADER_EXTENSIONS = setOf("h", "hpp", "hh", "inl")
    private val SOURCE_EXTENSIONS = HEADER_EXTENSIONS + setOf("cpp", "cc", "cxx", "c")

    /** Generated and compiled output: never the place a user wants to land. */
    fun isBuildArtifact(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return BUILD_DIRECTORIES.any { normalized.contains("/$it/") }
    }

    fun isCppSource(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in SOURCE_EXTENSIONS

    /**
     * How promising [path] is for [typeName]; lower sorts first.
     *
     * A header outranks a `.cpp` because declarations live in headers, and a file named after the
     * type outranks one that is not - but neither is decisive, since the caller confirms the
     * declaration is really there before navigating.
     */
    fun rank(path: String, typeName: String): Int {
        val normalized = path.replace('\\', '/')
        val fileName = normalized.substringAfterLast('/')

        var rank = 0
        if (fileName.substringAfterLast('.', "").lowercase() !in HEADER_EXTENSIONS) rank += 100
        if (!fileNameMatchesType(fileName, typeName)) {
            // A shared word is weak evidence, but it is the difference between opening
            // MassCommonFragments.h for FTransformFragment and opening twelve unrelated headers
            // that merely mention the name.
            rank += if (sharesWord(fileName, typeName)) 8 else 20
        }

        rank += when {
            normalized.contains("/Public/") -> 0
            normalized.contains("/Classes/") -> 1
            normalized.contains("/Runtime/") -> 2
            normalized.contains("/Editor/") -> 3
            normalized.contains("/Private/") -> 4
            else -> 5
        }
        return rank
    }

    /** `AActor` is declared in `Actor.h`, `FVector` in `Vector.h` - Unreal drops the type prefix. */
    fun withoutTypePrefix(typeName: String): String =
        if (typeName.length > 1 && typeName[0] in "AUFEIST" && typeName[1].isUpperCase()) typeName.substring(1)
        else typeName

    /** The file names worth trying before any content search, in the order Unreal makes them. */
    fun likelyFileNames(typeName: String): List<String> {
        val base = withoutTypePrefix(typeName)
        return if (base == typeName) listOf("$typeName.h") else listOf("$base.h", "$typeName.h")
    }

    private fun fileNameMatchesType(fileName: String, typeName: String): Boolean {
        val stem = fileName.substringBeforeLast('.')
        return stem.equals(typeName, ignoreCase = true) || stem.equals(withoutTypePrefix(typeName), ignoreCase = true)
    }

    /** Whether the file name and the type name have a camel-case word in common. */
    private fun sharesWord(fileName: String, typeName: String): Boolean {
        val inFile = words(fileName.substringBeforeLast('.'))
        return words(withoutTypePrefix(typeName)).any { it in inFile }
    }

    /**
     * The camel-case words of [name], long enough to mean something and singularised, so
     * `MassCommonFragments` and `TransformFragment` meet at `fragment`.
     */
    private fun words(name: String): Set<String> =
        CAMEL_WORD.findAll(name)
            .map { it.value.lowercase() }
            .filter { it.length >= 4 }
            .map { if (it.length > 5 && it.endsWith("s")) it.dropLast(1) else it }
            .toSet()

    private val CAMEL_WORD = Regex("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])")
}
