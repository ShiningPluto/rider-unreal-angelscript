# VS Code commands sent by the language server

The AngelScript language server (Hazelight's, bundled as `angelscript-language-server.js`) emits
VS Code command ids on code lenses and completion items. lsp4ij executes such a command by looking
up an IntelliJ action with the same id, and logs

```
Missing '<id>' command! It was referenced by 'angelscript-lsp'.
```

when no action has that id. Every command the server can send therefore needs an action, even when
the correct behaviour is "do nothing here".

## Commands the server sends

| Command | Sent from | Status |
| --- | --- | --- |
| `angelscript.paren` | every function/method completion item | Action exists, no-op. Parentheses are inserted by `AngelScriptParenthesisInserter` after lsp4ij inserts the item. |
| `editor.action.insertSnippet` | file-template code lenses in an empty script (`Create Actor`, `Create Component`, ...), arguments `[{ snippet }]` | Implemented: `InsertSnippetCommandAction` expands the snippet with `AngelScriptSnippets` and places the caret at `$0`. |
| `angelscript.saveAndCreateBlueprint` | code lens on a data asset class, arguments `[uri, className]` | Implemented: `SaveAndCreateBlueprintCommandAction` saves the script, then sends `angelscript.createBlueprint`. |
| `angelscript.saveAndEditAsset` | code lens on a literal asset, arguments `[uri, assetPath]` | Implemented: `SaveAndEditAssetCommandAction` saves the script, then sends `angelscript.editAsset`. |
| `angelscript.openAssets` | code lens listing the blueprints that implement a class, arguments `[typeName]` | Implemented: `OpenAssetsCommandAction` sends `angelscript.openAssets`. |

## How the code lens commands work

The three code lens commands do not talk to the editor themselves: they send
`workspace/executeCommand` to the language server, which forwards `CreateBlueprint` / `FindAssets`
over its own socket and shows its own error when no editor is connected.

The plugin's part is the same for all three (`AngelScriptExecuteCommandAction`):

1. Read the payload from the command's arguments - slot 1 for the commands that save first
   (`[uri, name]`), slot 0 for `openAssets`.
2. Check `AngelScriptUnrealStatusService`. Unless it reports a live editor (`READY` or
   `LOADING_TYPES`), show an `AngelScript` balloon notification and stop, so the user is told why
   nothing happened.
3. For the two commands that save first, save the document the uri points at (falling back to the
   selected file).
4. Send the command after 300 ms, the same settle delay the VS Code extension uses, so Unreal has
   time to compile the script the command refers to.

## Adding a command

1. Add the action id and class to `plugin.xml` under `<actions>`. The id must match the command id
   the server sends, dots included.
2. Subclass `com.redhat.devtools.lsp4ij.commands.LSPCommandAction` and implement
   `commandPerformed(command: LSPCommand, e: AnActionEvent)`.
3. Read the arguments with `command.getArgumentAt(index, SomeArgument::class.java)`. lsp4ij converts
   the JSON the server sent into the class you ask for, so a plain class with `var` fields matching
   the JSON keys is enough (see `InsertSnippetCommandAction.InsertSnippetArgument`).
4. Do document changes inside `WriteCommandAction.runWriteCommandAction(...)`, and return the
   command thread that suits the work (`ActionUpdateThread.EDT` for editor changes).
5. Add a test for the pure part of the work, as `AngelScriptSnippetsTest` and
   `AngelScriptAssetCommandActionsTest` do.
