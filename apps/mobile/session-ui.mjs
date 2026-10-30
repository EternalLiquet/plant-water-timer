import {createSessionState} from './session-state.mjs';
import {createNativeSessionAdapter} from './session-adapter.mjs';

// This panel owns no garden/store/photo references. Sign-in never gates local care.
export async function installSessionUi(plugin, document) {
  const byId = id => document.getElementById(id);
  const panel = byId('sign-in');
  const form = byId('login-form');
  const password = byId('password');
  const submit = byId('login-submit');
  const logout = byId('sign-out');
  const message = byId('login-message');
  const details = document.createElement('details');
  details.id = 'session-options';
  const summary = document.createElement('summary');
  summary.textContent = 'Garden sign-in (optional)';
  details.append(summary, panel);
  byId('garden').after(details);
  panel.hidden = false;
  panel.querySelector('h1').textContent = 'Garden sign-in';
  panel.querySelector('p').textContent = 'Sign-in does not move or sync the plants, photos or watering notes saved on this phone.';
  submit.textContent = 'Sign in';
  form.hidden = true;
  password.disabled = true;
  logout.hidden = true;
  const check = document.createElement('button');
  check.id = 'session-check'; check.type = 'button'; check.textContent = 'Check sign-in';
  check.hidden = true;
  panel.append(check, logout);
  message.textContent = 'Sign-in is not available in this build. You can keep caring for plants on this phone.';

  const adapter = createNativeSessionAdapter(plugin);
  if (!await adapter.available()) return;
  const state = createSessionState(adapter);
  function render() {
    const value = state.getState();
    message.textContent = value.message;
    form.hidden = value.authenticated;
    password.disabled = value.busy;
    submit.disabled = value.busy;
    check.hidden = false;
    check.disabled = value.busy || value.status === 'signOutUnconfirmed';
    logout.hidden = value.status === 'localOnly' || value.status === 'signedOut' || value.status === 'unconfigured' || value.status === 'signOutUnconfirmed';
    logout.disabled = value.status === 'signingOut';
  }
  async function perform(operation) {
    const pending = operation();
    render();
    await pending;
    render();
  }
  form.addEventListener('submit', event => {
    event.preventDefault();
    const value = password.value;
    password.value = '';
    void perform(() => state.signIn(value));
  });
  logout.addEventListener('click', () => {
    password.value = '';
    void perform(() => state.signOut());
  });
  check.addEventListener('click', () => { void perform(() => state.refresh()); });
  // Capability lookup above is local only; session lookup requires an explicit user action.
  render();
}
