---
id: 0014
title: Give lsp4ij the access of each AngelScript reference, and group references under the names Rider gives C++ and C# access
status: accepted
date: 2026-11-20
area: lsp-client
applies-to:
  plugin: 1.1.0
  lsp4ij: "0.30"
  language-server: v1.9.4
  rider: "2025.2"
touches:
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptUsageFeature.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptUsageTypeGroupingRuleProvider.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptAccess.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptLanguageServerFactory.kt
  - src/rider/main/resources/META-INF/plugin.xml
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/usages/AngelScriptAccessTest.kt
supersedes: [0009]
superseded-by: null
---

# Give lsp4ij the access of each AngelScript reference, and group references under the names Rider gives C++ and C# access

## TL;DR

`AngelScriptUsageFeature` answers lsp4ij's `LSPUsageFeature.getReadWriteAccess` with
`AngelScriptAccess`, so every AngelScript reference whose access is known is a
`ReadWriteAccessUsage`, and the read and write access filters and icons of Find Usages work for it.
The plugin's usage type provider is gone. The grouping rule stays, reads the access from the usage,
and names the groups as Rider's backend does: "Read access", "Write access", "Read write access".
How a reference is classified is unchanged from [0009](0009-classify-reference-access-from-the-parse-tree.md).

## Context

- 0009 could deliver only the grouping. The filters and icons act on `ReadWriteAccessUsage`, and
  only lsp4ij's searcher creates the usages. The hook it lacked went into lsp4ij as
  redhat-developer/lsp4ij#1682 ([plan 0005](../plans/0005-read-write-usage-filters.md)) and shipped in
  0.30.0, on 2026-11-19. For each `textDocument/references` result, `LSPUsagesManager.toPsiElement`
  asks the usage feature of the server that reported it for the access and stores it on the
  `LSPUsagePsiElement`. `LSPUsageSearcher` wraps an element with an access in
  `ReadWriteAccessUsageInfo2UsageAdapter`, and `LSPUsageTypeProvider` types it `UsageType.READ` or
  `WRITE`, a read-write as a write.
- Rider groups C++ and C# usages by access under "Read access", "Write access" and "Read write
  access", the occurrence kinds of its backend (strings in `JetBrains.ReSharper.Feature.Services.dll`,
  Rider 2025.2.5). The platform's usage types are "Value read" and "Value write"
  (`UsageViewBundle`), and it has none for a reference that both reads and writes. So 0009's groups
  were named unlike C++'s in the same tool window, and `X += 1` and `X++` were among the writes.
- On 2026-10-02 the hook was tried end to end in Rider 2026.2.3, with a local lsp4ij build and the
  plugin's side applied: access icons, both filters, and grouping by "Occurrence Type". A reference
  that both reads and writes has the read-write icon, and is hidden only when both filters are off.

## Decision

- `AngelScriptUsageFeature` is the usage feature of the AngelScript client features
  (`AngelScriptLanguageServerFactory`). Its `getReadWriteAccess` gives `AngelScriptAccess.at` of a
  reference in an AngelScript file. lsp4ij asks it only for references, in a read action.
- `AngelScriptUsageTypeProvider` and its `usageTypeProvider` registration are deleted. lsp4ij's own
  provider types references by the access the plugin gave.
- `AngelScriptUsageTypeGroupingRule` stays, for 0009's reason: Rider runs no rule that groups lsp4ij
  usages by type. A usage that is a `ReadWriteAccessUsage` goes under "Read access", "Write access"
  or "Read write access", from `isAccessedForReading` and `isAccessedForWriting`. Any other
  AngelScript usage goes under the type the usage type providers give it, as before: lsp4ij's
  "References", "Declarations" and so on. When the rule is on is unchanged.

## Alternatives

### Keep the platform's names

"Value read" and "Value write" are what lsp4ij's provider gives, and need no strings of the plugin's
own. But C++ and C# results in the same tool window are under Rider's names, and a group for
`X += 1` needs a name the platform does not have. "Value read", "Value write" and "Read write
access" side by side also sort "References" in between. The user chose Rider's names for all three.

### Read the access from lsp4ij's usage element

`LSPUsagePsiElement.getAccess()` holds the same answer, but only from lsp4ij 0.30. lsp4ij is updated
on its own and `plugin.xml` cannot require a version of it, so on an older one the rule would throw
`NoSuchMethodError` for every AngelScript usage while "Occurrence Type" is on. Read from the usage,
the rule uses nothing newer than lsp4ij 0.21, and the group always agrees with the icon and the
filters, which read the same thing.

### Keep the usage type provider

Registered first, it would keep the reads and writes grouped on an lsp4ij older than 0.30. It would
also type every reference a second time from the same answer, in a second place to keep in step with
lsp4ij's, for a version users get by updating a plugin.

## Consequences

- The filters and icons need lsp4ij 0.30. On an older one nothing fails: references are under
  "References", as before 0009.
- The group names are the plugin's own strings, copied from Rider's backend. If Rider renames its
  kinds, AngelScript's groups keep the old names until they are copied again.
- 0009's consequences still hold: Find Usages and the highlighting of the symbol under the caret can
  disagree, writes the syntax cannot show are reads, the answer is only as good as the parse tree,
  and "Occurrence Type" is not always under Group By.

## How this breaks

- No access icons, the filters change nothing, and references are under "References": lsp4ij no
  longer asks the usage feature. `AngelScriptAccessTest` calls the feature itself, so it cannot
  notice; only Rider shows it.
- Icons and filters work, but references are under "Value read" and "Value write": the usages the
  rule is given are no longer `ReadWriteAccessUsage` - Rider wraps them, say - while lsp4ij's
  provider still types them from the element.
- No usage type groups at all, or groups regardless of "Occurrence Type": see 0009's
  **How this breaks**, which still describes the rule's switch.

## History

- 2026-11-20 - Taken against lsp4ij 0.30.1, closing plan 0005, and supersedes 0009. Rider's names
  and reading the access from the usage are covered by unit tests only; the filters and icons were
  last seen in Rider on 2026-10-02.
