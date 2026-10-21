# Plans

Work that is understood but not done. One file per piece of work, so the investigation behind it
does not have to be repeated by whoever picks it up.

A plan is not a backlog entry. It exists because something was already worked out - a constraint
found, a measurement taken, a design settled - and that knowledge would otherwise be lost between
the session that found it and the session that acts on it.

| # | Plan | Area | Status | Date |
|---|---|---|---|---|
| [0001](0001-configurable-script-roots.md) | Let script root folders be configured | lsp-client | open | 2026-09-20 |
| [0002](0002-notice-script-folders-added-later.md) | Notice script folders that appear while the server runs | lsp-client | open | 2026-09-20 |
| [0003](0003-cpp-navigation-precision.md) | Tell overloads and inherited members apart in C++ navigation | cpp-navigation | open | 2026-09-20 |
| [0004](0004-more-mcp-tools.md) | Give agents C++ declarations, semantic queries and asset lookups for AngelScript | mcp | open | 2026-09-23 |
| [0005](0005-read-write-usage-filters.md) | Make the read and write access filters and icons of Find Usages work for AngelScript | lsp-client | open | 2026-09-25 |
| [0006](0006-register-script-folders-on-first-open.md) | Script folders become content roots the first time a project is opened | editor | done | 2026-10-02 |

## Format

File name `NNNN-kebab-case-title.md`. Front matter, then the sections below.

```yaml
---
id: plan-0004
title: One sentence, what would be true when this is done
status: open              # open | in-progress | done | dropped
date: 2026-09-20          # when the plan was written
area: lsp-client          # same areas as the decision records
depends-on: []            # other plans, or decisions that have to change first
context: decisions/0003-send-only-the-script-folders-as-the-workspace.md   # the background, if recorded
---
```

Sections:

1. **TL;DR** - what is missing, in a couple of lines.
2. **Why it matters** - who hits this and how, ideally measured.
3. **What already works** - the part that is done, so nobody redoes it.
4. **What is missing** - the actual work, concretely enough to start from.
5. **Constraints** - what the work has to respect. Usually the expensive knowledge.
6. **History** - dated lines: written, revised, closed.

## Lifecycle

- **open** - nobody is on it.
- **in-progress** - someone is; say who in **History**.
- **done** - the work landed. Say where: a commit, and a decision record if the work made a choice
  worth recording. Keep the file; it explains what the code now does not have to.
- **dropped** - decided against. Say why. This is the most valuable state to write down, because
  the idea will come back.

Plans are version-controlled deliberately. They were local-only until 2026-09-20, which meant the
investigation behind them lived in one working copy and was invisible to anyone else - including to
an agent working on the same repository from a different checkout.
