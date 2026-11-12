const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/mygrant-prototype.html', 'utf8');
const source = html.slice(html.indexOf('async function parseApiResponse('), html.indexOf('async function loadImmigrationDocuments('));
for (const status of [401, 403, 503]) {
  test(`HTTP ${status} ${status === 401 ? 'expires' : 'preserves'} the session`, async () => {
    let loggedOut = false;
    const context = vm.createContext({
      state: { token: 'valid-session' },
      clearLocalSession() { loggedOut = true; }
    });
    vm.runInContext(source, context);
    await assert.rejects(context.parseApiResponse({ status, ok: false, json: async () => ({ message: 'Test error' }) }), /Test error/);
    assert.equal(loggedOut, status === 401);
  });
}

test('missing consent names only the filled profile fields without repeating the generic error', async () => {
  const context = vm.createContext({ state: { token: 'valid-session' } });
  vm.runInContext(source, context);
  const error = 'Consent is required before this field can be used.';
  const response = { status: 400, ok: false, json: async () => ({
    message: 'Request validation failed',
    validationErrors: { visaType: error, academicLevel: error, programEndDate: error }
  }) };
  await assert.rejects(context.parseApiResponse(response), exception => {
    assert.equal(exception.message, 'Tick the consent boxes for: Visa category, Academic level, Program end date.');
    assert.equal(exception.message.includes(error), false);
    return true;
  });
});
