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
test('report includes policy, issue, and recommendation',async()=>{
  let sent;
  const context=setup(async(path,options)=>{sent={path,body:JSON.parse(options.body)};return {};});
  context.state.policyDetail={id:7,policy:{externalId:'2026-1',title:'Policy'},assessment:{summary:'Summary',recommendedActions:['Check source']},reportText:'Wrong deadline'};
  await context.reportPolicyRecommendation({preventDefault(){}});
  assert.equal(sent.path,'/api/v1/feedback/messages');
  assert.match(sent.body.message,/2026-1/);
  assert.match(sent.body.message,/Wrong deadline/);
  assert.match(sent.body.message,/Check source/);
  assert.equal(context.state.policyDetail.reported,true);
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
test('an AI outage shows the server message with a way to try again (CS203-174)',async()=>{
  const message='The AI service is temporarily unavailable. Please try again later.';
  const context=setup(async path=>path.endsWith('recommendation')
    ?{status:503,ok:false,json:async()=>({message})}
    :{id:7,title:'Policy',status:'FINAL',agency:'DHS',visaType:'F-1',content:''});
  context.parseApiResponse=async response=>{
    if(response.ok===false) throw new Error((await response.json()).message);
    return response;
  };
  context.escapeHtml=value=>String(value ?? '');
  context.escapeAttribute=value=>String(value ?? '');
  context.formatDate=value=>String(value ?? '');
  await context.openPolicyDetail(7);
  assert.equal(context.state.policyDetail.error,message);
  const rendered=context.renderPolicyDetail();
  assert.match(rendered,/temporarily unavailable\. Please try again later\./);
  assert.match(rendered,/Try again/);
});
test('a draft awaiting review shows no draft content or advice',()=>{
  const context=setup(async()=>({}));
  context.escapeHtml=value=>String(value ?? '');
  const rendered=context.renderPolicyRecommendation({status:'AWAITING_REVIEW',urgency:null,
    summary:'This assessment is awaiting review.',requiresHumanReview:true,recommendedActions:[]});
  assert.match(rendered,/Awaiting review/);
  assert.match(rendered,/reviewer is checking/);
  assert.doesNotMatch(rendered,/What to do next|Unavailable|Not independently verified/);
});
