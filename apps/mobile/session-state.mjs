// UI state only. The native adapter owns HTTPS, cookies, CSRF and credential lifetime.
// It returns only {authenticated, configured}; this controller never stores server payloads.
const messages = Object.freeze({
  localOnly: 'Your plants and watering notes are saved on this phone. Sign-in is optional.',
  checking: 'Checking your sign-in. You can keep caring for plants on this phone.',
  signingIn: 'Signing in. Your plants on this phone are still available.',
  signedIn: 'Signed in. Your plants and watering notes are still saved on this phone.',
  signedOut: 'Signed out. Your plants and watering notes are still on this phone.',
  signingOut: 'Signing out. Online actions are paused; your plants remain on this phone.',
  expired: 'Please sign in again. Your plants and watering notes are still on this phone.',
  unconfigured: 'This garden has not been set up for sign-in. You can keep using the garden on this phone.',
  offline: 'Could not reach your garden. Check your connection and try again. Your plants remain on this phone.',
  error: 'The garden returned an unexpected response. You can keep using the garden on this phone.',
  signOutUnconfirmed: 'I could not confirm server sign-out. Online actions are paused. The server session may still be active. Sign in again to start a new session; your plants remain on this phone.'
});

const failures = Object.freeze({
  'invalid-password': ['signedOut', 'That password did not work. Please try again.'],
  unconfigured: ['unconfigured', messages.unconfigured],
  'rate-limited': ['signedOut', 'Too many sign-in attempts. Wait a minute, then try again.'],
  offline: ['offline', messages.offline],
  timeout: ['offline', messages.offline],
  tls: ['error', 'A secure connection could not be verified. Your plants remain on this phone.'],
  expired: ['expired', messages.expired]
});

function validSession(value) {
  return value !== null && typeof value === 'object' &&
    typeof value.authenticated === 'boolean' && typeof value.configured === 'boolean' &&
    (!value.authenticated || value.configured);
}

export function createSessionState(transport) {
  for (const method of ['getSession', 'signIn', 'signOut']) {
    if (typeof transport?.[method] !== 'function') throw new TypeError('A session transport is required.');
  }
  let generation = 0;
  let logoutUncertain = false;
  let tail = Promise.resolve();
  let state;
  function set(status, message = messages[status]) {
    if (status === 'signOutUnconfirmed') logoutUncertain = true;
    if (status === 'signedIn') logoutUncertain = false;
    state = Object.freeze({status, message, authenticated: status === 'signedIn',
      busy: ['checking', 'signingIn', 'signingOut'].includes(status)});
    return state;
  }
  set('localOnly');

  function run(kind, request) {
    const previousAuthenticated = state.authenticated;
    const ownGeneration = ++generation;
    set({refresh: 'checking', login: 'signingIn', logout: 'signingOut'}[kind]);
    // Serialize native operations: a logout follows any in-flight login/cookie rotation.
    // No operation is retried here, including a failed or uncertain sign-out.
    tail = tail.then(async () => {
      if (ownGeneration !== generation && kind !== 'logout') return state;
      if (kind === 'refresh' && logoutUncertain) return set('signOutUnconfirmed');
      try {
        const result = await request();
        if (kind === 'logout') logoutUncertain = !validSession(result) || result.authenticated;
        if (ownGeneration !== generation) return state;
        if (!validSession(result)) return set(kind === 'logout' ? 'signOutUnconfirmed' : 'error');
        if (kind === 'logout') {
          if (result.authenticated) return set('signOutUnconfirmed');
          logoutUncertain = false;
          return set('signedOut');
        }
        if (!result.configured) return set('unconfigured');
        if (result.authenticated) return set('signedIn');
        if (kind === 'refresh' && previousAuthenticated) return set('expired');
        return set('signedOut', kind === 'login' ? 'Sign-in was not confirmed. Please try again.' : messages.signedOut);
      } catch (error) {
        if (kind === 'logout') logoutUncertain = true;
        if (ownGeneration !== generation) return state;
        if (kind === 'logout') return set('signOutUnconfirmed');
        const [status, message] = Object.hasOwn(failures, error?.code) ? failures[error.code] : ['error', messages.error];
        return set(status, message);
      }
    });
    return tail;
  }

  return Object.freeze({
    getState: () => state,
    refresh() {
      if (state.busy) return tail;
      // Native logout forgets its cookie even on failure. An anonymous check cannot
      // establish that the previous server session was invalidated.
      if (logoutUncertain) return Promise.resolve(set('signOutUnconfirmed'));
      return run('refresh', () => transport.getSession());
    },
    signIn(password) {
      if (state.busy) return tail;
      if (typeof password !== 'string' || password.length === 0) {
        return Promise.resolve(logoutUncertain ? set('signOutUnconfirmed') :
          set('signedOut', 'Enter your garden password.'));
      }
      return run('login', () => transport.signIn({password}));
    },
    signOut() {
      if (state.status === 'signingOut') return tail;
      return run('logout', () => transport.signOut());
    },
    expire() {
      generation++;
      return set('expired');
    }
  });
}
