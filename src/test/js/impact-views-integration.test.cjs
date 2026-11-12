const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const functions=html.slice(html.indexOf('async function loadImpacts('),html.indexOf("window.addEventListener('beforeunload'"));
const dashboard=html.slice(html.indexOf('function renderLiveDashboard(){'),html.indexOf('async function loadForecastAlerts(){'));
const escaping=html.slice(html.indexOf('function escapeHtml(value){'),html.indexOf('function formatDate(value){'));
const parsing=html.slice(html.indexOf('async function parseApiResponse('),html.indexOf('async function loadImmigrationDocuments('));
const assessment=(overrides={})=>({id:7,policyId:52,policyTitle:'Your policy',urgency:'HIGH',summary:'Your saved summary',
  confidence:85,createdAt:'2026-11-10T12:00:00Z',status:'COMPLETED',requiresReview:false,
  reviewStatus:'NOT_REQUIRED',reviewedByHuman:false,reasons:['Your dates match'],recommendedActions:['Read the rule'],
  sourceUrl:'https://example.org/rule',...overrides});
const response=(body,status=200)=>({ok:status>=200 && status<300,status,json:async()=>body});
const result=content=>response({content,totalPages:1,totalElements:content.length});
function browser(fetch){
  const calls=[];
  let context;
  const state={view:'dashboard',user:{email:'student@example.org',visaType:'F-1',profileComplete:true},
    notifications:[],policyRecords:[],impacts:null,impactsPage:0,impactsTotalPages:0};
  context=vm.createContext({state,calls,window:{},permissionPromptedThisSession:false,
    apiFetch:async(path,options)=>{calls.push([path,options]);return fetch(path,options);},
    goto(view){state.view=view;},render(){context.screen=state.view==='dashboard'
      ?context.renderLiveDashboard():context.renderImpactDetail();},
    clearLocalSession(){},renderProcessingTimeline:()=>'',renderActionChecklist:()=>'',
    formatTimestamp:value=>value,formatDate:value=>value});
  vm.runInContext(escaping+parsing+functions+dashboard,context);
  context.renderActionChecklist=()=>'';
  context.render();
  return context;
}
function click(view,label){
  const button=[...view.screen.matchAll(/<button\b([^>]*)>([^<]*)<\/button>/g)].find(match=>match[2]===label);
  assert.ok(button,`Missing ${label} button`);
  const handler=button[1].match(/onclick="([^"]+)"/);
  assert.ok(handler,`Missing ${label} handler`);
  return vm.runInContext(handler[1],view);
}
function readOnly(calls){
  for(const [path,options] of calls){
    assert.match(path,/^\/api\/v1\/impact-assessments(?:\?|\/)/);
    assert.equal(options,undefined,'UI must use the default GET, without creating or mutating records');
  }
}

test('dashboard card to saved detail shows both loading states and never generates AI output',async()=>{
  const pending=[];
  const view=browser(()=>new Promise(resolve=>pending.push(resolve)));
  assert.match(view.screen,/Loading your assessments/);
  const loading=view.loadImpacts();
  assert.match(view.screen,/Loading your assessments/);
  pending.shift()(result([assessment()]));
  await loading;
  assert.match(view.screen,/Your saved summary/);
  assert.match(view.screen,/href="https:\/\/example.org\/rule"/);
  const opening=click(view,'View assessment');
  assert.match(view.screen,/Loading assessment/);
  assert.doesNotMatch(view.screen,/Your saved summary/);
  pending.shift()(response(assessment()));
  await opening;
  for(const text of ['Your policy','Your saved summary','Your dates match','Read the rule']) assert.ok(view.screen.includes(text));
  assert.deepEqual(view.calls.map(call=>call[0]),[
    '/api/v1/impact-assessments?page=0&size=20','/api/v1/impact-assessments/7']);
  readOnly(view.calls);
});

test('empty and filtered-empty states explain availability without offering generation',async()=>{
  const view=browser(async()=>result([]));
  await view.loadImpacts();
  assert.match(view.screen,/No assessments are available yet/);
  await view.showImpactUrgency('HIGH');
  assert.match(view.screen,/No assessments match this urgency filter/);
  assert.doesNotMatch(view.screen,/See how this impacts me|create one/);
  readOnly(view.calls);
});

