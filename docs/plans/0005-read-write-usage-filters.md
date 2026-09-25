---
id: plan-0005
title: The read and write access filters and icons of Find Usages work for AngelScript references
status: open
date: 2026-09-25
area: lsp-client
depends-on: []
context: decisions/0009-classify-reference-access-from-the-parse-tree.md
---

# The read and write access filters and icons of Find Usages work for AngelScript references

## TL;DR

References are grouped into "Value read" and "Value write", but the read and write access filters
of the Find tool window and the Show Usages popup ignore them, and they have no read or write icon.
Both need the usage itself to be a `ReadWriteAccessUsage`, which only lsp4ij can create. The change
to lsp4ij is small and designed below; what is left in the plugin is a few lines once it ships.

## Why it matters

The filters are how C++ usages in Rider are narrowed to the writes. The grouping gets most of the
way there in the Find tool window; the Show Usages popup relies on the filters and icons.

## What already works

- `AngelScriptAccess.at` classifies a reference from the parse tree
  ([decisions/0009](../decisions/0009-classify-reference-access-from-the-parse-tree.md)).
- `AngelScriptUsageTypeProvider` turns that into the "Value read" and "Value write" usage types,
  and `AngelScriptUsageTypeGroupingRuleProvider` groups by them, which Rider does not do itself.

## What is missing

### In lsp4ij

A hook on the usage feature, asked once per reference while the usage is being created:

- `LSPUsageFeature.getReadWriteAccess(LSPUsagePsiElement reference)`, returning
  `ReadWriteAccessDetector.Access` or null, null by default.
- `LSPUsagePsiElement` keeps the answer. It is asked in `LSPUsagesManager.toPsiElement`, the one
  place both the search and the popup's cached references go through with the client features of
  the server that reported them, and only for `UsageKind.references`.
- `LSPUsageSearcher` creates a `ReadWriteAccessUsageInfo2UsageAdapter` when the access is known,
  and the plain adapter otherwise.
- `LSPUsageTypeProvider` groups a reference with a known access under `UsageType.READ` or
  `UsageType.WRITE` (a read-write counts as a write, as the platform does), and every other usage as
  before.

A server that does not override the hook gets exactly today's behaviour. A draft with tests was
written on 2026-09-25 against lsp4ij `main` at `e88c1f5` and has not been submitted.

### In the plugin, once lsp4ij has released it

- Give the AngelScript client features a usage feature whose `getReadWriteAccess` returns
  `AngelScriptAccess.at(reference.containingFile, reference.textRange)` for AngelScript files.
- Delete `AngelScriptUsageTypeProvider` and its `plugin.xml` entry: lsp4ij's provider then gives
  the types. Keep `AngelScriptUsageTypeGroupingRuleProvider`, which is what shows them in Rider.
- Raise the minimum lsp4ij version, and say so in the changelog.

## Constraints

- **Why not the platform's `ReadWriteAccessDetector`.** The obvious lsp4ij change is to create
  usages with `UsageInfoToUsageConverter.convert`, which asks every registered detector. It was
  weighed and rejected:
  - `ReadWriteUtil.getReadWriteAccess` calls each detector with no exception handling, so one
    that throws on lsp4ij's fake elements breaks Find Usages for every language server. Rider
    2025.2.5 registers four (XML, SQL, JavaScript, YAML), all plain `instanceof` checks; the other
    IDEs lsp4ij runs in have more.
  - `getExpressionAccess` must return an access. Declarations, definitions and implementations
    would have to be classified as well, and hiding reads would then hide the definitions.
  - Grouping would not change, because lsp4ij's own usage type provider answers first.
- **Nothing in the plugin can substitute for this**; see the alternatives in
  [decisions/0009](../decisions/0009-classify-reference-access-from-the-parse-tree.md).
- lsp4ij supports IDEs from 2024.2. `ReadWriteAccessUsageInfo2UsageAdapter(UsageInfo, Access)` and
  `UsageType.READ`/`WRITE` are present there.
- lsp4ij contributions need a DCO `Signed-off-by` line on every commit.

## History

- 2026-09-25 - Written together with decision 0009, which delivered the grouping; the lsp4ij change
  was drafted the same day.
