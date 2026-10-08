// Lightweight DOM boundary for deterministic handler races. Production app.js is executed
// unchanged apart from its module import/start call; HTTP and DOM are the test boundaries.
import vm from 'node:vm';
import fs from 'node:fs';
import * as dates from '../../apps/api/src/main/resources/static/garden-utils.mjs';
const source = fs.readFileSync('apps/api/src/main/resources/static/app.js', 'utf8');
const html = fs.readFileSync('apps/api/src/main/resources/static/index.html', 'utf8');
export function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}
export function setup({cards = false, initialPlants = []} = {}) {
  const elements = new Map();
  let document;
  class Element {
    constructor(id = '') {
      this.id = id; this.value = ''; this.hidden = false; this.open = false;
      this.disabled = false; this.files = []; this.children = []; this.listeners = {};
      this.dataset = {}; this.attributes = new Map(); this.selectors = {};
      const classes = new Set();
      this.classList = {toggle: (name, on) => on ? classes.add(name) : classes.delete(name), add: name => classes.add(name), contains: name => classes.has(name)};
    }
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
    async fire(type, event = {}) {
      if (type === 'click' && this.disabled) return;
      for (const fn of this.listeners[type] || []) await fn({preventDefault() {}, ...event});
    }
    setAttribute(name, value) { this.attributes.set(name, value); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    removeAttribute(name) { this.attributes.delete(name); }
    replaceChildren(...children) { this.children.forEach(x => x.parent = null); this.children = []; this.append(...children); }
    append(...children) { for (const child of children) { this.children.push(child); child.parent = this; } }
    querySelector(selector) { return this.selectors[selector] || null; }
    focus() { document.activeElement = this; }
    select() { this.focus(); }
    showModal() { this.open = true; }
    close() { this.open = false; this.closing = this.fire('close'); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(x => x !== this); }
    reset() {
      const ids = this.id === 'past-form' ? ['past-date'] : this.id === 'plant-form' ? ['plant-name', 'species', 'last-watered', 'photo'] : [];
      for (const id of ids) elements.get(id).value = '';
      if (this.id === 'plant-form') elements.get('interval').value = '7';
    }
    requestSubmit() { this.submitting = this.fire('submit'); }
  }
  for (const match of html.matchAll(/\bid="([^"]+)"/g)) elements.set(match[1], new Element(match[1]));
  function find(node, id) { if (node.id === id) return node; for (const child of node.children) { const found = find(child, id); if (found) return found; } return null; }
  document = {
    getElementById(id) { return elements.get(id) || find(elements.get('plants'), id); },
    addEventListener() {}, querySelectorAll: () => [...elements.values()].filter(x => x.id.endsWith('-dialog')),
    createElement: () => new Element(), activeElement: null
  };
  elements.get('plant-template').content = {firstElementChild: {cloneNode() {
    const node = new Element();
    for (const selector of ['.plant-name', '.species', 'img', '.placeholder', '.due', '.last', '.water', '.undo', '.card-message', '.history', '.edit']) {
      node.selectors[selector] = new Element(); node.append(node.selectors[selector]);
    }
    return node;
  }}};
  let nextId = 0;
  const context = vm.createContext({document, window: {addEventListener() {}}, navigator: {}, setInterval() {},
    crypto: {randomUUID: () => `request-${++nextId}`}, Intl, Date,
    URL: {createObjectURL: () => 'preview', revokeObjectURL() {}}, FormData: class {append() {}},
    ...dates, today: () => '2026-10-08', todayInZone: () => '2026-10-08'});
  vm.runInContext(source.replace(/^import[^\n]*\n/, '').replace('\nstart();', '\n'), context);
  const run = code => vm.runInContext(code, context);
  if (!cards) run('render = () => {};');
  context.fixturePlants = initialPlants;
  run('plants = fixturePlants;');
  return {run, el: id => document.getElementById(id), context,
    api(fn) { context.mockApi = fn; run('api = mockApi;'); },
    card(id) { return elements.get('plants').children.find(node => node.dataset.plantId === id); }};
}
export const plant = (id, overrides = {}) => ({id, name: id === 'a' ? 'Fern' : 'Basil', species: '', zone: 'UTC', photoId: null, lastWatered: null, nextCheck: null, intervalDays: 7, lastEventId: null, ...overrides});
