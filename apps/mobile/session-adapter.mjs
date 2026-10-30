// Only bounded session state crosses into the UI. Native code owns cookies and CSRF.
const codes = Object.freeze({
  WRONG_PASSWORD: 'invalid-password', UNCONFIGURED: 'unconfigured', RATE_LIMITED: 'rate-limited',
  NETWORK: 'offline', UNCERTAIN_OUTCOME: 'timeout', UNTRUSTED_TLS: 'tls', SIGN_IN_REQUIRED: 'expired'
});

export function createNativeSessionAdapter(plugin) {
  async function invoke(method, input) {
    try {
      const result = await plugin[method](input);
      if (!result || typeof result.authenticated !== 'boolean' || typeof result.configured !== 'boolean' ||
          (result.authenticated && !result.configured)) throw new Error();
      return {authenticated: result.authenticated, configured: result.configured};
    } catch (error) {
      const code = Object.hasOwn(codes, error?.code) ? codes[error.code] : 'unexpected';
      throw Object.assign(new Error('Session request failed.'), {code});
    }
  }
  return Object.freeze({
    async available() {
      try { return (await plugin.availability())?.available === true; }
      catch { return false; }
    },
    getSession: () => invoke('session'),
    signIn: ({password}) => invoke('login', {password}),
    signOut: () => invoke('logout')
  });
}