test('late applicant explicitly requests a matching official policy while feed reads stay safe',async()=>{
  const view=browser(async(path,options)=>options?.method==='POST'
    ?response({message:'Assessment request processed. It will appear after any required review.'})
    :result([]));
  view.state.policyRecords=[
    {id:52,title:'F-1 policy',visaType:'F-1',sourceType:'PRIMARY'},
    {id:53,title:'J-1 policy',visaType:'J-1',sourceType:'PRIMARY'},
    {id:54,title:'Secondary article',visaType:'F-1',sourceType:'SECONDARY'}];
  await view.loadImpacts();
  view.render();
  assert.match(view.screen,/Request an assessment for an imported policy/);
  assert.match(view.screen,/F-1 policy/);
  assert.doesNotMatch(view.screen,/J-1 policy|Secondary article/);
  let openedPolicy=null;
  view.openPolicyDetail=async id=>{openedPolicy=id;};
  await click(view,'See how this impacts me');
  assert.equal(view.calls[1][0],'/api/v1/impact-assessments/policy/52/request');
  assert.equal(view.calls[1][1].method,'POST');
  assert.equal(openedPolicy,52);
  assert.match(view.screen,/after any required review/);
  assert.doesNotMatch(view.screen,/Your saved summary/);
});

test('feed retry repeats the failed page and urgency filter rather than an earlier page',async()=>{
  let failed=true;
  const view=browser(async()=>failed?response({message:'Feed is temporarily unavailable'},503):result([assessment()]));
  view.state.impactsUrgency='HIGH';
  view.state.impactsPage=0;
  await view.loadImpacts(2);
  assert.match(view.screen,/Feed is temporarily unavailable/);
  assert.doesNotMatch(view.screen,/No assessments are available|Your saved summary/);
  failed=false;
  await click(view,'Try again');
  assert.equal(view.calls[0][0],'/api/v1/impact-assessments?page=2&size=20&urgency=HIGH');
  assert.deepEqual(view.calls[1],view.calls[0]);
  assert.match(view.screen,/Your saved summary/);
  readOnly(view.calls);
});

test('detail retry repeats only its failed saved-assessment read',async()=>{
  let failed=true;
  const view=browser(async()=>failed?response({message:'Detail is temporarily unavailable'},503):response(assessment()));
  await view.openSavedImpact(7);
  assert.match(view.screen,/Detail is temporarily unavailable/);
  failed=false;
  await click(view,'Try again');
  assert.deepEqual(view.calls,[['/api/v1/impact-assessments/7',undefined],['/api/v1/impact-assessments/7',undefined]]);
  assert.match(view.screen,/Your saved summary/);
  readOnly(view.calls);
});

for(const review of [
  {reviewStatus:'APPROVED',requiresReview:true,reviewedByHuman:true,marker:true},
  {reviewStatus:'NOT_REQUIRED',requiresReview:false,reviewedByHuman:false,marker:false},
  // Canonical review status wins over an inconsistent cosmetic marker.
  {reviewStatus:'NOT_REQUIRED',requiresReview:false,reviewedByHuman:true,marker:false}
]){
  test(`review marker follows ${review.reviewStatus}, including stale marker=${review.reviewedByHuman}`,async()=>{
    const record=assessment(review);
    const view=browser(async path=>path.includes('?')?result([record]):response(record));
    await view.loadImpacts();
    assert.equal(view.screen.includes('Reviewed by a human'),review.marker);
    await click(view,'View assessment');
    assert.equal(view.screen.includes('Reviewed by a human'),review.marker);
    readOnly(view.calls);
  });
}

for(const hidden of [
  {reviewStatus:'PENDING_REVIEW',requiresReview:true},
  {reviewStatus:'REJECTED',requiresReview:true},
  {status:'UNAVAILABLE',reviewStatus:'APPROVED',requiresReview:true},
  {reviewStatus:'NOT_REQUIRED',requiresReview:true},
  {reviewStatus:undefined,requiresReview:undefined}
]){
  test(`hidden content never renders: ${JSON.stringify(hidden)}`,async()=>{
    const record=assessment({...hidden,summary:'PRIVATE SUMMARY',reasons:['PRIVATE REASON'],recommendedActions:['PRIVATE ACTION']});
    const view=browser(async path=>path.includes('?')?result([assessment(),record]):response(record));
    await view.loadImpacts();
    assert.match(view.screen,/Your saved summary/);
    assert.doesNotMatch(view.screen,/PRIVATE SUMMARY|PRIVATE REASON|PRIVATE ACTION/);
    assert.equal(view.state.impacts.length,1);
    await view.openSavedImpact(record.id);
    assert.match(view.screen,/This assessment is unavailable/);
    assert.doesNotMatch(view.screen,/PRIVATE SUMMARY|PRIVATE REASON|PRIVATE ACTION/);
    assert.equal(view.state.impactDetail,null);
    readOnly(view.calls);
  });
}

test('inaccessible detail and its retry never disclose previously loaded content',async()=>{
  const view=browser(async()=>response({message:'Not found'},404));
  view.state.impactDetail=assessment({summary:'Old private summary'});
  await view.openSavedImpact(99);
  assert.match(view.screen,/unavailable or you do not have access/);
  assert.doesNotMatch(view.screen,/Old private summary/);
  await click(view,'Try again');
  assert.doesNotMatch(view.screen,/Old private summary/);
  readOnly(view.calls);
});
