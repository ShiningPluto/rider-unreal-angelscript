---
id: 0009
title: Tell reads from writes in Find Usages with the plugin's parse tree, through a usage type provider and a grouping rule
status: accepted
date: 2026-09-25
area: lsp-client
applies-to:
  plugin: 0.9.10
  lsp4ij: "0.21"
  language-server: v1.9.4
  rider: "2025.2"
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptAccess.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptUsageTypeProvider.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptUsageTypeGroupingRuleProvider.kt
  - src/rider/main/resources/META-INF/plugin.xml
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptAccessTest.kt
supersedes: []
superseded-by: null
---

# Tell reads from writes in Find Usages with the plugin's parse tree, through a usage type provider and a grouping rule

## TL;DR

`textDocument/references` has no notion of access, so the plugin decides read or write for each
reference from its own PSI (`AngelScriptAccess`) and hands the answer to the usage view as a usage
type, from a `usageTypeProvider` ordered ahead of lsp4ij's. Rider removes the platform rule that
groups by usage type, so the plugin groups AngelScript usages by it with a rule of its own. That
gets references grouped into "Value read" and "Value write". The access filters and icons cannot be
reached this way; that part waits on lsp4ij ([plan 0005](../plans/0005-read-write-usage-filters.md)).

## Context

Rider tells reads from writes in C++ Find Usages; for AngelScript every reference was listed under
"References". Checked against lsp4ij 0.21.0 and Rider 2025.2.5:

- The LSP reference result is `Location[]`. Only `textDocument/documentHighlight` carries a
  read/write kind.
- `LSPUsageSearcher` wraps each result in a plain `UsageInfo2UsageAdapter`, and
  `LSPUsageTypeProvider` types every reference as "References". lsp4ij's `main` did the same on
  2026-09-25.
- The usage view groups by the first `UsageTypeProvider` that answers, and only falls back to
  `ReadWriteAccessUsage` when none does (`UsageTypeGroupingRule`). The read and write access
  filters look at nothing but `ReadWriteAccessUsage` (`ReadAccessFilteringRule`,
  `WriteAccessFilteringRule`).
- Rider never runs that grouping rule. `RiderExtensionsConfigurator` unregisters
  `UsageGroupingRuleProviderImpl`, which supplies it, and Rider's own rules
  (`RiderUsageGroupingRuleProvider`) group what its backend found, and anything else only by
  project, folder and file. No usage type of an lsp4ij usage has ever been shown in Rider, lsp4ij's
  "References" included. The same in Rider 2025.2.5 and 2026.2.2.
- The language server does compute access, as `isWriteAccess` on each semantic symbol, but uses it
  only for document highlights. It sets it for the left side of `=` and compound assignments and
  nothing else (`as_parser.ts`, the `Assignment`/`CompoundAssignment` case of `DetectNodeSymbols`).

## Decision

- `AngelScriptAccess.at(file, range)` finds the identifier at exactly the reported range and reads
  the tree around it. Writes are the left side of an assignment (a compound one reads as well),
  the operand of `++`/`--`, and a declarator with an initializer or constructor arguments. `default`
  statements are assignments in the tree, so they are writes too. Any other value position is a
  read. A called function, a type, a `::` qualifier, and a declarator with no value are not values,
  and get null.
- `AngelScriptUsageTypeProvider` answers only for lsp4ij references in AngelScript files, maps
  `Write` and `ReadWrite` to `UsageType.WRITE` and `Read` to `UsageType.READ`, and returns null
  otherwise, so lsp4ij still labels declarations, definitions, calls and types. It is registered with
  `order="first"` because lsp4ij's provider answers for every reference.
