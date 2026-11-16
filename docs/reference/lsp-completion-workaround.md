# LSP Completion Workarounds - Maintenance Guide

What the plugin changes about lsp4ij's completion, why, and how to tell when a change is no longer
needed.

## The constraint

lsp4ij turns each LSP completion item into an `LSPCompletionProposal`, which inserts the item when it
is chosen. That class is `@ApiStatus.Internal`, and the JetBrains Marketplace reports every use of it:
subclassing it, calling its methods, and constructing it. Replacing it is not possible either:
`LSPCompletionFeature.addLookupItem` casts every element it adds back to `LSPCompletionProposal`.

So the plugin never touches the class. It works at the two points around it that are public:

- **Before**: `AngelScriptCompletionFeature.createLookupElement` corrects the `CompletionItem` before
  calling `super.createLookupElement`, so lsp4ij's own insertion does the right thing
  (`AngelScriptCompletionEdits`).
- **After**: `AngelScriptCompletionContributor` runs before lsp4ij's contributor, lets it run with
  `runRemainingContributors`, and wraps each element lsp4ij produced in a `LookupElementDecorator`
  that inserts through the element and then formats. A decorator copies its delegate's user data,
  including `DIRECT_INSERTION`, which tells IntelliJ that the element inserts its text itself.

Until 1.0.1 this was done by `AngelScriptCompletionProposal`, a subclass of `LSPCompletionProposal`
that copied its `apply()` and reached its private members by reflection.

## 1. Edits at the same position are applied in reverse

The LSP specification (3.17, 3.16.6.4) says that edits at the same position are applied in the order
of the array. `LSPIJUtils.applyEdits` turns every edit into a `RangeMarker` up front and applies them
in array order, and a zero-length marker at an insertion point stays in front of text inserted there,
so each later insertion at a position lands before the earlier ones:

```
in array order ["UFUNCTION()\n", "\t"]  ->  "\tUFUNCTION()\n"   (lsp4ij)
                                         ->  "UFUNCTION()\n\t"   (the specification)
```

The server sends such edits when an override of a Blueprint event is completed at column 0: the
`UFUNCTION(BlueprintOverride)` line and the return type are both insertions at the start of the line,
and the return type ended up in front of the macro.

**Fix:** `AngelScriptCompletionEdits.mergeSamePositionEdits` combines the additional edits that start
at the same position into one edit, with their text in array order. By the specification the result
is the same; there is just no order left to lose. Edits that would overlap - two at one position that
both replace text - are left alone. The server sends its additional edits with the completion list,
not on resolve, so they can be merged before lsp4ij sees the item.

**Tests:** `LspSamePositionTextEditTest` pins lsp4ij's reversal and shows that merged edits come out in
array order; `AngelScriptCompletionEditsTest` covers the merge itself.

## 2. Completing right after `::` replaced the operator

The server sends no `textEdit`, so lsp4ij works out the range a completion replaces itself
(`CompletionUtils.computePrefixStartFromInsertText`): it takes the PSI leaf before the caret. After
`FVector::` that leaf is the `::` token, so choosing `ZeroVector` replaced `::` and gave
`FVectorZeroVector`.

**Fix:** `AngelScriptCompletionEdits.prepare` compares the start lsp4ij would use
(`LSPIJUtils.getWordRangeAt`) with the start of the identifier being typed
(`scopeAwarePrefixStart`). Only when they differ - in practice, only right after `:` - does it give the
item a `textEdit` covering the identifier, plus whatever after the caret already continues the
inserted text, as lsp4ij does itself for an item without one.

Everywhere else the item is left without a `textEdit`, on purpose: lsp4ij filters an item that has
one by a prefix it derives from the edit's range and the item's `filterText`, and the server sends a
`filterText` that is not the start of the label for many items - lowercased names, and `MyMethod` for
an override labelled `void MyMethod(...)`. For those, lsp4ij cuts the matching part off the prefix
and the lookup stops filtering. Without a `textEdit`, lsp4ij uses IntelliJ's own prefix matcher,
which filters them correctly.

## 3. Formatting what a completion inserted

After lsp4ij has inserted an item, the plugin reformats the range the main edit and the additional
edits touched, with the AngelScript formatter, so an inserted override follows the project's code
style. This is what `AngelScriptCompletionContributor`'s decorator is for.

## Caret placement

`AngelScriptCompletionProposal` also moved the caret to the end of the main edit. lsp4ij 0.21 does
that itself: `LSPIJUtils.applyEdits` shifts the caret by each edit before it, so it ends up after the
inserted text. Nothing in the plugin does this any more.

## Checking after an lsp4ij upgrade

1. Run `LspSamePositionTextEditTest`. If `testSamePositionEditsComeOutReversedSoTheWorkaroundIsStillNeeded`
   fails, lsp4ij now applies same-position edits in array order and the merging can go.
2. Check that `LSPCompletionFeature.createLookupElement` still builds its element from the item it is
   given, and that `addLookupItem` still computes the filtering prefix as described in section 2.
