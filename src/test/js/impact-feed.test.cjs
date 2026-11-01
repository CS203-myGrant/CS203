const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function loadImpacts(){'),
  html.indexOf("window.addEventListener('beforeunload'"));
const impact=(id,severity,summary)=>({id,severity,summary,policyTitle:`Policy ${id}`,
  confidence:85,confidenceLabel:'HIGH',createdAt:'2026-10-30T00:00:00Z',
  actionItems:[{id:`${id}:1`,text:'Check the official source'}],citations:[],archived:false,
  reviewedByHuman:false});
function page(fetch){
  const state={view:'dashboard',user:{visaType:'F-1',profileComplete:true},
    policyRecords:[{visaType:'F-1'}],impacts:null,impactsArchived:false,
    impactsPage:0,impactsTotalPages:0};
  const context=vm.createContext({state,apiFetch:fetch,parseApiResponse:async x=>x,
    render(){},showToast(){},goto(view){state.view=view;},
    escapeHtml:x=>String(x).replaceAll('<','&lt;'),
    escapeAttribute:x=>String(x).replaceAll('"','&quot;'),
    formatTimestamp:x=>x});
  vm.runInContext(source,context);
  return context;
}
test('loads current published feed in severity order and shows critical warning first',async()=>{
  const calls=[];
  const view=page(async path=>{calls.push(path);return {content:[
    impact(2,'CRITICAL','Act now'),impact(1,'LOW','Reminder')],totalPages:1};});
  await view.loadImpacts();
  assert.match(calls[0],/sort=severity,desc&archived=false/);
  const rendered=view.renderImpactFeed();
  assert.ok(rendered.indexOf('WARNING: CRITICAL')<rendered.indexOf('Reminder'));
  assert.match(rendered,/Read details/);
  assert.match(rendered,/Check the official source/);
});
test('archives through PATCH and refetches the active list',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{
    calls.push([path,options]);
    return options?.method==='PATCH'?{...impact(2,'CRITICAL','Act now'),archived:true}
      :{content:[],totalPages:0};
  });
  await view.setImpactArchived(2,true);
  assert.equal(calls[0][0],'/api/v1/impacts/2');
  assert.equal(calls[0][1].method,'PATCH');
  assert.equal(JSON.parse(calls[0][1].body).archived,true);
  assert.match(calls[1][0],/archived=false/);
});
test('detail shows human correction, citation, disclaimer and escaped text',()=>{
  const view=page(async()=>({}));
  view.state.impactDetail={...impact(4,'CRITICAL','<script>bad</script>'),
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
