# Documentation

This plugin sits between three systems that each have their own idea of how things work - the
IntelliJ platform, lsp4ij, and a language server built for VSCode - and most of the hard-won
knowledge here is about where those ideas disagree. That knowledge does not fit in code comments:
it is about what was tried, what was measured, and what will break the next time one of the three
changes.

So the documentation is organised around **why**, not **what**.

| Where | What belongs there |
|---|---|
| [decisions/](decisions/) | Why the code is the way it is: the choice, the alternatives, the evidence, and what would invalidate it |
| [plans/](plans/) | Work that is understood but not done, with the investigation behind it |
| [reference/](reference/) | How a specific mechanism works and how to maintain it, when the mechanism is too intricate for comments |
| [../CHANGELOG.md](../CHANGELOG.md) | What changed, for users |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | How to build, test and submit |

All of it is version-controlled. A decision that is not in the repository is a decision the next
person re-takes, and a plan that lives in one working copy is an investigation the next person
repeats.

Code comments still carry the local "why"; a decision record carries the kind that spans files,
outlives the code it was written for, or was expensive to learn.

## Decision records

A decision record is one file per decision, in [decisions/](decisions/), numbered and dated. The
[index](decisions/README.md) lists them all.

### When to write one

Write a record when any of these is true:

- **A choice constrains future work.** "The workspace we send the server is only the script folders"
  changes what every later feature may assume.
- **An alternative was rejected for a reason that is not obvious from the code.** If the next person
  will ask "why didn't they just use X", the answer belongs in a record, with the evidence that
  killed X.
- **A failure was invisible.** Anything that broke without an error message, a warning or a failing
  test is worth a record, because it will break that way again.
- **A measurement drove the design.** Numbers rot in a pull request description; they belong
  somewhere a future maintainer can find them and re-take them.

Do **not** write one for an ordinary bug fix that the code and the changelog already explain.

### When to update one

Records are not immutable, but they are append-only in spirit:

- Something measured turned out to be wrong, or a version changed the behaviour: correct the body
  and add a line to **History**. A record that quietly describes an older reality is worse than no
  record.
- The decision was replaced: set `status: superseded`, fill in `superseded-by`, and write the new
  record. Do not delete the old one - its rejected alternatives are still useful.
- The decision was undone: set `status: reverted` and say why in **History**.

### Format

File name `NNNN-kebab-case-title.md`, where `NNNN` is the next free number. Start with YAML front
matter, then the sections below. Both are what makes the records usable by an agent as well as a
person: the front matter answers "is this still relevant, and to which versions" without reading
the prose, and the fixed headings mean the interesting part can be found without reading the whole
file.

```yaml
---
id: 0007
title: One sentence, in the imperative or as a statement of fact
status: accepted          # proposed | accepted | superseded | reverted
date: 2026-09-20          # when the decision was taken
area: cpp-navigation      # lsp-bundle | lsp-client | cpp-navigation | mcp | build | editor | docs
applies-to:               # the versions the decision was taken against; omit what is irrelevant
  plugin: 0.9.10
  lsp4ij: "0.21"
  language-server: v1.9.4
  rider: "2025.2"
touches:                  # the code this decision governs
  - src/rider/main/kotlin/.../Something.kt
verified-by:              # tests that fail if the decision is silently undone, if any
  - src/test/kotlin/.../SomethingTest.kt
supersedes: []
superseded-by: null
---
```

Sections, in this order:

1. **TL;DR** - three or four lines. What was decided and the one thing to remember.
2. **Context** - what forced the decision. Measurements go here, with how they were taken.
3. **Decision** - what the code does now.
4. **Alternatives** - what else was considered and what ruled it out. One subsection each.
5. **Consequences** - what this costs, what it makes harder, what it obliges future work to respect.
6. **How this breaks** - the signals that the decision no longer holds, and how to check.
7. **History** - dated lines: when it was taken, corrected, superseded.

Keep them short. A record that is skimmed is worth more than one that is complete.

### Conventions

- **English**, like the rest of the repository.
- **Absolute claims need evidence.** "The SDK does not ship C++ PSI" is only useful with the
  packages that were checked; "the scan takes minutes" is only useful with the project it was
  measured on.
- **Link to code by path**, not by quoting it. Quotes go stale; paths survive renames badly too, but
  a wrong path is obvious while a wrong quote is not.
- **Name versions.** lsp4ij, the bundled language server and Rider all change behaviour underneath
  this plugin; a record without versions cannot be re-checked.

## Plans

Work that is understood but not done, one file per piece of work, in [plans/](plans/). The point is
the investigation, not the intention: what was already ruled out, what constrains the work, what
part of it is already built. A plan whose work lands is closed rather than deleted - and if the work
made a choice worth keeping, it becomes a decision record.

Format and lifecycle: [plans/README.md](plans/README.md).

## Reference documents

Longer maintenance guides for mechanisms that are too intricate to live in comments:

- [reference/lsp-completion-workaround.md](reference/lsp-completion-workaround.md) - the
  reflection-based completion proposal and how to tell whether lsp4ij has fixed the underlying bug.
- [reference/debugging.md](reference/debugging.md) - debugging AngelScript with the bundled debug
  adapter.

A reference document describes a mechanism as it is now, and is rewritten when the mechanism
changes. A decision record describes a choice as it was made, and is superseded rather than
rewritten.
