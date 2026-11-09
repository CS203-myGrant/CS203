const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function loadImpacts(){'),
  html.indexOf("window.addEventListener('beforeunload'"));
// The app's own escaping, so the escaping test checks what the page really does.
const escaping=html.slice(html.indexOf('function escapeHtml(value){'),html.indexOf('function formatDate(value){'));
const item=(id,overrides={})=>({id,assessmentId:4,policyId:9,policyTitle:'Student status rule',
  title:`Step ${id}`,description:null,priority:'HIGH',status:'PENDING',deadline:'2026-11-05',
  urgent:false,overdue:false,completedAt:null,dismissedAt:null,text:`Step ${id}`,...overrides});
function page(fetch){
  const state={view:'dashboard',user:{visaType:'F-1',profileComplete:true},policyRecords:[],
    impacts:null,impactsArchived:false,impactsPage:0,impactsTotalPages:0,
    actions:null,actionsTab:'active',actionsPage:0,actionsTotalPages:0,actionUpdating:null};
  const toasts=[];
  const context=vm.createContext({state,toasts,apiFetch:fetch,parseApiResponse:async x=>x,
    render(){},showToast(message){toasts.push(message);},goto(view){state.view=view;},
    formatTimestamp:x=>`at ${x}`,formatDate:x=>`on ${x}`});
  vm.runInContext(escaping,context);
  vm.runInContext(source,context);
  return context;
}

test('loads active items by default with visible deadline and urgency plus expandable details',async()=>{
  const calls=[];
  const view=page(async path=>{calls.push(path);return {content:[
    item(1,{title:'Renew your I-20',description:'Renew your I-20 before it expires and keep a copy',
      urgent:true}),
    item(2,{priority:'LOW',deadline:null,overdue:true,urgent:true})],totalPages:1};});
  await view.loadActions();
  assert.equal(calls[0],'/api/v1/actions?page=0&size=50');
  const rendered=view.renderActionChecklist();
  assert.match(rendered,/Your checklist/);
  assert.match(rendered,/Renew your I-20/);
  assert.match(rendered,/Due on 2026-11-05/);
  assert.match(rendered,/>Urgent</);
  assert.match(rendered,/<summary>More details<\/summary>/);
  assert.match(rendered,/Renew your I-20 before it expires and keep a copy/);
  assert.match(rendered,/aria-label="Complete task"/);
  assert.match(rendered,/toggleActionComplete\(1,'PENDING'\)/);
  assert.match(rendered,/>Dismiss</);
  assert.doesNotMatch(rendered,/HIGH priority|>Start<|>Complete</);
});

test('an item without a description or urgency shows neither',()=>{
  const view=page(async()=>({}));
  const rendered=view.renderActionItem(item(3),true);
  assert.doesNotMatch(rendered,/task-card__detail-label">Details/);
  assert.doesNotMatch(rendered,/Urgent|Overdue/);
});

test('completed and dismissed tabs request their corresponding statuses',async()=>{
  const calls=[];
  const view=page(async path=>{calls.push(path);return {content:[item(5,{status:'COMPLETED',
    completedAt:'2026-10-31T10:00:00Z'})],totalPages:1};});
  view.showActionTab('completed');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls[0],'/api/v1/actions?page=0&size=50&status=COMPLETED');
  const rendered=view.renderActionChecklist();
  assert.match(rendered,/aria-label="Mark task incomplete"/);
  assert.match(rendered,/<summary>More details<\/summary>/);
  assert.doesNotMatch(rendered,/>Complete</);
  view.showActionTab('dismissed');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(calls[1],'/api/v1/actions?page=0&size=50&status=DISMISSED');
});

test('active checklist tasks retain a dismiss action',()=>{
  const view=page(async()=>({}));
  assert.match(view.actionButtons(item(6)),/setActionStatus\(6,'DISMISSED'\)/);
  assert.match(view.actionButtons(item(6)),/>Dismiss</);
});

