const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/mygrant-prototype.html', 'utf8');
const source = html.slice(html.indexOf('async function parseApiResponse('), html.indexOf('async function loadImmigrationDocuments('));
for (const status of [401, 403, 503]) {
  test(`HTTP ${status} ${status === 401 ? 'expires' : 'preserves'} the session`, async () => {
    let loggedOut = false;
    const context = vm.createContext({ state: { token: 'valid-session' }, logout() { loggedOut = true; } });
    vm.runInContext(source, context);
    await assert.rejects(context.parseApiResponse({ status, ok: false, json: async () => ({ message: 'Test error' }) }), /Test error/);
    assert.equal(loggedOut, status === 401);
  });
}
