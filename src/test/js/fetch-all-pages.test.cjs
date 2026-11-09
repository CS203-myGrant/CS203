const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/mygrant-prototype.html', 'utf8');
const source = html.slice(html.indexOf('async function fetchAllPages('), html.indexOf('async function refreshCurrentSession('));

function load(pages) {
  const requested = [];
  const context = vm.createContext({
    async apiFetch(path) { requested.push(path); return pages[requested.length - 1]; },
    async parseApiResponse(page) { return page; }
  });
  vm.runInContext(source, context);
  return { fetchAllPages: context.fetchAllPages, requested };
}

test('collects every page of a list endpoint in order', async () => {
  const { fetchAllPages, requested } = load([
    { content: [1, 2], last: false },
    { content: [3], last: true }
  ]);
  assert.deepEqual([...await fetchAllPages('/api/v1/policies')], [1, 2, 3]);
  assert.deepEqual(requested, ['/api/v1/policies?page=0&size=100', '/api/v1/policies?page=1&size=100']);
});

test('keeps a query string the path already has', async () => {
  const { fetchAllPages, requested } = load([{ content: [], last: true }]);
  assert.deepEqual([...await fetchAllPages('/api/v1/policies?sort=id')], []);
  assert.deepEqual(requested, ['/api/v1/policies?sort=id&page=0&size=100']);
});
