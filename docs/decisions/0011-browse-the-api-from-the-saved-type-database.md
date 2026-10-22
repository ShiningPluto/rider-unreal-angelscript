---
id: 0011
title: Browse the Unreal API from the type database the server saves, and save it whatever the cache setting says
status: accepted
date: 2026-10-22
area: lsp-client
applies-to:
  plugin: 1.0.1
  lsp4ij: "0.21"
  language-server: v1.9.4
  rider: "2025.2"
touches:
  - scripts/bundle-lsp.js
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/typedb/UnrealTypeDatabase.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/typedb/UnrealTypeDatabaseService.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/typedb/ScriptDeclarations.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/toolwindow/AngelScriptApiPanel.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/toolwindow/ApiNode.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/services/AngelScriptApiService.kt
  - src/rider/main/kotlin/com/scriptacus/riderunrealangelscript/status/AngelScriptUnrealStatusService.kt
verified-by:
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/typedb/UnrealTypeDatabaseTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/toolwindow/ApiBrowserTest.kt
  - src/test/kotlin/com/scriptacus/riderunrealangelscript/status/AngelScriptUnrealStatusTest.kt
supersedes: []
superseded-by: null
---

# Browse the Unreal API from the type database the server saves, and save it whatever the cache setting says

## TL;DR

The API tool window reads `unreal-type-database.json`, the copy of Unreal's type database the
bundled server already saves for offline analysis, and builds the tree in the plugin. Upstream's
API requests cannot list a class's members, and no new server request was added for it. For the
file to be usable, the server now saves every database an editor sends; the cache setting only
decides whether a saved one is loaded. What script declares is not in the file and comes from the
server, through one added request, `angelscript/getScriptDeclarations`, which lists each script
function and variable in a namespace whole. The browser builds its tree, search and details from that
and the file, and asks the server nothing else.

## Context

Upstream's `GetAPIList` (`language-server/src/api_docs.ts`) lists only the root namespace's child
namespaces, and a namespace's functions and properties. Classes are never listed. `APawn::` in the
tree is the namespace `__APawn` the editor sends for APawn's static functions: two of them, against
30 methods and 13 properties APawn declares. `GetAPISearch` does reach class members, but only a
class's own (`forEachSymbol(..., false)`), never inherited ones.

The editor's database has what is missing. Measured on a project's saved file (25.8 MB, 28,850
types of which 4,758 are enums sent under `__` names, 1,913 namespaces, about 160,000 members):
every type carries `inherits`, `doc`, `properties`
and `methods`, with argument types, defaults, `const`, `protected` and blueprint event flags.
A project's `ACharacter` subclass declares 110 members and inherits 399 more through five
ancestors. 20,188 of the global functions are constructors of structs and delegates, named after
their type; 72 are not.

Upstream's parse drops the C++ name of events: `unrealname` is read only alongside `ufunction`,
which events never carry. 398 of the 900 functions with a C++ name in that file are events.

Upstream's `getAPI`, `getAPISearch` and `getAPIDetails` (`server.ts`) do not answer until the server
has Unreal's types: they poll `HasTypesFromUnreal()` every 100 ms with no limit. Nothing script
declares can be listed through them while no editor has connected. `getAPI` also never lists what
is directly in the root namespace, and skips mixins everywhere; `getAPISearch` finds both, but only
by name. The project measured has 1,272 script files, about 36 file-level functions and about 40
mixins.

Their items carry a name and, for a function, `DBMethod.id`, but no signature: overloads are
listed as the same `Name()`. `getAPIDetails` finds a function by that id, and the id is a counter
taken when the parse creates the method, so the next parse of the file - opening it is enough -
leaves every id the browser holds unknown. `getAPIDetails` then answers for the last function of
that name, and overloads that each had their own documentation all show the same one. The
constructors of a script struct are root-namespace functions named after it, carrying its
documentation, and one is generated for every struct that declares no default constructor; they
were most of what the root namespace held.

## Decision

- `UnrealTypeDatabase` parses the saved file a type at a time with Gson's streaming reader: 354 ms
  for the file above, about 62 MB retained. It splits `__` names into namespaces (enums excepted),
  moves constructors to their type, keeps every C++ name including events', and answers supertype
  chains, inherited members (minus what a subclass redeclares, by name for properties and by
  argument types for methods) and search.
- `UnrealTypeDatabaseService` follows the server, not the file. It reads the file when
  `AngelScriptUnrealStatusService` reports that the server has types, and again when the server
  reports a database captured at another time. The server saves before it reports, in the same
  tick. While the server has no types, nothing is shown, even if a file exists.
- The status service publishes `TOPIC` when the status or the capture time changes. The browser
  starts polling itself instead of relying on the status bar widget having done so.
- The bundle patch saves every database (`__asTypeCache.path` is the only condition) and the setting
  became `loadEnabled`, passed as `--no-type-cache-load`. Turning it off no longer drops chunks being
  received.