test('a status change PATCHes the item then refetches the checklist, feed and open detail',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{
    calls.push([path,options]);
    if(options?.method==='PATCH') return item(4,{status:'COMPLETED'});
    if(path==='/api/v1/impacts/4') return {id:4,actionItems:[item(4,{status:'COMPLETED'})]};
    return {content:[],totalPages:0};
  });
  view.state.view='impactDetail';
  view.state.impactDetail={id:4,actionItems:[item(4)]};
  await view.setActionStatus(4,'COMPLETED');
  assert.equal(calls[0][0],'/api/v1/actions/4');
  assert.equal(calls[0][1].method,'PATCH');
  assert.equal(JSON.parse(calls[0][1].body).status,'COMPLETED');
  const refetched=calls.slice(1).map(call=>call[0]);
  assert.ok(refetched.includes('/api/v1/actions?page=0&size=50'));
  assert.ok(refetched.some(path=>path.startsWith('/api/v1/impacts?')));
  assert.ok(refetched.includes('/api/v1/impacts/4'));
  assert.equal(view.state.impactDetail.actionItems[0].status,'COMPLETED');
  assert.equal(view.state.actionUpdating,null);
  assert.deepEqual(view.toasts,['Item completed.']);
});

test('a failed status change is reported and the views are still refetched',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{
    calls.push(path);
    if(options?.method==='PATCH') throw new Error('This action item belongs to another user');
    return {content:[],totalPages:0};
  });
  await view.setActionStatus(9,'DISMISSED');
  assert.deepEqual(view.toasts,['This action item belongs to another user']);
  assert.ok(calls.includes('/api/v1/actions?page=0&size=50'));
  assert.equal(view.state.actionUpdating,null);
});

test('impact cards keep checklist items inside the detail view',()=>{
  const view=page(async()=>({}));
  view.state.impacts=[{id:4,severity:'HIGH',summary:'Act soon',policyTitle:'Policy 4',confidence:85,
    confidenceLabel:'HIGH',createdAt:'2026-10-30T00:00:00Z',archived:false,reviewedByHuman:false,
    actionItems:[item(1,{status:'COMPLETED',title:'Finished step'}),item(2,{title:'First open step',urgent:true}),
      item(3,{status:'IN_PROGRESS',title:'Second open step'}),item(4,{title:'Third open step'})]}];
  const rendered=view.renderImpactFeed();
  assert.doesNotMatch(rendered,/Finished step|First open step|Second open step|Third open step/);
  assert.match(rendered,/View details and checklist/);
});

test('impact detail keeps the task completion and dismiss controls available',()=>{
  const view=page(async()=>({}));
  view.state.impactDetail={id:4,severity:'MEDIUM',summary:'Summary',policyTitle:'Policy 4',
    policyAgency:'DHS',confidence:85,confidenceLabel:'HIGH',createdAt:'2026-10-30T00:00:00Z',
    analysis:[],missingInformation:[],citations:[],archived:false,reviewedByHuman:false,
    disclaimer:'Not legal advice',actionItems:[item(1,{status:'DISMISSED',dismissedAt:'2026-10-31T00:00:00Z'}),
      item(2,{status:'IN_PROGRESS'})]};
  const rendered=view.renderImpactDetail();
  assert.match(rendered,/task-card--dismissed/);
  assert.match(rendered,/task-card--active/);
  assert.match(rendered,/>Dismiss</);
  assert.doesNotMatch(rendered,/Source: /);
});

test('loading, empty and error states',async()=>{
  const view=page(async()=>{throw new Error('Checklist unavailable');});
  assert.match(view.renderActionChecklist(),/Loading your checklist/);
  view.state.actions=[];
  assert.match(view.renderActionChecklist(),/Nothing to do right now/);
  view.state.actionsTab='dismissed';
  assert.match(view.renderActionChecklist(),/not dismissed any/);
  view.state.actionsTab='active';
  await view.loadActions();
  const rendered=view.renderActionChecklist();
  assert.match(rendered,/role="alert">Checklist unavailable/);
  assert.match(rendered,/onclick="loadActions\(\)">Try again/);
  assert.doesNotMatch(rendered,/Nothing to do right now/);
});

test('item text is escaped',()=>{
  const view=page(async()=>({}));
  const rendered=view.renderActionItem(item(1,{title:'<img src=x>',description:'<script>bad</script>',
    policyTitle:'<b>Policy</b>'}),true);
  assert.doesNotMatch(rendered,/<script>|<img|<b>/);
  assert.match(rendered,/aria-label="&lt;img src=x&gt;"/);
  assert.match(rendered,/&lt;script&gt;bad&lt;\/script&gt;/);
});
