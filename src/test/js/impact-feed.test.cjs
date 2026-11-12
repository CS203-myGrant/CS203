const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function loadImpacts(){'),
  html.indexOf("window.addEventListener('beforeunload'"));
const item=(id,assessmentId,title,status='PENDING')=>({id,assessmentId,policyId:1,
  policyTitle:`Policy ${assessmentId}`,title,description:null,priority:'HIGH',status,
  deadline:'2026-11-05',urgent:false,overdue:false,completedAt:null,dismissedAt:null,text:title});
const impact=(id,severity,summary)=>({id,severity,summary,policyTitle:`Policy ${id}`,
  confidence:85,confidenceLabel:'HIGH',createdAt:'2026-10-30T00:00:00Z',
  actionItems:[item(id*10,id,'Check the official source')],citations:[],archived:false,
  reviewedByHuman:false});
function page(fetch){
  const state={view:'dashboard',user:{visaType:'F-1',profileComplete:true},
    policyRecords:[{visaType:'F-1'}],impacts:null,impactsArchived:false,
    impactsPage:0,impactsTotalPages:0};
  const context=vm.createContext({state,apiFetch:fetch,parseApiResponse:async x=>x,
    render(){},showToast(){},goto(view){state.view=view;},markReportUpdatesSeen(){},
    escapeHtml:x=>String(x).replaceAll('<','&lt;'),
    escapeAttribute:x=>String(x).replaceAll('"','&quot;'),
    formatTimestamp:x=>x,formatDate:x=>x});
  vm.runInContext(source,context);
  return context;
}
test('loads current published feed in severity order and shows critical warning first',async()=>{
  const calls=[];
  const view=page(async path=>{calls.push(path);return {content:[
    impact(2,'HIGH','Act now'),impact(1,'LOW','Reminder')],totalPages:1};});
  await view.loadImpacts();
  assert.match(calls[0],/sort=severity,desc&archived=false/);
  const rendered=view.renderImpactFeed();
  assert.ok(rendered.indexOf('HIGH')<rendered.indexOf('Reminder'));
  assert.match(rendered,/View details and checklist/);
  assert.doesNotMatch(rendered,/Check the official source/);
});
test('archives through PATCH and switches to the archived list',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{
    calls.push([path,options]);
    return options?.method==='PATCH'?{...impact(2,'HIGH','Act now'),archived:true}
      :{content:[],totalPages:0};
  });
  await view.setImpactArchived(2,true);
  assert.equal(calls[0][0],'/api/v1/impacts/2');
  assert.equal(calls[0][1].method,'PATCH');
  assert.equal(JSON.parse(calls[0][1].body).archived,true);
  assert.match(calls[1][0],/archived=true/);
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

test('report posts a category, details and the viewed version, then lists it and keeps the form open',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{calls.push([path,options]);
    return {id:3,assessmentId:5,assessmentVersion:2,category:'WRONG_SEVERITY',details:'Not urgent for me',status:'OPEN',createdAt:'2026-11-11T00:00:00Z'};});
  view.state.view='impactDetail';
  view.state.impactDetail={...impact(5,'HIGH','Urgent'),version:2};
  view.state.impactRecommendationReport={category:'',text:'Not urgent for me',sending:false,history:[],justSent:false,error:null};

  await view.reportImpactRecommendation({preventDefault(){}});
  assert.equal(calls.length,0);
  assert.match(view.state.impactRecommendationReport.error,/Choose what kind of problem/);

  view.state.impactRecommendationReport.category='WRONG_SEVERITY';
  await view.reportImpactRecommendation({preventDefault(){}});
  assert.equal(calls.length,1);
  assert.equal(calls[0][0],'/api/v1/impacts/5/reports');
  assert.equal(calls[0][1].method,'POST');
  assert.deepEqual(JSON.parse(calls[0][1].body),{category:'WRONG_SEVERITY',details:'Not urgent for me',version:2});
  assert.equal(view.state.impactRecommendationReport.history[0].status,'OPEN');
  assert.doesNotMatch(calls.map(call=>call[0]).join(' '),/feedback/);

  const rendered=view.renderImpactRecommendationReport(view.state.impactDetail);
  assert.match(rendered,/Your reports on this assessment/);
  assert.match(rendered,/The severity is wrong/);
  assert.match(rendered,/Waiting for review/);
  assert.match(rendered,/version 2/);
  // Another problem can still be reported.
  assert.match(rendered,/<form/);
  assert.match(rendered,/Send another report/);
});