- A bundle patch adds `angelscript/getScriptDeclarations`: every function and variable a script
  module declares in any namespace, the root included, with its namespace, module, file, return or
  variable type, arguments, `const`, whether it is a mixin, and documentation. Constructors are left
  out, since the browser does not list script types. `ScriptDeclarations` reads the answer into the
  classes the file is read into, with `scriptOrigin` set.
- Script namespaces are those the answer has members in, and their parents, that the file does not
  declare. What script adds to a namespace Unreal declares, or outside any, is listed after Unreal's,
  followed in italics by the file that declares it; that note is in italics, and worded as a
  location, so that it does not read as the return type before it. A mixin is listed under "Script
  mixins" with the type its first argument names and that type's subclasses, where it says "from"
  the supertype it extends.
- Search and details are worked out in the plugin for script as for Unreal, so nothing is asked by
  id. Script results are listed under "From scripts".
- The script declarations are asked once per database, again whenever the browser is shown, and on
  Refresh. The tree is built again when an answer differs from the last, with the rows that were
  expanded and the one selected found again by their text.
- Nothing is asked of the server without a database: a database means the server has the types its
  API requests wait for. Without one, the browser only says why.
- Opening the browser starts the server if it is not running. Upstream's requests used to do that
  as a side effect, and nothing is asked of a stopped server now.

## Alternatives

### A new server request for a class's members

A `getTypeMembers` handler in `bundle-lsp.js` would have been a few dozen lines. Rejected to keep the
patch surface small (see [0001](0001-patch-the-language-server-at-bundle-time.md)): the file already
existed, its format is the editor's rather than upstream's internal one, and reading it needs
nothing from the server's code to keep resolving.

### Upstream's requests for what they can list, and an added one for the rest

The first version of this decision asked `getAPI` for script namespaces and their members,
`getAPISearch` for script search results, `getAPIDetails` for their details, and an added request
only for script globals and mixins. Overloads could not be told apart in the tree, and their details
went stale as described above. Answering for those in the added request as well was a smaller
change than working around upstream's ids, and left one request to keep in step with upstream.

### Listing script declarations without a new request

The file cannot answer for script. `textDocument/documentSymbol` lists a file's top-level functions
and variables, but one file at a time: 1,272 requests on the project measured, each returning every
class and member in its file. The plugin's own parser could find them without the server, but
parsing 28 MB of script on first use needs an index to be fast, and can disagree with the server.
`workspace/symbol` returns nothing for an empty query and matches members only from five
characters. A handler that only reads the server's namespace tree costs about 40 lines and changes
no upstream logic, so it was added.

### Connecting to the editor's debug server from the plugin

The plugin could request the database itself. That means a second 25 MB transfer per connection and
re-implementing the binary message framing, for data the server already writes to disk.

### Leaving saving behind the setting

The file would then be stale or absent whenever the setting is off, so the browser would show an API
the server is not using. With saving unconditional, the setting off and no editor connected means no
types at all, so the browser shows nothing, which is consistent.

## Consequences

- The saved file is now an interface between the bundle and the plugin: its name, location
  (`UnrealTypeDatabaseService.cacheFile`) and `{"version":1,"chunks":[...]}` layout. A change to the
  format needs a new version number, which `UnrealTypeDatabase.parse` rejects rather than misreads.
- Every editor connection writes the file, about 26 MB for the project measured, even for users who
  turned the setting off.
- The browser holds the parsed database for the rest of the session once it has been opened.
- Script classes are not in the browser at all, as before.
- The answer holds every script function and variable in a namespace with its documentation, and is
  sent whenever the browser is shown. What script edits while the browser is on screen shows after
  Refresh, or the next time it is shown.
- The server does not keep the default values of a script function's arguments, so script
  signatures have none; Unreal's do.
- A mixin extending a type script declares, or a template instantiation (`TArray<AActor>` is filed
  under `TArray`), is only as precise as the declared type of its first argument.
- The file is read through NIO, which opens it with `FILE_SHARE_DELETE` on Windows. The server
  replaces it by renaming over it, and a `java.io` reader holding it open would make that fail.

## How this breaks

- The browser says "Could not read the saved type database": the bundle stopped saving, or moved the
  file. Check `--type-cache=` in the server's command line and the `[typeCache] Saved` line in its
  log.
- Classes show members but no "Inherited from" groups: the editor stopped sending `inherits`.
- `AngelScriptUnrealStatusTest.testTheDatabaseIsSavedWhateverTheSettingSays` fails: saving depends on
  the setting again.

## History

- 2026-10-22: Taken.
- 2026-10-22: Script globals and mixins come from an added request. Script namespaces are no longer
  shown without a database: that relied on `getAPI("")` answering without Unreal's types, which it
  does not.
- 2026-10-22: Everything script declares comes from `angelscript/getScriptDeclarations`, which
  replaced `angelscript/getScriptGlobals`; upstream's API requests are no longer used. Overloads
  showed as one name with stale details, and script struct constructors filled the globals.
