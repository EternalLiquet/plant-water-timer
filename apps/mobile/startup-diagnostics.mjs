// Included only in the separate startupTest asset set. Never render an exception payload.
export function diagnosticCode(error) {
  const message = typeof error?.message === 'string' ? error.message : typeof error === 'string' ? error : '';
  for (const [pattern, code] of [
    [/duplicate column name:\s*photo_id/i, 'DUPLICATE_PHOTO_COLUMN'],
    [/no such table/i, 'MISSING_TABLE'],
    [/not implemented|plugin.*unavailable/i, 'PLUGIN_UNAVAILABLE'],
    [/database.*locked|database.*busy/i, 'DATABASE_BUSY'],
    [/read.?only database/i, 'DATABASE_READ_ONLY'],
    [/disk.*full|no space left/i, 'STORAGE_FULL'],
    [/permission denied/i, 'STORAGE_ACCESS_DENIED'],
    [/newer app version/i, 'NEWER_SCHEMA'],
    [/UnsatisfiedLinkError|couldn.t find.*\.so|dlopen failed/i, 'NATIVE_LIBRARY_UNAVAILABLE'],
    [/keystore|masterkey/i, 'KEYSTORE_UNAVAILABLE']
  ]) if (pattern.test(message)) return code;
  return error?.name === 'TypeError' ? 'JS_TYPE_ERROR' : 'STARTUP_ERROR';
}

const methods = {
  connection: ['checkConnectionsConsistency', 'isConnection', 'retrieveConnection', 'createConnection'],
  database: ['isDBOpen', 'open', 'execute', 'query', 'beginTransaction', 'commitTransaction', 'rollbackTransaction']
};
export function watchNative(native, setStage, kind, onFailure = () => {}) {
  return new Proxy(native, {get(target, key) {
    const value = Reflect.get(target, key);
    if (typeof value !== 'function') return value;
    return async (...args) => {
      if (methods[kind].includes(key)) setStage(`${kind}-${key}`);
      try {
        const result = await value.apply(target, args);
        return kind === 'connection' && ['createConnection', 'retrieveConnection'].includes(key)
          ? watchNative(result, setStage, 'database', onFailure) : result;
      } catch (error) {
        if (methods[kind].includes(key)) onFailure(`${kind}-${key}`);
        throw error;
      }
    };
  }});
}

export function startupDiagnostic(build) {
  let stage = 'ui', failedStage = null, finished = false;
  function display(status) {
    let panel = document.getElementById('startup-diagnostic');
    if (!panel) {
      panel = document.createElement('section');
      panel.id = 'startup-diagnostic'; panel.setAttribute('role', 'status');
      panel.style.cssText = 'padding:16px;border:2px solid #e7b56d;margin:12px;background:#20251f;color:#fff';
      document.body.prepend(panel);
    }
    panel.textContent = `Little Garden Test — separate test garden. PR28 baseline 9e5c698; build ${build}. ${status}`;
  }
  const setStage = next => { if (!finished) { stage = next; display(`Stage: ${stage}`); } };
  return {
    stage: setStage,
    connection: native => watchNative(native, setStage, 'connection', s => { failedStage ??= s; }),
    ready() { finished = true; display('Startup completed. No original garden was opened.'); },
    fail(error) { finished = true; display(`Stage: ${failedStage || stage}. Error: ${diagnosticCode(error)}. Original app is unchanged.`); }
  };
}