- `AngelScriptUsageTypeGroupingRuleProvider` puts back the grouping Rider removes, for lsp4ij
  usages in AngelScript files only: it asks the usage type providers in order, as the platform rule
  does, and gives every other usage no group, so C# and other results keep Rider's grouping. It is
  on while Rider groups its own results by usage type - its rule with the id `OccurrenceType`,
  "Occurrence Type" under Group By, which is never on in the Show Usages popup - and falls back to
  `UsageViewSettings.isGroupByUsageType` in a Rider without that rule. It ranks first, so the groups
  are the outermost level, as in the platform, and is `DumbAware`, since it reads no index.

## Alternatives

### Use the server's own answer

The usage type provider runs synchronously in a read action, once per usage, and the server's answer
is per file and asynchronous - one `documentHighlight` request per file, positioned on the symbol.
It would also inherit the server's narrower idea of a write, which misses `++` and declarations.

### Make the filters work from the plugin

The filters need `ReadWriteAccessUsage`, which only the code creating the usage can supply, and that
is lsp4ij's. Registering our own filtering rules with the platform's action ids produces a second
pair of toggle buttons (`UsageFilteringRuleActions` makes one per rule). Suppressing lsp4ij's searcher
for AngelScript and searching ourselves is not possible either: `LSPUsageSearcher` and
`LSPFindUsagesHandlerFactory` share one gate, so turning one off turns off Find Usages.

## Consequences

- Find Usages and the highlighting of a symbol under the caret can disagree: the highlight comes
  from the server and calls `X++` and `int X = 0` reads. Patching the server to agree would change
  its unused-variable diagnostic, which relies on writes not counting as uses.
- A write through a subscript or an `&out` argument is a read here, as it is to the server.
- The answer is only as good as the parse tree. A reference in code that fails to parse may be a
  read or get no group at all.
- Once lsp4ij can be told the access of a reference, `AngelScriptAccess` is what it should be told,
  and the provider goes ([plan 0005](../plans/0005-read-write-usage-filters.md)). The grouping rule
  stays: in Rider nothing else groups by the types lsp4ij gives.
- "Occurrence Type" is not always under Group By in the Find tool window of AngelScript results.
  For results its backend did not find, Rider offers only File, Directory, Solution Folder and
  Project, the rules that can group them (`RiderUsageGroupingRuleProvider.filterRules`) - unless the
  view had no results yet when the menu was built, once, as the view opened, which is the usual
  case. The setting is shared, so a C++ or C# search can change it too.
- Rider's root group, "Found usages", which every AngelScript result tree has, sits under each usage
  type group instead of at the top. Rider leaves it out while indexes are rebuilt, as it is not
  `DumbAware`.

## How this breaks

- Every reference is under "References" again: lsp4ij changed `LSPUsagePsiElement`, its kinds, or
  started registering its provider `first`. `AngelScriptAccessTest` builds `LSPUsagePsiElement`s
  directly, so it catches the first two, not the third.
- No usage type groups at all in the Find tool window, with "Occurrence Type" checked: the grouping
  rule is not asked, or Rider's rule is no longer found by its id. `AngelScriptAccessTest` stands in
  for Rider's provider, so it catches neither; only Rider shows it.
- Usage type groups whether "Occurrence Type" is checked or not: Rider renamed the rule, and the
  grouping has fallen back to `isGroupByUsageType`, which is on in every Find tool window.
- AngelScript usages grouped by usage type twice, nested: Rider stopped unregistering the
  platform's `UsageGroupingRuleProviderImpl`, and the plugin's rule should go.
- A kind of expression is always a read, or has no group: the grammar changed the shape of
  assignments, member access, postfix or scoped identifiers. `AngelScriptAccessTest` covers each
  shape the classification depends on.

## History

- 2026-09-25 - Taken; the filters and icons are left to plan 0005.
- 2026-09-25 - Grouping rule added. The first build showed no groups in Rider: the provider had
  been checked against the platform's grouping rule, which Rider does not run.
- 2026-09-25 - The grouping follows "Occurrence Type" rather than `isGroupByUsageType`: the groups
  appeared with that item unchecked, so the item is not that setting.
