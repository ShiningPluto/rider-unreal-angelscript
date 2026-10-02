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

A server that does not override the hook gets exactly today's behaviour. The change, with tests,
against lsp4ij `main` at `e88c1f5`, is on the branch `usage-read-write-access` of
[ShiningPluto/lsp4ij](https://github.com/ShiningPluto/lsp4ij), submitted as
[redhat-developer/lsp4ij#1682](https://github.com/redhat-developer/lsp4ij/pull/1682).

Rider keeps the read and write access filters (`RiderUsageFilteringRuleCustomizer` only stops their
state being saved), so in Rider the change shows as the icons and the filters. It does not group
anything there: Rider groups no lsp4ij usage by usage type.

### In the plugin, once lsp4ij has released it

- Give the AngelScript client features a usage feature whose `getReadWriteAccess` returns
  `AngelScriptAccess.at(reference.containingFile, reference.textRange)` for AngelScript files.
- Delete `AngelScriptUsageTypeProvider` and its `plugin.xml` entry: lsp4ij's provider then gives
  the types. Keep `AngelScriptUsageTypeGroupingRuleProvider`, which is what shows them in Rider.
- Group a reference that both reads and writes (`X += 1`, `X++`) on its own, as Rider's
  "Read write access" group does for C++. The IntelliJ Platform has no usage type for it:
  `UsageType` has only `READ` and `WRITE`, and its `UsageTypeGroupingRule` files any usage that
  writes under `WRITE`, so lsp4ij types it "Value write" and the change to lsp4ij keeps that. The
  C++ group comes from Rider's backend. `AngelScriptUsageTypeGroupingRule` can tell from the usage
  itself - a `ReadWriteAccessUsage` accessed both for reading and for writing - and name the group
  as Rider does. The icon and the filters already treat it as read-write.
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
- lsp4ij's `CONTRIBUTING.md` asks for a DCO `Signed-off-by` line on every commit; no check enforces
  it. CI does check that the pull request title follows Conventional Commits (`feat: ...`).

## History

- 2026-09-25 - Written together with decision 0009, which delivered the grouping; the lsp4ij change
  was drafted the same day.
- 2026-09-25 - The draft pushed to a fork; its docs no longer say a known access groups the reference,
  which is not so in Rider.
- 2026-10-02 - Tried end to end in Rider 2026.2.3 (`runLocalRider` with `-PlocalLsp4ij`, and the
  plugin side above applied locally): access icons, the read and write filters, and grouping by
  "Occurrence Type" all work. A read-write reference (`+=`, `++`) gets the read-write icon and
  stays visible unless both filters are off; only its group is "Value write". Submitted as lsp4ij#1682 after the maintainer asked for a PR in
  discussion #1676.
