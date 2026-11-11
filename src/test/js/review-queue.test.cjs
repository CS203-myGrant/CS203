const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

// CS203-97: the reviewer side of applicant reports.
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('function renderQueue(){'),html.indexOf('function bulletinCurrentMonth('));
const draft={id:7,userId:2,applicantName:'Ana',applicantEmail:'ana@example.org',policyTitle:'Rail cargo rule',
  draftSummary:'No impact',draftUrgency:'LOW',draftConfidence:90,draftAnalysis:['Not immigration'],
  draftActionItems:['None'],draftMissingInformation:[],version:1,reports:[]};
const reported={...draft,id:8,reports:[{id:1,category:'DOES_NOT_APPLY_TO_ME',details:'Not me',assessmentVersion:1,createdAt:'2026-11-11'}]};

function queue(items,role='admin'){
  const context=vm.createContext({state:{role,reviewQueue:items,reviewQueueLoading:false,reviewQueueError:null},
    escapeHtml:x=>String(x ?? '').replaceAll('<','&lt;'),escapeAttribute:x=>String(x ?? '').replaceAll('"','&quot;'),
    reportCategoryLabel:x=>x,formatTimestamp:x=>x});
  vm.runInContext(source,context);
  return context;
}

test('reported items come first, are highlighted, and a banner counts the waiting reports',()=>{
  const rendered=queue([draft,reported]).renderQueue();
  assert.match(rendered,/1 applicant report waiting/);
  assert.match(rendered,/queue-row--reported/);
  assert.ok(rendered.indexOf('DOES_NOT_APPLY_TO_ME')<rendered.indexOf('Awaiting review'));
  assert.match(rendered,/Ana/);
  assert.match(rendered,/ana@example\.org/);
});

test('no banner when only drafts are queued',()=>{
  assert.doesNotMatch(queue([draft]).renderQueue(),/applicant report/);
});

test('an untouched reported assessment counts as unchanged, ignoring spacing and blank lines',()=>{
  const view=queue([reported]);
  assert.equal(view.reviewCorrectionUnchanged(reported,{severity:'LOW',confidence:90,summary:'  No   impact ',
    analysis:['Not immigration'],actionItems:['None'],missingInformation:[]}),true);
  assert.equal(view.reviewCorrectionUnchanged(reported,{severity:'MEDIUM',confidence:90,summary:'No impact',
    analysis:['Not immigration'],actionItems:['None'],missingInformation:[]}),false);
  assert.equal(view.reviewCorrectionUnchanged(reported,{severity:'LOW',confidence:90,summary:'No impact',
    analysis:['Not immigration','Checked the rule text'],actionItems:['None'],missingInformation:[]}),false);
});
