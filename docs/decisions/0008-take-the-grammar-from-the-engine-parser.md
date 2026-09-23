---
id: 0008
title: Take the grammar from the engine's parser, and measure every change against shipped scripts
status: accepted
date: 2026-09-23
area: editor
applies-to:
  plugin: 0.9.10
  language-server: v1.9.4
  angelscript-plugin: "WillGordon9999/UE-Angelscript master, 2025-03-26 (AngelScript 2.33.0 WIP)"
touches:
  - src/main/bnf/AngelScript.bnf
  - src/main/jflex/AngelScript.flex
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/lang/parser/ValidateProductionFilesTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/lang/parser/CorpusParseReport.kt
supersedes: []
superseded-by: null
---

# Take the grammar from the engine's parser, and measure every change against shipped scripts

## TL;DR

The BNF and the JFlex lexer are the only things that decide what the editor marks as a syntax
error. Nothing filters `PsiErrorElement`s, so wherever they disagree with the engine, the user sees
red on code that compiles. The rules come from the engine's own parser and preprocessor, not from
the documentation, the language server, or what seems plausible. Every grammar change is measured
by parsing the whole Split Fiction script corpus before and after, and no file that parsed before
may fail after.

## Context

The `access` declaration was implemented from a guess: pairs of a level and a class,
`protected AGameMode, private *`. Its two tests used that form and passed. The form does not
exist. All 373 declarations in the corpus, and the engine parser, use one `private`/`protected`
base followed by what is granted. None of the 274 files declaring one parsed. Nobody noticed,
because `ValidateProductionFilesTest` skipped every file containing `access:` or one of four named
arguments, on the grounds that these were "Hazelight extensions". They are part of the public
plugin, and the named arguments parsed all along.

The sources, and what each is good for:

- **The engine.** Hazelight's repository needs an Epic-linked GitHub account. The public derivative
  [WillGordon9999/UE-Angelscript](https://github.com/WillGordon9999/UE-Angelscript) carries the
  same code:
  - `AngelscriptCode/Public/source/as_parser.cpp`: `ParseAccessDecl`, `ParseMethodAttributes`.
  - `as_tokendef.h`: keywords and context keywords.
  - `AngelscriptCode/Private/Preprocessor/AngelscriptPreprocessor.cpp`: f-strings and name
    literals, which never reach the AngelScript parser.
- **The language server grammar**, `language-server/pegjs/angelscript.pegjs`. It is Hazelight's too,
  but it parses half-typed code for completion, so it accepts more than the engine. For example, it
  takes any identifier after a parameter list, and access entries in any order. It is good for
  structure: it showed that class and struct bodies share one rule.
- **The corpus.** The retail Split Fiction build ships its scripts as source under `Split/Script/`:
  15,812 `.as` files, about 1.7M lines, dated 2025-03-07. It is game code and cannot be
  redistributed, so it lives in the gitignored `third-party/angelscript-reference/Script`.
  `Examples/Angelscript/` in it holds Hazelight's own syntax examples.

Measured with `CorpusParseReport`, 2026-09-23:

| Grammar state | Files failing | Newly failing |
|---|---|---|
| before | 470 | - |
| `access` declarations as the engine parses them | 205 | 0 |
| every trailing function attribute `ParseMethodAttributes` accepts | 200 | 0 |
| f-string literal text as the preprocessor reads it | 187 | 0 |

## Decision

- **A rule's shape comes from the engine source.** When the engine accepts something, the grammar
  must not flag it. When the language server is looser than the engine, follow the engine.
- **Context keywords are matched by text.** The engine matches words like `no_discard` with
  `IdentifierIs`, so they are keywords in one position and identifiers everywhere else. The BNF
  quotes them (`'no_discard'`); Grammar-Kit then emits `consumeToken(builder, "no_discard")`. They
  are not added to the lexer: a lexer keyword would make `bool deprecated` a syntax error. `final`,
  `override` and `property` are lexer keywords from before this decision and were left alone.
- **Every grammar or lexer change is measured.** Run
  `./gradlew test --tests CorpusParseReport -Dcorpus.report=<file> -i` before and after, then diff
  the two reports. A line that is only in the after-report is a regression.
- **Expectations are regenerated wholesale.** Run `UpdateTestExpectationsTest`, then review every
  `.txt` that changed; an unexplained change is a regression too.
- **Test inputs use forms that occur in the corpus or the engine source.** Do not invent syntax.
- **`ValidateProductionFilesTest` skips nothing.**

## Alternatives

### Follow the documentation

angelscript.hazelight.se does not mention `access` declarations, `no_discard`, or the escaping
rules for f-strings. What it leaves out is exactly where the guesses went wrong.

### Follow the language server's grammar

It is public and maintained by Hazelight, but it is built to tolerate unfinished code. Taking it
as the rule would hide real errors: `void F() Anything {}` is accepted there, and the engine
rejects it.

### Keep skipping "Hazelight extensions" in the production test

The skips hid 274 files of public-plugin syntax that the grammar got wrong, and hid that four
named-argument patterns needed no skip. A skip needs evidence that the engine rejects the construct
too, or that it cannot reach a public project.

## Consequences

- Full verification needs the corpus locally. Without it, `ValidateProductionFilesTest` fails and
  `CorpusParseReport` cannot run. This was already true of the test before.
- The engine source consulted is a third party's snapshot from 2025-03. Hazelight's current plugin
  may have moved on.
- 187 files still fail. 157 have a stray `;` after a class member (`void F() {};`, `;;`), which the
  engine tolerates. 27 have macro arguments the grammar rejects, such as a negative number or `!`
  inside `meta = (...)`. That leaves three more.

## How this breaks

- New plugin syntax shows up as red in the editor on code that compiles. To check, compare the
  keyword table in `as_tokendef.h` and the parser in `as_parser.cpp` of the new plugin version
  against the BNF.
- A later game or plugin version changes the corpus. Regenerate the baseline report before
  comparing anything against it.
- `CorpusParseReport` reports the first error per file only. A change that fixes one error in a
  file can expose the next; the diff shows this as the same file with a later line.

## History

- 2026-09-23 - Taken while correcting `access` declarations, `no_discard`/`allow_discard` and
  f-string literal text.
