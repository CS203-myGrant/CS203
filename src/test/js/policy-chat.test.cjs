const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function loadPolicyChatHistory('),html.indexOf('async function reportPolicyRecommendation('));
function setup(fetch){
  const chat={messages:[],draft:'What should I do next?',sending:false,error:null,historyLoaded:true};
  const ctx=vm.createContext({state:{view:'policyDetail',policyDetail:{id:21,chat}},apiFetch:fetch,parseApiResponse:async x=>x,render(){},document:{getElementById(){return null;}}});
  vm.runInContext(source,ctx);return ctx;
}
test('sends policy-specific question without client-supplied profile or conversation',async()=>{
  let sent;
  const ctx=setup(async(path,options)=>{sent={path,body:JSON.parse(options.body)};return {answer:'Check source [1]',sourceUrl:'https://example.org',sourceTitle:'Policy',requiresReview:true};});
  ctx.state.policyDetail.chat.messages=Array.from({length:14},(_,i)=>({role:i%2?'ASSISTANT':'USER',message:'Turn '+i}));
  await ctx.sendPolicyQuestion({preventDefault(){}});
  assert.equal(sent.path,'/api/v1/policies/21/questions');
  assert.equal(sent.body.history.length,0);
  assert.deepEqual(Object.keys(sent.body),['question','history']);
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
test('reloads persisted question, answer, confidence and citations after refresh',async()=>{
  const ctx=setup();
  ctx.state.policyDetail.chat.historyLoaded=false;
  ctx.fetchAllPages=async path=>{assert.equal(path,'/api/v1/policies/21/questions');return [{question:'Saved question',response:{answer:'Saved answer',confidence:'MEDIUM',citations:[{title:'Source',sourceUrl:'https://example.org'}]}}];};
  await ctx.loadPolicyChatHistory(ctx.state.policyDetail);
  assert.equal(ctx.state.policyDetail.chat.messages[0].message,'Saved question');
  assert.equal(ctx.state.policyDetail.chat.messages[1].message,'Saved answer');
  assert.equal(ctx.state.policyDetail.chat.messages[1].confidence,'MEDIUM');
  assert.equal(ctx.state.policyDetail.chat.messages[1].citations[0].sourceUrl,'https://example.org');
  assert.equal(ctx.state.policyDetail.chat.historyLoaded,true);
});
