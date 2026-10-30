import test from 'node:test';
import assert from 'node:assert/strict';
import {createNativeSessionAdapter} from '../apps/mobile/session-adapter.mjs';
import {createSessionState} from '../apps/mobile/session-state.mjs';

const session = {authenticated: true, configured: true};
test('native bridge sessions expose only booleans and map login/logout into controller state', async () => {
  const calls = [];
  const adapter = createNativeSessionAdapter({
    async availability() { return {available: true}; },
    async session() { calls.push('session'); return {...session, csrfToken: 'secret'}; },
    async login(input) { calls.push(input); return {...session, cookie: 'secret'}; },
    async logout() { calls.push('logout'); return {authenticated: false, configured: true}; }
  });
  assert.equal(await adapter.available(), true);
  assert.deepEqual(await adapter.getSession(), session);
  const controller = createSessionState(adapter);
  await controller.signIn('synthetic-password');
  assert.equal(controller.getState().authenticated, true);
  await controller.signOut();
  assert.equal(controller.getState().status, 'signedOut');
  assert.deepEqual(calls, ['session', {password: 'synthetic-password'}, 'logout']);
});

test('native enum failures produce bounded controller messages without forwarding payloads', async () => {
  for (const [code, expected] of [
    ['WRONG_PASSWORD', 'signedOut'], ['UNCONFIGURED', 'unconfigured'],
    ['RATE_LIMITED', 'signedOut'], ['NETWORK', 'offline'], ['UNCERTAIN_OUTCOME', 'offline'],
    ['UNTRUSTED_TLS', 'error'], ['SIGN_IN_REQUIRED', 'expired'],
    ['UNSUPPORTED_COOKIE_CONTEXT', 'error'], ['MALFORMED_RESPONSE', 'error'], ['other', 'error']
  ]) {
    const state = createSessionState(createNativeSessionAdapter({
      async login() { throw {code, message: 'private-cookie-password', body: 'private-body'}; }
    }));
    await state.signIn('synthetic');
    assert.equal(state.getState().status, expected, code);
    assert.doesNotMatch(JSON.stringify(state.getState()), /private|synthetic/);
  }
});

test('missing native capability and malformed sessions fail closed', async () => {
  for (const result of [null, {}, {available: 'true'}, {available: false}]) {
    assert.equal(await createNativeSessionAdapter({async availability() { return result; }}).available(), false);
  }
  assert.equal(await createNativeSessionAdapter({}).available(), false);
  for (const result of [null, {}, {authenticated: true, configured: false}, {authenticated: 'true', configured: true}]) {
    const adapter = createNativeSessionAdapter({async session() { return result; }});
    await assert.rejects(adapter.getSession(), {code: 'unexpected'});
  }
});
