const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('function renderPolicyChat('),html.indexOf('async function reportPolicyRecommendation('));
function setup(fetch){
  const chat={messages:[],draft:'What should I do next?',sending:false,error:null};
  const ctx=vm.createContext({state:{view:'policyDetail',policyDetail:{id:21,chat}},apiFetch:fetch,parseApiResponse:async x=>x,render(){},document:{getElementById(){return null;}}});
  vm.runInContext(source,ctx);return ctx;
}
test('sends policy-specific question and bounded history without client-supplied profile',async()=>{
  let sent;
  const ctx=setup(async(path,options)=>{sent={path,body:JSON.parse(options.body)};return {answer:'Check source [1]',sourceUrl:'https://example.org',sourceTitle:'Policy',requiresReview:true};});
  ctx.state.policyDetail.chat.messages=Array.from({length:14},(_,i)=>({role:i%2?'ASSISTANT':'USER',message:'Turn '+i}));
  await ctx.sendPolicyQuestion({preventDefault(){}});
  assert.equal(sent.path,'/api/v1/policies/21/questions');
  assert.equal(sent.body.history.length,10);
  assert.deepEqual(Object.keys(sent.body),['question','history']);
  assert.deepEqual(Object.keys(sent.body.history[0]),['role','message']);
  assert.equal(ctx.state.policyDetail.chat.messages.at(-1).message,'Check source [1]');
  assert.equal(ctx.state.policyDetail.chat.sending,false);
});
test('failed answer restores question and preserves previous conversation',async()=>{
  const ctx=setup(async()=>{throw Error('Temporarily unavailable');});
  ctx.state.policyDetail.chat.messages=[{role:'ASSISTANT',message:'Previous answer'}];
  await ctx.sendPolicyQuestion({preventDefault(){}});
  const chat=ctx.state.policyDetail.chat;
  assert.equal(chat.messages.length,1);
  assert.equal(chat.draft,'What should I do next?');
  assert.equal(chat.error,'Temporarily unavailable');
  assert.equal(chat.sending,false);
});
test('duplicate sends and blank questions do not call API',async()=>{
  let calls=0;
  const ctx=setup(async()=>{calls++;});
  ctx.state.policyDetail.chat.sending=true;
  await ctx.sendPolicyQuestion({preventDefault(){}});
  ctx.state.policyDetail.chat.sending=false;ctx.state.policyDetail.chat.draft='   ';
  await ctx.sendPolicyQuestion({preventDefault(){}});
  assert.equal(calls,0);
});
test('renders model text escaped and rejects unsafe source links',()=>{
  const ctx=setup();
  ctx.escapeHtml=text=>String(text).replace(/</g,'&lt;').replace(/>/g,'&gt;');
  ctx.escapeAttribute=ctx.escapeHtml;
  ctx.state.policyDetail.chat.messages=[{role:'ASSISTANT',message:'<script>bad</script>',sourceUrl:'javascript:alert(1)'}];
  const rendered=ctx.renderPolicyChat(ctx.state.policyDetail);
  assert.match(rendered,/&lt;script&gt;/);
  assert.doesNotMatch(rendered,/href="javascript:/);
});
