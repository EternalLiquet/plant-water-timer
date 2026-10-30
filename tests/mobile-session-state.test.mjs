import test from 'node:test';
import assert from 'node:assert/strict';
import {createSessionState} from '../apps/mobile/session-state.mjs';

const signedIn = {authenticated: true, configured: true};
const signedOut = {authenticated: false, configured: true};
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};
const transport = (overrides = {}) => ({
  async getSession() { return signedOut; },
  async signIn() { return signedIn; },
  async signOut() { return signedOut; },
  ...overrides
});

test('creating session state does not contact a server or require sign-in for local care', () => {
  let calls = 0;
  const state = createSessionState(transport({getSession() { calls++; }}));
  assert.equal(state.getState().status, 'localOnly');
  assert.equal(state.getState().authenticated, false);
  assert.equal(state.getState().busy, false);
  assert.equal(calls, 0);
  assert.ok(Object.isFrozen(state.getState()));
});

test('explicit sign-in requires a confirmed authenticated session and exposes no secrets', async () => {
  const calls = [];
  const state = createSessionState(transport({async signIn(input) {
    calls.push(input);
    return {...signedIn, csrfToken: 'synthetic-token', cookie: 'synthetic-cookie'};
  }}));
  const pending = state.signIn('synthetic-password');
  assert.equal(state.getState().status, 'signingIn');
  await pending;
  assert.deepEqual(calls, [{password: 'synthetic-password'}]);
  assert.equal(state.getState().status, 'signedIn');
  assert.equal(state.getState().authenticated, true);
  assert.doesNotMatch(JSON.stringify(state.getState()), /synthetic/);
});

test('empty password does not reach the transport', async () => {
  let calls = 0;
  const state = createSessionState(transport({async signIn() { calls++; return signedIn; }}));
  await state.signIn('');
  assert.equal(calls, 0);
  assert.equal(state.getState().status, 'signedOut');
  assert.match(state.getState().message, /Enter your garden password/);
});

test('malformed session responses never authenticate the UI', async () => {
  for (const value of [null, {}, {authenticated: 'true', configured: true},
    {authenticated: true}, {authenticated: true, configured: false}]) {
    const state = createSessionState(transport({async signIn() { return value; }}));
    await state.signIn('synthetic-password');
    assert.equal(state.getState().status, 'error');
    assert.equal(state.getState().authenticated, false);
  }
});

test('login failures have distinct bounded messages without forwarding error payloads', async () => {
  const messages = new Set();
  for (const code of ['invalid-password', 'unconfigured', 'rate-limited', 'offline', 'tls', 'unexpected']) {
    const state = createSessionState(transport({async signIn() {
      throw Object.assign(new Error('SENSITIVE synthetic-password cookie token'), {code});
    }}));
    await state.signIn('synthetic-password');
    const snapshot = state.getState();
    assert.equal(snapshot.authenticated, false);
    assert.equal(snapshot.busy, false);
    assert.doesNotMatch(JSON.stringify(snapshot), /SENSITIVE|synthetic-password|cookie|token/);
    messages.add(snapshot.message);
  }
  assert.equal(messages.size, 6);
});

test('unauthenticated or unconfigured responses cannot be mistaken for login success', async () => {
  for (const [response, expected] of [[signedOut, 'signedOut'],
    [{authenticated: false, configured: false}, 'unconfigured']]) {
    const state = createSessionState(transport({async signIn() { return response; }}));
    await state.signIn('synthetic-password');
    assert.equal(state.getState().status, expected);
    assert.equal(state.getState().authenticated, false);
  }
});

test('duplicate login submissions and refresh while login is pending do not replay requests', async () => {
  const response = deferred();
  let logins = 0, refreshes = 0;
  const state = createSessionState(transport({
    signIn() { logins++; return response.promise; },
    async getSession() { refreshes++; return signedOut; }
  }));
  const one = state.signIn('one');
  const two = state.signIn('two');
  const refresh = state.refresh();
  await Promise.resolve();
  assert.equal(logins, 1);
  assert.equal(refreshes, 0);
  response.resolve(signedIn);
  await Promise.all([one, two, refresh]);
  assert.equal(state.getState().status, 'signedIn');
});

test('sign-out waits for an outstanding login and suppresses its late authenticated result', async () => {
  const login = deferred(), logout = deferred();
  const calls = [];
  const state = createSessionState(transport({
    signIn() { calls.push('login'); return login.promise; },
    signOut() { calls.push('logout'); return logout.promise; }
  }));
  const first = state.signIn('synthetic-password');
  await Promise.resolve();
  const second = state.signOut();
  assert.equal(state.getState().status, 'signingOut');
  assert.deepEqual(calls, ['login']);
  login.resolve(signedIn);
  await first;
  await Promise.resolve();
  assert.deepEqual(calls, ['login', 'logout']);
  assert.equal(state.getState().status, 'signingOut');
  assert.equal(state.getState().authenticated, false);
  logout.resolve(signedOut);
  await second;
  assert.equal(state.getState().status, 'signedOut');
});

test('expiry invalidates an older refresh result without changing any local garden data', async () => {
  const response = deferred();
  const state = createSessionState(transport({getSession() { return response.promise; }}));
  const pending = state.refresh();
  await Promise.resolve();
  state.expire();
  response.resolve(signedIn);
  await pending;
  assert.equal(state.getState().status, 'expired');
  assert.equal(state.getState().authenticated, false);
  assert.match(state.getState().message, /on this phone/);
});

test('failed sign-out remains unconfirmed and is never automatically retried', async () => {
  let calls = 0;
  const state = createSessionState(transport({async signOut() {
    calls++;
    throw Object.assign(new Error('private server details'), {code: 'offline'});
  }}));
  await state.signIn('synthetic-password');
  await state.signOut();
  assert.equal(state.getState().status, 'signOutUnconfirmed');
  assert.equal(state.getState().authenticated, false);
  assert.match(state.getState().message, /could not confirm/i);
  assert.doesNotMatch(state.getState().message, /private server/);
  assert.equal(calls, 1);
});