3. In Rider, with a type database loaded:
   - `FVector::` then a completion: the `::` stays.
   - In the body of an actor class, type the start of `BeginPlay` at column 0 and accept the override:
     `UFUNCTION(BlueprintOverride)` comes first, then `void BeginPlay()`.
   - Type `Actor.GetAct` and keep typing: the list keeps narrowing.
4. Run `./gradlew verifyPlugin -PverifyIde=<installed Rider>`: no internal or override-only API usages.

## History

- 2024-12-30 - `AngelScriptCompletionProposal` written against lsp4ij 0.19.2-SNAPSHOT, blaming an
  unstable comparator in `doApplyEdits()`.
- 2026-09-20 - Reviewed against lsp4ij 0.19.1 and 0.21.0: there is no comparator; the reversal comes
  from applying range markers in array order. `LspSamePositionTextEditTest` added.
- 2026-10-21 - The subclass replaced by item corrections before lsp4ij and a decorator after it, after
  the JetBrains Marketplace review of 1.0.0 reported its internal API usages.

## 4. Parentheses after a function completion

The server attaches the ngelscript.paren command to every function and method completion item.
In VS Code that command inserts `()` after the completed name, puts the caret between the
parentheses and opens signature help. lsp4ij only inserts the item's text, and the plugin's action
for the command is a no-op, so a completed call had no parentheses at all.

**Fix:** `AngelScriptParenthesisInserter` runs from the contributor's decorator, after lsp4ij has
inserted the item, and mirrors the VS Code command's branches:

- a `(` directly before the caret (the user typed it) or directly after it (the call is already
  written) inserts nothing;
- a `.` before the caret is the completion's commit character, so `()` is inserted in front of
  it;
- otherwise `()` is inserted and the caret is moved between the parentheses.

The setting `insertParenthesisOnFunctionCompletion` (default on, Completion settings page) turns it
off, matching the VS Code extension's `UnrealAngelscript.insertParenthesisOnFunctionCompletion`.

**Tests:** `AngelScriptParenthesisInserterTest` covers every branch of the decision.

## 5. Signature help after a function completion

lsp4ij's proposal already calls `AutoPopupController.autoPopupParameterInfo` after inserting an
item, but it does that before the plugin's parentheses exist. At that moment the caret sits right
after the function name, outside any argument list, so the parameter info handler has nothing to
show. `AngelScriptParenthesisInserter` therefore triggers signature help again once `()` has been
inserted and the caret is between them.

The handler for `angelscript` is lsp4ij's `LSPParameterInfoHandler`, registered in `plugin.xml`
under `codeInsight.parameterInfo`.

## 6. What lsp4ij already does - do not reimplement

Checked against lsp4ij 0.21.0 so that these are not added a second time in this plugin:

- **Sorting**: `LSPCompletionItemComparator` sorts items by `sortText`, falling back to the label.
  The server's ordering is what the lookup shows.
- **`filterText` matching**: `LSPCompletionProposal.getAllLookupStrings` returns both
  `filterText` and `label`, so items whose filter text is a lowercased name still match what is
  typed.
- **Signature help**: `LSPCompletionProposal.handleInsert` calls `autoPopupParameterInfo` when the
  server supports signature help (see section 5 for the timing problem).
- **Not implemented by lsp4ij**: `CompletionItem.commitCharacters`. Nothing reads
  `item.getCommitCharacters()`; typing `(` or `.` closes the lookup through IntelliJ's own
  behaviour rather than committing the selected item. The plugin implements them itself, see
  section 7.

## 7. Commit characters

lsp4ij never reads `CompletionItem.commitCharacters`, so typing `(` or `.` while a function was
selected closed the lookup instead of committing the selected item, which is what VS Code does.

`AngelScriptCommitCharacterHandler` (a `TypedHandlerDelegate`) implements them:

- `beforeCharTyped` looks at `LookupManager.getActiveLookup`; only a live lookup is considered.
- The selected element is unwrapped through the decorator chain (lsp4ij's `PrioritizedLookupElement`,
  the plugin's `FormatAfterInsert`) until an `AngelScriptCompletionItemHolder` is found. That
  interface, added for this, exposes the `CompletionItem` the element was built from.
- When the typed character is one of the item's commit characters,
  `LookupImpl.finishLookup(char)` commits the completion and types the character in one step.
- `(` is special: `finishLookup` inserts it itself, so
  `AngelScriptCommitCharacters.suppressParenthesisInsertion` is set while it runs and
  `AngelScriptParenthesisInserter` skips its own pair. Without it the result would be
  `Foo()(` instead of `Foo(`.
- A `.` needs no flag: the parenthesis inserter adds `()` while the completion is inserted and
  `finishLookup` appends the dot, giving `Foo().`.

The behaviour is controlled by the `useCommitCharacters` setting (Completion page, on by default)
because a mistakenly matched character would interrupt typing.

**Tests:** `AngelScriptCommitCharactersTest` covers the character matching.