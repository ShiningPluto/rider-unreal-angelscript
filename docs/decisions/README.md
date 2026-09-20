# Decision records

Why the code is the way it is. Format and rules: [../README.md](../README.md).

| # | Decision | Area | Status | Date |
|---|---|---|---|---|
| [0001](0001-patch-the-language-server-at-bundle-time.md) | Patch the bundled language server at build time, and prove the patches still resolve | lsp-bundle | accepted | 2026-09-20 |
| [0002](0002-send-lowercase-drive-letters-to-the-server.md) | Every URI sent to the language server carries a lower-case drive letter | lsp-client | accepted | 2026-09-20 |
| [0003](0003-send-only-the-script-folders-as-the-workspace.md) | Send the language server only the folders that hold AngelScript | lsp-client | accepted | 2026-09-20 |
| [0004](0004-keep-analysis-usable-during-the-initial-load.md) | Load each workspace root as it is scanned, and re-resolve when a batch lands | lsp-bundle | accepted | 2026-09-20 |
| [0005](0005-find-cpp-declarations-by-reading-headers.md) | Find C++ declarations by reading headers, because Rider's C++ index is out of reach | cpp-navigation | accepted | 2026-09-20 |
| [0006](0006-answer-goto-declaration-synchronously.md) | Answer go-to-declaration while the caret waits, and hand back a real target | cpp-navigation | accepted | 2026-09-20 |
| [0007](0007-match-ignore-globs-as-text.md) | Match ignore globs against the path text, not through java.nio PathMatcher | lsp-client | accepted | 2026-09-20 |

## Reading order

New to the plugin: [0001](0001-patch-the-language-server-at-bundle-time.md) explains how the
language server is built and why patches are the way they are;
[0003](0003-send-only-the-script-folders-as-the-workspace.md) explains what the server is told about
the project. Those two carry most of the context for everything else.

Working on C++ navigation: [0005](0005-find-cpp-declarations-by-reading-headers.md) then
[0006](0006-answer-goto-declaration-synchronously.md).

Sending anything to the language server: [0002](0002-send-lowercase-drive-letters-to-the-server.md)
is an invariant, not a suggestion.

## Not recorded here

Decisions taken before 2026-09-20 are not written up. They can be reconstructed from the code,
`CHANGELOG.md` and git history, and are worth a record only when someone has to touch them and pays
the cost of working them out again - at which point, write one.
