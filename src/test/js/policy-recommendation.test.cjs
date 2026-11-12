const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function openPolicyDetail('),html.indexOf('function renderNotificationDetail('));
function setup(fetch){
  const context=vm.createContext({state:{},apiFetch:fetch,parseApiResponse:async response=>response,
    goto(view){context.state.view=view;},render(){},fetchAllPages:async()=>[]});
  vm.runInContext(source,context);
  return context;
}
test('loads stored policy and personalized recommendation',async()=>{
  const calls=[];
  const context=setup(async(path,options)=>{calls.push([path,options]);return path.endsWith('recommendation')?{summary:'Impact'}:{id:7};});
  await context.openPolicyDetail(7);
  assert.equal(calls[0][0],'/api/v1/policies/7');
  assert.equal(calls[1][0],'/api/v1/policies/7/recommendation');
  assert.equal(calls[1][1].method,'POST');
  assert.equal(calls[2][0],'/api/v1/impacts/policy/7/latest');
  assert.equal(context.state.policyDetail.assessment.summary,'Impact');
  assert.equal(context.state.policyDetail.loading,false);
});
test('retains source details when recommendation fails',async()=>{
  const context=setup(async path=>{if(path.endsWith('recommendation'))throw Error('Unavailable');return {id:7};});
  await context.openPolicyDetail(7);
  assert.equal(context.state.policyDetail.policy.id,7);
  assert.equal(context.state.policyDetail.error,'Unavailable');
  assert.equal(context.state.policyDetail.loading,false);
});
test('policy detail report is linked to the saved assessment and review queue',async()=>{
  let sent;
  const context=setup(async(path,options)=>{sent={path,body:JSON.parse(options.body)};return {};});
  context.state.policyDetail={id:7,policy:{externalId:'2026-1',title:'Policy'},
    assessment:{summary:'Summary'},impact:{id:42,version:2},
    reportCategory:'WRONG_SEVERITY',reportText:'Wrong deadline'};
  await context.reportPolicyRecommendation({preventDefault(){}});
  assert.equal(sent.path,'/api/v1/impacts/42/reports');
  assert.deepEqual(sent.body,{category:'WRONG_SEVERITY',details:'Wrong deadline',version:2});
  assert.equal(context.state.policyDetail.reported,true);
});

test('only the displayed saved assessment may be linked for reporting',async()=>{
  const context=setup(async path=>path.endsWith('recommendation')
    ? {summary:'Current advice',urgency:'MEDIUM',status:'COMPLETED'}
    : path.includes('/impacts/policy/')
      ? {ok:true,summary:'Older advice',urgency:'LOW',id:42,version:1}
      : {id:7});
  await context.openPolicyDetail(7);
  assert.equal(context.state.policyDetail.impact,null);
});
test('extracts official summary from HTML Federal Register fixture',()=>{
  const context=setup(async()=>({}));
  const summary=context.policySummary(fs.readFileSync('src/test/resources/federal-register/2026-14439.txt','utf8'));
  assert.match(summary,/admission for a fixed time period/);
  assert.doesNotMatch(summary,/<html>|DATES:|<a|Federal Register Volume/);
});
test('does not substitute boilerplate for a missing summary',()=>{
  assert.match(setup(async()=>({})).policySummary('<html><title>Boilerplate</title>Text</html>'),/not available/);
});
test('missing endpoint retains policy with readable error',async()=>{
  const context=setup(async path=>path.endsWith('recommendation')?{status:404}:{id:21});
  await context.openPolicyDetail(21);
  assert.equal(context.state.policyDetail.policy.id,21);
  assert.match(context.state.policyDetail.error,/latest backend has been deployed/);
  assert.equal(context.state.policyDetail.assessment,null);
});
test('unavailable processing is separate from severity and hides next-step advice',()=>{
  const context=setup(async()=>({}));
  context.escapeHtml=value=>String(value ?? '');
  context.formatUrgency=value=>value || 'Unavailable';
  const rendered=context.renderPolicyRecommendation({status:'UNAVAILABLE',urgency:null,
    summary:'Try again later',requiresHumanReview:true,recommendedActions:['Check source']});
  assert.match(rendered,/Try again later/);
  assert.doesNotMatch(rendered,/What to do next|Not independently verified/);
});
test('completed HIGH impact retains the canonical label and next steps',()=>{
  const context=setup(async()=>({}));
  context.escapeHtml=value=>String(value ?? '');
  context.formatUrgency=value=>value || 'Unavailable';
  const rendered=context.renderPolicyRecommendation({status:'COMPLETED',urgency:'HIGH',
    summary:'Check your travel date',requiresHumanReview:false,recommendedActions:['Check source'],
    evaluation:{verdict:'YES'}});
  assert.match(rendered,/HIGH/);
  assert.match(rendered,/What to do next/);
  assert.doesNotMatch(rendered,/CRITICAL|WARNING|INFORMATIONAL|NO_IMPACT/);
});
