const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync('app/mygrant-prototype.html', 'utf8');
const source = html.slice(html.indexOf('function receiveNotification('), html.indexOf('async function requestBrowserNotifications('));

test('a live notification immediately refreshes the alerts dashboard', () => {
  let renders = 0;
  let badgeUpdates = 0;
  const context = vm.createContext({
    state: { view: 'dashboard', unreadCount: 0, notifications: [] },
    window: {},
    updateUnreadBadge() { badgeUpdates++; },
    render() { renders++; }
  });
  vm.runInContext(source, context);
  context.receiveNotification({ id: 42, message: 'New policy', actionRequired: false });
  assert.equal(context.state.unreadCount, 1);
  assert.equal(context.state.notifications[0].id, 42);
  assert.equal(badgeUpdates, 1);
  assert.equal(renders, 1);
});
