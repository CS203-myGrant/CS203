const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const savedSource=html.slice(html.indexOf('async function loadImpacts('),html.indexOf("window.addEventListener('beforeunload'"));
const escaping=html.slice(html.indexOf('function escapeHtml(value){'),html.indexOf('function formatDate(value){'));
const record={id:70,policyId:7,policyTitle:'Saved policy',status:'COMPLETED',urgency:'HIGH',summary:'Impact',confidence:85,
  reviewStatus:'NOT_REQUIRED',requiresReview:false,reasons:['Saved reason'],recommendedActions:['Read the source'],createdAt:'2026-11-10'};
const source=html.slice(html.indexOf('async function openPolicyDetail('),html.indexOf('function renderNotificationDetail('));
function setup(fetch){
  const context=vm.createContext({state:{},apiFetch:fetch,parseApiResponse:async response=>response,
    goto(view){context.state.view=view;},render(){},fetchAllPages:async()=>[],formatTimestamp:x=>x,formatDate:x=>x});
  vm.runInContext(escaping+savedSource+source,context);
  return context;
}
test('loads policy and saved assessment with read requests only',async()=>{
  const calls=[];
  const context=setup(async(path,options)=>{calls.push([path,options]);return path.startsWith('/api/v1/impact-assessments')?{content:[record]}:{id:7};});
  await context.openPolicyDetail(7);
  assert.equal(calls[0][0],'/api/v1/policies/7');
  assert.equal(calls[1][0],'/api/v1/impact-assessments?policyId=7&page=0&size=1');
  assert.ok(calls.every(([,options])=>options===undefined));
  assert.equal(context.state.policyDetail.assessment.summary,'Impact');
  assert.equal(context.state.policyDetail.loading,false);
});
test('retains source details when recommendation fails',async()=>{
  const context=setup(async path=>{if(path.startsWith('/api/v1/impact-assessments'))throw Error('Unavailable');return {id:7};});
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
test('policy with no visible saved assessment retains the source and does not generate',async()=>{
  const calls=[];
  const context=setup(async(path,options)=>{calls.push([path,options]);return path.startsWith('/api/v1/impact-assessments')?{content:[]}:{id:21};});
  await context.openPolicyDetail(21);
  assert.equal(context.state.policyDetail.policy.id,21);
  assert.equal(context.state.policyDetail.error,null);
  assert.equal(context.state.policyDetail.assessment,null);
  assert.equal(context.state.policyDetail.assessmentUnavailable,true);
  assert.ok(calls.every(([,options])=>options===undefined));
  assert.match(context.renderPolicyDetail(),/No saved assessment is currently available/);
});
test('policy detail reuses saved detail rendering and canonical review markers',async()=>{
  const approved={...record,reviewStatus:'APPROVED',requiresReview:true};
  const context=setup(async path=>path.startsWith('/api/v1/impact-assessments')?{content:[approved]}:{id:7,title:'Policy'});
  await context.openPolicyDetail(7);
  const rendered=context.renderPolicyDetail();
  for(const value of ['Saved policy','Saved reason','Read the source','HIGH confidence','Reviewed by a human']) assert.ok(rendered.includes(value));
  assert.doesNotMatch(rendered,/Not independently verified|Needs human review/);
});
test('hidden saved assessment is not displayed or regenerated in policy detail',async()=>{
  const context=setup(async path=>path.startsWith('/api/v1/impact-assessments')?{content:[{...record,reviewStatus:'PENDING_REVIEW',requiresReview:true}]}:{id:7});
  await context.openPolicyDetail(7);
  assert.equal(context.state.policyDetail.assessment,null);
  assert.doesNotMatch(context.renderPolicyDetail(),/Saved reason/);
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
