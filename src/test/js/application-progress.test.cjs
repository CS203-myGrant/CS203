const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('const APPLICATION_STEPS='),html.indexOf('async function openPolicyDetail('));
function setup(fetch){
  const context=vm.createContext({state:{token:'session',view:'applicationProgress'},apiFetch:fetch,parseApiResponse:async x=>x,render(){}});
  vm.runInContext(source,context);return context;
}
test('progress reflects completed steps and next incomplete step',()=>{
  const ctx=setup();
  assert.equal(ctx.applicationProgress([]).percent,0);
  assert.equal(ctx.applicationProgress([1,3]).next,1);
  assert.equal(ctx.applicationProgress([1,3]).percent,29);
  assert.equal(ctx.applicationProgress([1,2,3,4,5,6,7]).percent,100);
  assert.equal(ctx.applicationProgress([1,2,3,4,5,6,7]).next,-1);
});
test('loads saved checklist from the authenticated account',async()=>{
  let path;
  const ctx=setup(async p=>{path=p;return {details:{visaType:'F-1',completedSteps:[1,2]},updatedAt:'2026-10-29T00:00:00Z'};});
  await ctx.loadApplicationProgress();
  assert.equal(path,'/api/v1/profiles/me/application-progress');
  assert.equal(ctx.state.applicationTracker.details.completedSteps.length,2);
  assert.equal(ctx.state.applicationTracker.loading,false);
});
test('failed load is retryable and never presented as saved progress',async()=>{
  const ctx=setup(async()=>{throw Error('Unavailable');});
  await ctx.loadApplicationProgress();
  assert.equal(ctx.state.applicationTracker.error,'Unavailable');
  assert.equal(ctx.state.applicationTracker.details,null);
});
test('leaving with unsaved changes can be canceled or confirmed',()=>{
  const ctx=setup();
  ctx.state.applicationTracker={dirty:true};
  ctx.window={confirm:()=>false};
  assert.equal(ctx.confirmApplicationLeave(),false);
  assert.equal(ctx.state.applicationTracker.dirty,true);
  ctx.window.confirm=()=>true;
  assert.equal(ctx.confirmApplicationLeave(),true);
  assert.equal(ctx.state.applicationTracker.dirty,false);
});
test('successful update clears dirty state and updates timestamp',async()=>{
  const details={visaType:'F-1',completedSteps:[1]};
  const ctx=setup(async()=>({details,updatedAt:'2026-10-29T00:00:00Z'}));
  ctx.readApplicationForm=()=>details;
  ctx.state.applicationTracker={details,dirty:true,saving:false};
  await ctx.saveApplicationProgress({preventDefault(){},target:{}});
  assert.equal(ctx.state.applicationTracker.dirty,false);
  assert.equal(ctx.state.applicationTracker.updatedAt,'2026-10-29T00:00:00Z');
  assert.equal(ctx.state.applicationTracker.savedSnapshot,JSON.stringify(details));
});
test('failed update retains draft and unsaved warning',async()=>{
  const ctx=setup(async()=>{throw Error('Save failed');});
  const details={visaType:'J-1',completedSteps:[1,2]};
  ctx.readApplicationForm=()=>details;
  ctx.state.applicationTracker={details,dirty:true,saving:false,updatedAt:null};
  await ctx.saveApplicationProgress({preventDefault(){},target:{}});
  assert.equal(ctx.state.applicationTracker.dirty,true);
  assert.equal(ctx.state.applicationTracker.details,details);
  assert.equal(ctx.state.applicationTracker.updatedAt,null);
  assert.equal(ctx.applicationSaveStatus(ctx.state.applicationTracker),'Unsaved changes');
});
test('refresh or close warns only while dirty',()=>{
  let handler;
  const ctx=vm.createContext({state:{applicationTracker:{dirty:true}},window:{addEventListener:(name,fn)=>{assert.equal(name,'beforeunload');handler=fn;}}});
  vm.runInContext(html.slice(html.indexOf("window.addEventListener('beforeunload'"),html.indexOf('const APPLICATION_STEPS=')),ctx);
  let prevented=false;
  const event={preventDefault(){prevented=true;}};
  handler(event);
  assert.equal(prevented,true);
  assert.equal(event.returnValue,'');
  ctx.state.applicationTracker.dirty=false;prevented=false;
  handler({preventDefault(){prevented=true;}});
  assert.equal(prevented,false);
});
