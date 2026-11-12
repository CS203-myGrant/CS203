const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('function visaTrackingMissing(profile){'),
  html.indexOf('async function loadForecastAlerts(){'));
const validationSource=html.slice(html.indexOf('function visaBulletinEntryError(values,consentedFields){'),
  html.indexOf('function selectedConsentFields(){'));
const validation=vm.createContext({});
vm.runInContext(validationSource,validation);

function settings(consents, busy=false, profileOverrides={}){
  const state={profile:{immigrantCategory:'EB-2',chargeabilityArea:'INDIA',
    priorityDate:'2020-01-01',consents,...profileOverrides},visaTracking:{enabled:false},
    visaTrackingError:null,visaTrackingBusy:busy};
  const context=vm.createContext({state,escapeHtml:String});
  vm.runInContext(source,context);
  return context.renderVisaTrackingSettings();
}

test('three Visa Bulletin consents allow opt-in with unrelated consent boxes checked',()=>{
  const page=settings({IMMIGRANT_CATEGORY:'2026-11-10T00:00:00Z',
    CHARGEABILITY_AREA:'2026-11-10T00:00:00Z',
    PRIORITY_DATE:'2026-11-10T00:00:00Z',
    VISA_TYPE:'2026-11-10T00:00:00Z',EMPLOYER:'2026-11-10T00:00:00Z'});
  assert.match(page,/Your profile has the required details/);
  assert.match(page,/onclick="setVisaTracking\(true\)"/);
  assert.doesNotMatch(page,/onclick="setVisaTracking\(true\)" disabled/);
});

test('missing consent gives guidance but does not leave opt-in grey',()=>{
  const page=settings({IMMIGRANT_CATEGORY:'2026-11-10T00:00:00Z',
    CHARGEABILITY_AREA:'2026-11-10T00:00:00Z'});
  assert.match(page,/saved profile still needs: priority date consent/);
  assert.doesNotMatch(page,/onclick="setVisaTracking\(true\)" disabled/);
  assert.match(settings({},true),/onclick="setVisaTracking\(true\)" disabled/);
});

test('missing saved values are named separately from consent',()=>{
  const page=settings({},false,{immigrantCategory:null,chargeabilityArea:null,priorityDate:null});
  assert.match(page,/saved profile still needs: immigrant category, chargeability area, priority date/);
  assert.match(page,/immigrant category consent/);
  assert.match(page,/chargeability area consent/);
  assert.match(page,/priority date consent/);
});

test('checking consent without entering values cannot silently save and untick',()=>{
  const consents=['IMMIGRANT_CATEGORY','CHARGEABILITY_AREA','PRIORITY_DATE','VISA_TYPE'];
  assert.match(validation.visaBulletinEntryError({immigrantCategory:'',
    chargeabilityArea:'',priorityDate:''},consents),
    /Enter immigrant category, chargeability area, priority date above before saving/);
  assert.equal(validation.visaBulletinEntryError({immigrantCategory:'EB-2',
    chargeabilityArea:'INDIA',priorityDate:'2020-01-01'},consents),null);
});
