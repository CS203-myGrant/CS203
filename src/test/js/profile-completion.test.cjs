const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const html = fs.readFileSync('app/mygrant-prototype.html', 'utf8');
const source = html.slice(
  html.indexOf('function renderProfileCompletion()'),
  html.indexOf('async function submitProfileCompletion(')
);

test('completion form preserves registration values', () => {
  const context = vm.createContext({
    state: { user: {
      visaType: 'H-1B', academicLevel: "Master's", programEndDate: '2026-11-27',
      currentLocation: 'US', upcomingTravelDate: '2026-12-03'
    } },
    escapeAttribute: value => value,
    countryOptions: () => '',
    onboardingConsentControls: () => ''
  });
  vm.runInContext(source, context);

  const form = context.renderProfileCompletion();
  assert.match(form, /<option value="H-1B" selected>H-1B<\/option>/);
  assert.match(form, /<option selected>Master's<\/option>/);
  assert.match(form, /id="workos-end-date" type="date" value="2026-11-27"/);
  assert.match(form, /<option value="US" selected>US<\/option>/);
  assert.match(form, /id="workos-travel-date" type="date" value="2026-12-03"/);
});