test('report history shows whether each report was corrected, dismissed or is still waiting',async()=>{
  const view=page(async path=>{
    if(path==='/api/v1/impacts/5/reports') return [
      {id:3,assessmentId:5,assessmentVersion:2,category:'UNCLEAR',details:'Which dates?',status:'OPEN',createdAt:'2026-11-11T00:00:00Z'},
      {id:2,assessmentId:5,assessmentVersion:1,category:'WRONG_SEVERITY',details:'Not urgent',status:'RESOLVED',createdAt:'2026-11-09T00:00:00Z',resolvedAt:'2026-11-10T00:00:00Z'},
      {id:1,assessmentId:5,assessmentVersion:1,category:'OTHER',details:'Typo',status:'DISMISSED',createdAt:'2026-11-08T00:00:00Z',resolvedAt:'2026-11-08T12:00:00Z'}];
    if(path==='/api/v1/impacts/5') return {...impact(5,'LOW','Reminder'),policyId:1,version:2};
    return [];
  });
  view.loadPolicyChatHistory=async()=>{};
  await view.openImpact(5);
  assert.equal(view.state.impactRecommendationReport.history.length,3);
  const rendered=view.renderImpactRecommendationReport(view.state.impactDetail);
  assert.match(rendered,/Waiting for review/);
  assert.match(rendered,/Corrected 2026-11-10/);
  assert.match(rendered,/Reviewed — no change/);
  assert.match(rendered,/found it correct as published/);
  assert.match(rendered,/<form/);
});

test('form is replaced by a notice once five reports are waiting',()=>{
  const view=page(async()=>[]);
  const open=n=>({id:n,assessmentId:5,assessmentVersion:1,category:'OTHER',details:`P${n}`,status:'OPEN',createdAt:'2026-11-11T00:00:00Z'});
  view.state.impactRecommendationReport={category:'',text:'',sending:false,history:[1,2,3,4,5].map(open),justSent:false,error:null};
  const rendered=view.renderImpactRecommendationReport({...impact(5,'LOW','Reminder'),version:1});
  assert.doesNotMatch(rendered,/<form/);
  assert.match(rendered,/5 reports waiting for review/);
});

test('a closed report shows the reviewer note to the applicant',()=>{
  const view=page(async()=>[]);
  view.state.impactRecommendationReport={category:'',text:'',sending:false,justSent:false,error:null,history:[
    {id:1,assessmentId:5,assessmentVersion:1,category:'WRONG_SEVERITY',details:'Not urgent',status:'RESOLVED',
      createdAt:'2026-11-09',resolvedAt:'2026-11-10',resolutionNote:'Lowered to LOW: your program ends after the rule.'}]};
  const rendered=view.renderImpactRecommendationReport({...impact(5,'LOW','Reminder'),version:2});
  assert.match(rendered,/Reviewer's note/);
  assert.match(rendered,/Lowered to LOW: your program ends after the rule\./);
});

test('answered reports can be archived and restored; open ones cannot',async()=>{
  const calls=[];
  const view=page(async(path,options)=>{calls.push([path,options]);
    const body=JSON.parse(options.body);
    return {id:2,assessmentId:5,assessmentVersion:1,category:'OTHER',details:'Typo',status:'DISMISSED',
      createdAt:'2026-11-08',resolvedAt:'2026-11-09',archivedAt:body.archived?'2026-11-11':null};});
  view.loadReportUpdates=async()=>{};
  view.state.view='impactDetail';
  view.state.impactDetail={...impact(5,'LOW','Reminder'),version:1};
  view.state.impactRecommendationReport={category:'',text:'',sending:false,justSent:false,error:null,history:[
    {id:3,assessmentId:5,assessmentVersion:1,category:'UNCLEAR',details:'Open one',status:'OPEN',createdAt:'2026-11-11'},
    {id:2,assessmentId:5,assessmentVersion:1,category:'OTHER',details:'Typo',status:'DISMISSED',createdAt:'2026-11-08',resolvedAt:'2026-11-09'}]};

  let rendered=view.renderImpactRecommendationReport(view.state.impactDetail);
  assert.equal((rendered.match(/archiveImpactReport\(/g) || []).length,1,'only the answered report offers Archive');

  await view.archiveImpactReport(2,true);
  assert.equal(calls[0][0],'/api/v1/impacts/5/reports/2');
  assert.equal(calls[0][1].method,'PATCH');
  assert.deepEqual(JSON.parse(calls[0][1].body),{archived:true});
  rendered=view.renderImpactRecommendationReport(view.state.impactDetail);
  assert.match(rendered,/Show archived reports \(1\)/);
  assert.doesNotMatch(rendered,/Typo/);

  view.toggleArchivedImpactReports();
  rendered=view.renderImpactRecommendationReport(view.state.impactDetail);
  assert.match(rendered,/Typo/);
  assert.match(rendered,/Restore/);

  await view.archiveImpactReport(2,false);
  assert.deepEqual(JSON.parse(calls[1][1].body),{archived:false});
  assert.doesNotMatch(view.renderImpactRecommendationReport(view.state.impactDetail),/Show archived/);
});
