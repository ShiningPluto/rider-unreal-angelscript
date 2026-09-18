const esbuild = require('esbuild');
const path = require('path');
const fs = require('fs');

// Project root is one level up from scripts/
const projectRoot = path.join(__dirname, '..');

async function bundle() {
  try {
    // Bundle directly from TypeScript source. Upstream switched to esbuild
    // and no longer produces language-server/out/ — esbuild handles .ts natively.
    await esbuild.build({
      entryPoints: [path.join(projectRoot, 'third-party', 'vscode-unreal-angelscript', 'language-server', 'src', 'server.ts')],
      bundle: true,
      outfile: path.join(projectRoot, 'src', 'rider', 'main', 'resources', 'js', 'angelscript-language-server.js'),
      platform: 'node',
      target: 'node14',
      format: 'cjs',
      external: [],
      minify: false,
      sourcemap: false,
      logLevel: 'info',
    });

    // Patch the bundled file to use stdin/stdout instead of IPC
    const bundledPath = path.join(projectRoot, 'src', 'rider', 'main', 'resources', 'js', 'angelscript-language-server.js');
    let content = fs.readFileSync(bundledPath, 'utf8');
    
    // Replace the createConnection call to use stdin/stdout
    // The bundled code uses (0, node_1.createConnection) pattern
    content = content.replace(
      /node_1\.IPCMessageReader\(process\)/g,
      'node_1.StreamMessageReader(process.stdin)'
    );
    content = content.replace(
      /node_1\.IPCMessageWriter\(process\)/g,
      'node_1.StreamMessageWriter(process.stdout)'
    );
    
    // Also handle non-namespaced version just in case
    content = content.replace(
      /IPCMessageReader\(process\)/g,
      'StreamMessageReader(process.stdin)'
    );
    content = content.replace(
      /IPCMessageWriter\(process\)/g,
      'StreamMessageWriter(process.stdout)'
    );

    // Patch NotifyDiagnostics to normalize drive letter before sending to client
    // This ensures diagnostics from ALL sources (UpdateCompileDiagnostics, UpdateScriptModuleDiagnostics, etc.)
    // are normalized to lowercase drive letters before being sent to the LSP client.
    //
    // IMPORTANT: We only normalize the drive letter (D: -> d:), NOT the entire path.
    // The server's NormalizeUri() lowercases everything, which breaks case-sensitive paths.
    //
    // Original code:
    //   if (notifyEmpty || allDiagnostics.length != 0) {
    //     for (let func of NotifyFunctions)
    //       func(uri, allDiagnostics);
    //   }
    //
    // Patched code:
    //   if (notifyEmpty || allDiagnostics.length != 0) {
    //     let notifyUri = uri.replace(/^(file:\/\/\/)([A-Z])(:)/, (m, p, d, c) => p + d.toLowerCase() + c);
    //     for (let func of NotifyFunctions)
    //       func(notifyUri, allDiagnostics);
    //   }

    const notifyDiagnosticsPattern = /if \(notifyEmpty \|\| allDiagnostics\.length != 0\) \{\s*for \(let func of NotifyFunctions\)\s*func\(uri, allDiagnostics\);/;
    const notifyDiagnosticsReplacement =
      'if (notifyEmpty || allDiagnostics.length != 0) {\n        let notifyUri = uri.replace(/^(file:\\/\\/\\/)([A-Z])(:)/, (m, p, d, c) => p + d.toLowerCase() + c);\n        for (let func of NotifyFunctions)\n          func(notifyUri, allDiagnostics);';

    if (notifyDiagnosticsPattern.test(content)) {
      content = content.replace(notifyDiagnosticsPattern, notifyDiagnosticsReplacement);
      console.log('✓ Patched NotifyDiagnostics to normalize drive letters before notification');
    } else {
      console.warn('⚠ Warning: Could not find NotifyDiagnostics pattern to patch');
    }

    // Patch to defer initial Unreal connection until configuration is received
    // Original: connect_unreal() called immediately on startup with default port 27099
    // Patched: Set port to null initially, so first config always triggers connection
    //
    // This ensures the server uses the user-configured port from the start,
    // rather than connecting on 27099 and then reconnecting.

    // Step 1: Change initial port from 27099 to -1 (unconfigured)
    // Using -1 instead of null to ensure != comparison works reliably
    content = content.replace(
      /var port = 27099;/,
      'var port = -1;'
    );

    // Step 2: Comment out the immediate connect_unreal() call
    content = content.replace(
      /^connect_unreal\(\);$/m,
      '// connect_unreal(); // Patched: deferred until config received'
    );

    console.log('✓ Patched to defer Unreal connection until configuration received');

    // Patch to add C++ navigation request handlers
    // These handlers allow the Rider plugin to get C++ symbol info and navigate to C++ code
    const cppNavigationHandlers = `
connection.onRequest("angelscript/getCppSymbol", (params) => {
  let uri = params.uri;
  let position = params.position;
  connection.console.log(\`[getCppSymbol] Request received - URI: \${uri}, Position: \${position.line}:\${position.character}\`);
  let asmodule = GetAndParseModule(uri);
  if (!asmodule) {
    connection.console.log(\`[getCppSymbol] Module not found for URI: \${uri}\`);
    return null;
  }
  if (!asmodule.resolved) {
    connection.console.log(\`[getCppSymbol] Module not resolved: \${uri}\`);
    return null;
  }
  connection.console.log(\`[getCppSymbol] Module found and resolved: \${asmodule.modulename}\`);
  let cppSymbol = scriptsymbols.GetCppSymbol(asmodule, position);
  if (!cppSymbol) {
    connection.console.log(\`[getCppSymbol] No C++ symbol found at position \${position.line}:\${position.character}\`);
    return null;
  }
  connection.console.log(\`[getCppSymbol] C++ symbol found: \${cppSymbol[0]}.\${cppSymbol[1]}\`);
  return {
    className: cppSymbol[0],
    symbolName: cppSymbol[1]
  };
});
connection.onRequest("angelscript/navigateToCpp", (params) => {
  let uri = params.uri;
  let position = params.position;
  connection.console.log(\`[navigateToCpp] Request received - URI: \${uri}, Position: \${position.line}:\${position.character}\`);
  let asmodule = GetAndParseModule(uri);
  if (!asmodule) {
    connection.console.log(\`[navigateToCpp] Module not found for URI: \${uri}\`);
    return false;
  }
  if (!asmodule.resolved) {
    connection.console.log(\`[navigateToCpp] Module not resolved: \${uri}\`);
    return false;
  }
  connection.console.log(\`[navigateToCpp] Module found and resolved: \${asmodule.modulename}\`);
  let cppSymbol = scriptsymbols.GetCppSymbol(asmodule, position);
  if (!cppSymbol) {
    connection.console.log(\`[navigateToCpp] No C++ symbol found at position \${position.line}:\${position.character}\`);
    return false;
  }
  connection.console.log(\`[navigateToCpp] C++ symbol found: \${cppSymbol[0]}.\${cppSymbol[1]}\`);
  if (unreal) {
    connection.console.log(\`[navigateToCpp] Sending goto command to Unreal Engine\`);
    unreal.write(buildGoTo(cppSymbol[0], cppSymbol[1]));
    return true;
  }
  connection.console.log(\`[navigateToCpp] Not connected to Unreal Engine\`);
  return false;
});
`;

    // Find the location to insert the handlers (after angelscript/getAPIDetails)
    // Match the complete getAPIDetails handler ending with "return promise;\n});"
    // Use a flag to ensure we only add once
    let handlerAdded = false;
    const insertionPoint = /connection\.onRequest\("angelscript\/getAPIDetails"[\s\S]*?return promise;\s*\}\);/;
    content = content.replace(insertionPoint, (match) => {
      if (!handlerAdded) {
        handlerAdded = true;
        return match + cppNavigationHandlers;
      }
      return match;
    });

    if (handlerAdded) {
      console.log('✓ Patched to add C++ navigation request handlers');
    } else {
      console.warn('⚠ Warning: Could not find insertion point for C++ navigation handlers');
    }

    // Patch to expose the Unreal editor connection state.
    //
    // Highlighting and diagnostics both require the C++ type database, which only exists inside a
    // running Unreal editor. When it is missing the server simply produces nothing - upstream sets
    // UnrealTypesTimedOut on connection timeout but never reads it, so there is no user-visible
    // signal at all. This handler lets the Rider plugin report the state in the status bar.
    const unrealStatusHandler = `
connection.onRequest("angelscript/getUnrealStatus", () => {
  return {
    configuredPort: port,
    // readyState alone is ambiguous: a socket that has not been connected yet still reports
    // "open", so check connecting first to tell "dialling" apart from "connected".
    socketState: unreal ? (unreal.connecting ? "connecting" : unreal.readyState) : "disconnected",
    typesLoaded: HasTypesFromUnreal(),
    usingCachedTypes: __asTypeCache.loaded,
    // When the database in use was captured, live or cached. HasTypesFromUnreal() never goes back
    // to false, so this is what tells a current database apart from one an editor left behind.
    typesCapturedAt: __asTypeCache.capturedAt
  };
});
`;

    let statusHandlerAdded = false;
    const statusInsertionPoint = /connection\.onRequest\("angelscript\/getAPIDetails"[\s\S]*?return promise;\s*\}\);/;
    content = content.replace(statusInsertionPoint, (match) => {
      if (!statusHandlerAdded) {
        statusHandlerAdded = true;
        return match + unrealStatusHandler;
      }
      return match;
    });

    if (statusHandlerAdded) {
      console.log('✓ Patched to add Unreal connection status handler');
    } else {
      console.warn('⚠ Warning: Could not find insertion point for Unreal connection status handler');
    }

    // Patch to persist the Unreal type database and reuse it when no editor is running.
    //
    // The database is only ever produced by a running Unreal editor, so without one the server has
    // no types and silently provides neither semantic tokens nor diagnostics. The payload arrives
    // as plain JSON, which makes it cheap to keep: chunks are stored verbatim and spliced into an
    // array literal, so saving costs no re-serialisation of the parsed graph.
    //
    // Replaying a cache is not a new risk: AddUnrealTypeToDatabase already drops a same-named type
    // before adding, so a live database received later overrides cached entries exactly as it does
    // when the editor restarts and re-sends. Types deleted since the cache was written do linger,
    // which is why the state is reported separately rather than presented as a live connection.
    const typeCacheSupport = `
var __asTypeCache = {
  path: (() => {
    const arg = process.argv.find((a) => a.startsWith("--type-cache="));
    return arg ? arg.substring("--type-cache=".length) : null;
  })(),
  enabled: !process.argv.includes("--no-type-cache"),
  chunks: [],
  loaded: false,
  capturedAt: null,
  configuredAt: null
};

// Called when the editor finishes sending a database. Live types supersede anything the cache
// supplied, so the cached flag has to be cleared even when saving is switched off.
function __asOnLiveTypesReceived() {
  __asTypeCache.loaded = false;
  __asTypeCache.capturedAt = Date.now();
  __asSaveTypeCache();
}

function __asSaveTypeCache() {
  if (!__asTypeCache.path || !__asTypeCache.enabled || __asTypeCache.chunks.length == 0)
    return;
  try {
    const fs = require("fs");
    const path = require("path");
    fs.mkdirSync(path.dirname(__asTypeCache.path), { recursive: true });
    // Each chunk is already valid JSON, so splice them in as array elements rather than
    // stringifying the parsed graph again.
    const body = '{"version":1,"savedAt":' + Date.now() + ',"port":' + port
      + ',"chunks":[' + __asTypeCache.chunks.join(",") + ']}';
    fs.writeFileSync(__asTypeCache.path + ".tmp", body);
    fs.renameSync(__asTypeCache.path + ".tmp", __asTypeCache.path);
    connection.console.log("[typeCache] Saved " + __asTypeCache.chunks.length
      + " chunks (" + body.length + " bytes) to " + __asTypeCache.path);
  } catch (e) {
    connection.console.log("[typeCache] Failed to save: " + e);
  } finally {
    __asTypeCache.chunks = [];
  }
}

function __asLoadTypeCache() {
  if (!__asTypeCache.path || !__asTypeCache.enabled || __asTypeCache.loaded)
    return false;
  try {
    const fs = require("fs");
    if (!fs.existsSync(__asTypeCache.path))
      return false;
    const cache = JSON.parse(fs.readFileSync(__asTypeCache.path, "utf8"));
    if (!cache || cache.version != 1 || !Array.isArray(cache.chunks) || cache.chunks.length == 0)
      return false;

    for (let chunk of cache.chunks)
      AddTypesFromUnreal(chunk);
    FinishTypesFromUnreal();
    AddPrimitiveTypes(GetScriptSettings().floatIsFloat64);

    __asTypeCache.loaded = true;
    __asTypeCache.capturedAt = cache.savedAt || null;
    connection.console.log("[typeCache] Loaded " + cache.chunks.length
      + " chunks saved at " + new Date(cache.savedAt).toISOString());

    ReResolveAllModules();
    return true;
  } catch (e) {
    connection.console.log("[typeCache] Failed to load: " + e);
    return false;
  }
}

// Fall back to the cache only once it is clear no editor is answering. connect_unreal() retries
// every 5s, so a socket that is still not open by then means nothing is listening.
setInterval(() => {
  if (!__asTypeCache.enabled || __asTypeCache.loaded || HasTypesFromUnreal())
    return;
  if (port < 0) {
    // Not configured yet: the server has not been told where to look, so nothing has failed.
    __asTypeCache.configuredAt = null;
    return;
  }
  if (unreal && !unreal.connecting)
    return; // Socket is open; live types are on their way and always win over the cache.
  if (__asTypeCache.configuredAt == null) {
    __asTypeCache.configuredAt = Date.now();
    return;
  }
  if (Date.now() - __asTypeCache.configuredAt >= 6000)
    __asLoadTypeCache();
}, 1000);
`;

    let typeCacheAdded = false;
    const typeCacheInsertionPoint = /connection\.onRequest\("angelscript\/getAPIDetails"[\s\S]*?return promise;\s*\}\);/;
    content = content.replace(typeCacheInsertionPoint, (match) => {
      if (!typeCacheAdded) {
        typeCacheAdded = true;
        return match + typeCacheSupport;
      }
      return match;
    });

    // Record every database chunk as it arrives, and persist once the editor says it is done.
    const chunkCapture = content.replace(
      /(let dbStr = msg\.readString\(\);\s*let dbObj = JSON\.parse\(dbStr\);)/,
      '$1\n        if (__asTypeCache.enabled) __asTypeCache.chunks.push(dbStr);'
    );
    const captureAdded = chunkCapture !== content;
    content = chunkCapture;

    const saveHook = content.replace(
      /(typedb_exports\.FinishTypesFromUnreal\(\);|\bFinishTypesFromUnreal\(\);)(\s*\n\s*let scriptSettings = GetScriptSettings\(\);)/,
      '$1\n        __asOnLiveTypesReceived();$2'
    );
    const saveAdded = saveHook !== content;
    content = saveHook;

    if (typeCacheAdded && captureAdded && saveAdded) {
      console.log('✓ Patched to cache the Unreal type database for offline use');
    } else {
      console.warn('⚠ Warning: Type database cache not fully applied'
        + ' (support: ' + typeCacheAdded + ', capture: ' + captureAdded + ', save: ' + saveAdded + ')');
    }

    fs.writeFileSync(bundledPath, content);
    console.log('✓ Language server bundled and patched for stdio communication!');
  } catch (error) {
    console.error('✗ Bundling failed:', error);
    process.exit(1);
  }
}

bundle();
