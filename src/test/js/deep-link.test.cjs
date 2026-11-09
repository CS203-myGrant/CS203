const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync('app/mygrant-prototype.html', 'utf8');
const source = html.slice(html.indexOf('const DEEP_LINK_KEY'), html.indexOf('function consumeWorkosRedirect('));

function browser(hash) {
  const storage = new Map();
  const context = vm.createContext({
    location: { hash, pathname: '/mygrant-prototype.html', search: '' },
    history: { replaceState(state, title, url) { context.replacedWith = url; context.location.hash = ''; } },
    sessionStorage: {
      getItem: key => (storage.has(key) ? storage.get(key) : null),
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: key => storage.delete(key)
    },
    URLSearchParams
  });
  vm.runInContext(source, context);
  return context;
}

test('an alert link is kept across sign-in and the hash is cleared', () => {
  const page = browser('#notification=42');
  assert.equal(page.captureDeepLink(), true);
  assert.equal(page.replacedWith, '/mygrant-prototype.html');
  assert.deepEqual({ ...page.takeDeepLink() }, { notification: 42 });
  assert.equal(page.takeDeepLink(), null, 'a link opens only once');
});

test('the email settings link opens the profile', () => {
  const page = browser('#view=profile');
  page.captureDeepLink();
  assert.deepEqual({ ...page.takeDeepLink() }, { view: 'profile' });
});

test('WorkOS tokens and unknown hashes are left alone', () => {
  for (const hash of ['#workosToken=abc&workosRefreshToken=def', '#notification=abc', '#notification=0', '#view=admin', '']) {
    const page = browser(hash);
    assert.equal(page.captureDeepLink(), false, hash);
    assert.equal(page.replacedWith, undefined, hash);
    assert.equal(page.takeDeepLink(), null, hash);
  }
});
