---
id: 0001
title: Patch the bundled language server at build time, and prove the patches still resolve
status: accepted
date: 2026-09-20
area: lsp-bundle
applies-to:
  plugin: 0.9.10
  language-server: v1.9.4
touches:
  - scripts/bundle-lsp.js
  - scripts/bundle-dap.js
  - third-party/vscode-unreal-angelscript
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/navigation/CppNavigationBundleTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/lsp/AngelScriptInitialLoadTest.kt
supersedes: []
superseded-by: null
---

# Patch the bundled language server at build time, and prove the patches still resolve

## TL;DR

The language server is an unmodified upstream checkout; everything Rider needs is applied by
`scripts/bundle-lsp.js` to the **esbuild output**, not to the source. Every patch prints `✓` or `⚠`,
and a `⚠` means the bundle must not be shipped. Since 2026-09-20 the bundler also checks the
opposite direction - that the names the injected code *calls* exist in the finished bundle - because
a patch can apply perfectly and still be dead.

## Context

`third-party/vscode-unreal-angelscript` is a submodule pinned to a raw commit SHA (upstream carries
no git tags, so the pin is the SHA of a `Publish version X` commit). Keeping it unmodified is what
makes bumping the pin a matter of re-running the bundler rather than re-applying a patch series.

The patches are regexes over generated JavaScript. That has one failure mode which is invisible in
two different ways:

1. **The pattern stops matching.** Upstream renames something, the `replace` does nothing, and the
   bundle ships without the patch. Each patch therefore reports `✓`/`⚠` explicitly.
2. **The pattern matches but the injected code is dead.** This one had been shipping since 0.9.10.
   The migration to upstream's esbuild layout turned module namespaces into hoisted top-level
   functions, so `scriptsymbols.GetCppSymbol(...)` - which the injected `angelscript/getCppSymbol`
   and `angelscript/navigateToCpp` handlers call - referenced a name the bundle no longer defined.
   Both requests failed at runtime with `scriptsymbols is not defined`. The bundler printed `✓`, the
   plugin treats a failed request as "no C++ symbol at this position", and so **C++ navigation did
   nothing at all for an entire release** without a single log line saying why.

## Decision

- Upstream stays unmodified; patches live in `scripts/bundle-lsp.js` and `scripts/bundle-dap.js`
  and are applied to the bundled output.
- Every patch reports `✓` or `⚠`. A `⚠` is a build failure in review terms: do not ship the bundle.
- `INJECTED_REFERENCES` in `scripts/bundle-lsp.js` lists every upstream name the injected code
  calls. After patching, `verifyInjectedReferences()` checks each one is declared in the bundle and
  reports `✓ Patched code resolves against the bundle (N names)` or names the missing ones.
- Anything the injected handlers depend on goes in that list when it is added.

## Alternatives

### Commit the patches into the submodule

Then every upstream bump is a rebase of a patch series over generated-code-adjacent TypeScript, and
the checkout stops being a verifiable copy of upstream. Rejected.

### Fork upstream

Same cost as the above, plus the fork has to be hosted and kept alive. The plugin needs perhaps a
dozen small behaviours changed; that does not justify owning a fork.

### Patch the TypeScript source before building

Possible - the submodule is a normal checkout - but then the patches have to survive upstream's
source layout rather than its output, which changes more often, and the build has to run the
TypeScript pipeline rather than just esbuild.

### Trust the `✓` lines

That is what shipped 0.9.10 with dead C++ navigation. A regex that applies proves nothing about
whether the code it inserted can run.

## Consequences

- Bumping the upstream pin is cheap, but **every bump must be run and the output read**: a rename
  upstream shows up as a `⚠`, and a layout change shows up as a missing name.
- Tests that read the bundled JavaScript (`CppNavigationBundleTest`, `AngelScriptInitialLoadTest`)
  are the second line of defence; they fail in CI where a human might skim past a warning.
- The injected code cannot use upstream's module structure, only its top-level names. Anything
  deeper has to be reached through a name that exists in the bundle.

## How this breaks

- `npm run bundle` prints `⚠` for any patch, or names missing references.
- A feature that goes through a custom `angelscript/*` request silently does nothing. Check the LSP
  console: the injected handlers log their entry, so a request that logs "Request received" and
  nothing after it is throwing.
- The bundle tests fail after an upstream bump.

## History

- 2026-09-20 - Written after finding that `scriptsymbols.GetCppSymbol` had been unresolvable since
  0.9.10; added `verifyInjectedReferences` and the bundle test in the same change.
