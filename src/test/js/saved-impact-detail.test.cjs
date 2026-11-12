const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const detailSource=html.slice(html.indexOf('async function loadImpacts('),
  html.indexOf("window.addEventListener('beforeunload'"));
const escaping=html.slice(html.indexOf('function escapeHtml(value){'),html.indexOf('function formatDate(value){'));
const parsing=html.slice(html.indexOf('async function parseApiResponse('),html.indexOf('async function loadImmigrationDocuments('));
const saved=(overrides={})=>({id:17,policyId:91,policyTitle:'Student status rule',urgency:'MEDIUM',
  summary:'Your program ends after the transition.',confidence:85,reasons:['Your program end date matches.',
    'Your travel date is outside the change.'],recommendedActions:['Read the official rule.','Check your program end date.'],
  createdAt:'2026-11-10T10:30:00Z',sourceUrl:'https://example.org/policy',reviewedByHuman:false,...overrides});
const response=(body,status=200)=>({ok:status>=200 && status<300,status,json:async()=>body});
function page(fetch){
  const state={view:'dashboard',user:{email:'student@example.org'},impacts:[]};
  const calls=[];
  const context=vm.createContext({state,calls,apiFetch:async(path,options)=>{
    calls.push([path,options]);return fetch(path,options);
  },render(){},goto(view){state.view=view;},clearLocalSession(){},
  formatTimestamp:value=>`date:${value}`,formatDate:value=>value});
  vm.runInContext(escaping+parsing+detailSource,context);
  return context;
}

test('selected assessment ID is used rather than policy ID and all detail fields are shown',async()=>{
  const view=page(async()=>response(saved()));
  await view.openSavedImpact(17);
  assert.deepEqual(view.calls,[['/api/v1/impact-assessments/17',undefined]]);
  assert.equal(view.state.view,'impactDetail');
  assert.equal(view.state.impactDetail.id,17);
  const rendered=view.renderImpactDetail();
  for(const text of ['Student status rule','MEDIUM','Your program ends after the transition.',
    'HIGH confidence (85%)','Your program end date matches.','Your travel date is outside the change.',
    'Read the official rule.','Check your program end date.','date:2026-11-10T10:30:00Z']) {
    assert.ok(rendered.includes(text),`Detail is missing ${text}`);
  }
  assert.match(rendered,/href="https:\/\/example.org\/policy" target="_blank" rel="noopener noreferrer"/);
  assert.match(rendered,/Back to your impacts/);
  assert.doesNotMatch(rendered,/setActionStatus|toggleActionComplete|setImpactArchived|type="checkbox"/);
});

for(const urgency of ['HIGH','MEDIUM','LOW','NOT_APPLICABLE']) {
  test(`card and detail use the same saved ${urgency} urgency and colours`,async()=>{
    const assessment=saved({urgency});
    const view=page(async()=>response(assessment));
    view.state.impacts=[assessment];
    const card=view.renderImpactFeed();
    assert.match(card,/openSavedImpact\(17\)/);
    await view.openSavedImpact(17);
    const detail=view.renderImpactDetail();
    for(const rendered of [card,detail]) {
      assert.ok(rendered.includes(urgency));
      assert.ok(rendered.includes(view.impactSeverityClass(urgency)));
      assert.ok(rendered.includes(assessment.summary));
    }
  });
}

test('repeated detail reads show saved advice without generating or creating checklist items',async()=>{
  const view=page(async()=>response(saved()));
  await view.openSavedImpact(17);
  await view.openSavedImpact(17);
  for(const [path,options] of view.calls) {
    assert.equal(path,'/api/v1/impact-assessments/17');
    assert.equal(options,undefined);
  }
  assert.equal(view.calls.length,2);
  const rendered=view.renderImpactDetail();
  assert.equal((rendered.match(/Read the official rule\./g) || []).length,1);
  assert.equal((rendered.match(/Check your program end date\./g) || []).length,1);
});

for(const status of [403,404]) {
  test(`HTTP ${status} clears previous detail and shows an unavailable message`,async()=>{
    const view=page(async()=>response({message:'Hidden internal details'},status));
    view.state.impactDetail=saved({summary:'Previously loaded private summary'});
    await view.openSavedImpact(99);
    assert.equal(view.state.impactDetail,null);
    assert.equal(view.state.impactDetailLoading,false);
    const rendered=view.renderImpactDetail();
    assert.match(rendered,/This assessment is unavailable or you do not have access to it/);
    assert.doesNotMatch(rendered,/Previously loaded private summary|Hidden internal details|Read the official rule/);
    assert.deepEqual(view.calls,[['/api/v1/impact-assessments/99',undefined]]);
  });
}

test('a failed detail read never falls back to generating another assessment',async()=>{
  const view=page(async()=>response({message:'Assessment service unavailable'},503));
  await view.openSavedImpact(17);
  assert.match(view.renderImpactDetail(),/Assessment service unavailable/);
  assert.equal(view.calls.length,1);
  assert.equal(view.state.impactDetail,null);
});

test('empty reasons, recommended actions and source have useful placeholders',async()=>{
  const view=page(async()=>response(saved({reasons:[],recommendedActions:[],sourceUrl:null})));
  await view.openSavedImpact(17);
  const rendered=view.renderImpactDetail();
  assert.match(rendered,/No further reasons were supplied/);
  assert.match(rendered,/No recommended actions were supplied/);
  assert.match(rendered,/original policy source link is unavailable/);
});

test('profile-derived content is escaped and executable source URLs are not linked',async()=>{
  const view=page(async()=>response(saved({policyTitle:'<script>title</script>',summary:'<img src=x>',
    reasons:['<script>reason</script>'],recommendedActions:['<script>action</script>'],
    sourceUrl:'javascript:alert(1)'})));
  await view.openSavedImpact(17);
  const rendered=view.renderImpactDetail();
  assert.match(rendered,/&lt;script&gt;title/);
  assert.match(rendered,/&lt;script&gt;reason/);
  assert.match(rendered,/&lt;script&gt;action/);
  assert.doesNotMatch(rendered,/<script>|<img src=x>|href="javascript:/);
});

test('late detail responses cannot replace the more recently selected assessment',async()=>{
  const pending=[];
  const view=page(()=>new Promise(resolve=>pending.push(resolve)));
  const first=view.openSavedImpact(17);
  assert.match(view.renderImpactDetail(),/Loading assessment/);
  const second=view.openSavedImpact(18);
  pending[1](response(saved({id:18,summary:'Latest selection'})));
  await second;
  pending[0](response(saved({summary:'Old selection'})));
  await first;
  assert.equal(view.state.impactDetail.id,18);
  assert.match(view.renderImpactDetail(),/Latest selection/);
  assert.doesNotMatch(view.renderImpactDetail(),/Old selection/);
});

test('late detail responses do not populate a different signed-in account',async()=>{
  let resolve;
  const view=page(()=>new Promise(done=>resolve=done));
  const loading=view.openSavedImpact(17);
  view.state={view:'dashboard',user:{email:'different@example.org'},impactDetail:null};
  resolve(response(saved()));
  await loading;
  assert.equal(view.state.impactDetail,null);
});
