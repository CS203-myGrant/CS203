const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function loadImpacts('),
  html.indexOf("window.addEventListener('beforeunload'"));
const item=(id,assessmentId,title,status='PENDING')=>({id,assessmentId,policyId:1,
  policyTitle:`Policy ${assessmentId}`,title,description:null,priority:'HIGH',status,
  deadline:'2026-11-05',urgent:false,overdue:false,completedAt:null,dismissedAt:null,text:title});
const impact=(id,severity,summary)=>({id,urgency:severity,summary,policyTitle:`Policy ${id}`,
  status:'COMPLETED',reviewStatus:'NOT_REQUIRED',requiresReview:false,confidence:85,confidenceLabel:'HIGH',createdAt:'2026-10-30T00:00:00Z',
  actionItems:[item(id*10,id,'Check the official source')],citations:[],archived:false,
  reviewedByHuman:false});
function page(fetch){
  const state={view:'dashboard',user:{visaType:'F-1',profileComplete:true},
    policyRecords:[{visaType:'F-1'}],impacts:null,impactsArchived:false,
    impactsPage:0,impactsTotalPages:0};
  const context=vm.createContext({state,apiFetch:fetch,parseApiResponse:async x=>x,
    render(){},showToast(){},goto(view){state.view=view;},
    escapeHtml:x=>String(x).replaceAll('<','&lt;'),
    escapeAttribute:x=>String(x).replaceAll('"','&quot;'),
    formatTimestamp:x=>x,formatDate:x=>x});
  vm.runInContext(source,context);
  return context;
}
test('loads saved feed in API order and renders card metadata and navigation',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{calls.push([path,options]);return {content:[
    impact(2,'HIGH','Act now'),impact(1,'LOW','Reminder')],totalPages:1};});
  await view.loadImpacts();
  assert.equal(calls.length,1);
  assert.equal(calls[0][0],'/api/v1/impact-assessments?page=0&size=20');
  assert.equal(calls[0][1],undefined);
  const rendered=view.renderImpactFeed();
  assert.ok(rendered.indexOf('Act now')<rendered.indexOf('Reminder'));
  assert.match(rendered,/Policy 2/);
  assert.match(rendered,/HIGH confidence/);
  assert.match(rendered,/2026-10-30T00:00:00Z/);
  assert.match(rendered,/impact-critical/);
  assert.match(rendered,/openSavedImpact\(2\)/);
  assert.doesNotMatch(rendered,/Archive|Check the official source|See how this impacts me/);
});
test('urgency filters reset pagination and next and previous retain the filter',async()=>{
  const calls=[];
  const view=page(async path=>{calls.push(path);return {content:[impact(1,'MEDIUM','Summary')],totalPages:3};});
  view.state.impactsPage=2;
  await view.showImpactUrgency('MEDIUM');
  assert.equal(calls[0],'/api/v1/impact-assessments?page=0&size=20&urgency=MEDIUM');
  assert.equal(view.state.impactsPage,0);
  assert.match(view.renderImpactFeed(),/value="MEDIUM" selected/);
  await view.loadMoreImpacts();
  assert.equal(calls[1],'/api/v1/impact-assessments?page=1&size=20&urgency=MEDIUM');
  assert.equal(view.state.impacts.length,1,'pages replace rather than append records');
  assert.match(view.renderImpactFeed(),/Page 2 of 3/);
  assert.match(view.renderImpactFeed(),/onclick="loadImpacts\(0\)"/);
  await view.loadImpacts(0);
  await view.showImpactUrgency('');
  assert.equal(calls.at(-1),'/api/v1/impact-assessments?page=0&size=20');
});
test('saved card opens its own detail endpoint without generation or legacy checklist operations',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{calls.push([path,options]);return {
    ...impact(7,'MEDIUM','Saved summary'),reasons:['Dates match'],recommendedActions:['Read the rule'],
    sourceUrl:'https://example.org/rule'};});
  await view.openSavedImpact(7);
  assert.equal(view.state.view,'impactDetail');
  assert.deepEqual(calls,[['/api/v1/impact-assessments/7',undefined]]);
  const rendered=view.renderImpactDetail();
  assert.match(rendered,/Saved summary|Dates match|Read the rule/);
  assert.match(rendered,/Original policy source/);
  assert.doesNotMatch(rendered,/setImpactArchived|setActionStatus/);
});
test('confidence labels and canonical urgency colours are consistent',()=>{
  const view=page(async()=>({}));
  view.state.impacts=[impact(1,'HIGH','A'),impact(2,'MEDIUM','B'),impact(3,'LOW','C'),impact(4,'NOT_APPLICABLE','D')];
  view.state.impacts[1].confidence=60;
  view.state.impacts[2].confidence=59;
  const rendered=view.renderImpactFeed();
  for(const urgency of ['HIGH','MEDIUM','LOW','NOT_APPLICABLE']) assert.match(rendered,new RegExp(urgency));
  for(const cls of ['impact-critical','impact-medium','impact-low']) assert.ok(rendered.includes(cls));
  assert.match(rendered,/HIGH confidence/);
  assert.match(rendered,/MEDIUM confidence/);
  assert.match(rendered,/LOW confidence/);
});
test('late filter responses cannot replace the selected filter results',async()=>{
  const pending=[];
  const view=page(()=>new Promise(resolve=>pending.push(resolve)));
  const first=view.showImpactUrgency('HIGH');
  const second=view.showImpactUrgency('LOW');
  pending[1]({content:[impact(2,'LOW','Latest')],totalPages:1});
  await second;
  pending[0]({content:[impact(1,'HIGH','Stale')],totalPages:1});
  await first;
  assert.equal(view.state.impacts[0].summary,'Latest');
});
test('responses arriving after an account change do not populate the next account',async()=>{
  let resolve;
  const view=page(()=>new Promise(done=>resolve=done));
  const loading=view.loadImpacts();
  view.state={view:'dashboard',user:{email:'another@example.org'},impacts:null};
  resolve({content:[impact(1,'HIGH','Private')],totalPages:1});
  await loading;
  assert.equal(view.state.impacts,null);
});
test('loading, filtered empty and failed reads are represented without generation links',async()=>{
  const view=page(async()=>{throw new Error('Service unavailable');});
  view.state.impactsLoading=true;
  assert.match(view.renderImpactFeed(),/Loading your assessments/);
  await view.loadImpacts();
  assert.match(view.renderImpactFeed(),/Service unavailable/);
  assert.match(view.renderImpactFeed(),/Try again/);
  view.state.impactsError=null;
  view.state.impactsUrgency='HIGH';
  assert.match(view.renderImpactFeed(),/No assessments match this urgency filter/);
  view.state.impactsUrgency='';
  assert.match(view.renderImpactFeed(),/No assessments are available yet/);
  assert.doesNotMatch(view.renderImpactFeed(),/create one|See how this impacts me/);
});
test('the live dashboard renders the saved feed without rebuilding cards in policy order',()=>{
  const view=page(async()=>({}));
  vm.runInContext(html.slice(html.indexOf('function renderLiveDashboard(){'),html.indexOf('async function loadForecastAlerts(){')),view);
  view.state.impacts=[impact(2,'HIGH','API first'),impact(1,'LOW','API second')];
  Object.assign(view,{window:{},permissionPromptedThisSession:false,renderProcessingTimeline:()=>'',renderActionChecklist:()=>''});
  const rendered=view.renderLiveDashboard();
  assert.ok(rendered.indexOf('API first')<rendered.indexOf('API second'));
  assert.match(rendered,/Your impact assessments/);
  assert.doesNotMatch(rendered,/openPolicyDetail|setImpactArchived/);
});
test('detail shows human correction, citation, disclaimer and escaped text',()=>{
  const view=page(async()=>({}));
  view.state.impactDetail={...impact(4,'HIGH','<script>bad</script>'),
    policyAgency:'DHS',analysis:['A reason'],missingInformation:[],
    citations:[{title:'Official rule',url:'https://www.federalregister.gov/example'}],
    reviewedByHuman:true,disclaimer:'Not legal advice'};
  const rendered=view.renderImpactDetail();
  assert.match(rendered,/Reviewed by a human/);
  assert.match(rendered,/Official rule/);
  assert.match(rendered,/Not legal advice/);
  assert.match(rendered,/&lt;script>/);
  assert.doesNotMatch(rendered,/<script>/);
});
test('empty state explains incomplete profile',()=>{
  const view=page(async()=>({}));
  view.state.impacts=[];
  view.state.user.profileComplete=false;
  assert.match(view.renderImpactFeed(),/Complete your profile/);
});

test('all inline scripts compile as browser JavaScript',()=>{
  for(const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)) new vm.Script(match[1]);
});
test('card metadata and summaries are escaped',()=>{
  const view=page(async()=>({}));
  view.state.impacts=[{...impact(1,'HIGH','<script>private</script>'),policyTitle:'<img src=x>'}];
  const rendered=view.renderImpactFeed();
  assert.match(rendered,/&lt;script>/);
  assert.match(rendered,/&lt;img src=x>/);
  assert.doesNotMatch(rendered,/<script>|<img src=x>/);
});
