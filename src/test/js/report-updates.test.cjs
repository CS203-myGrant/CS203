const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

// CS203-97: the applicant's bell for reviewer answers to their reports.
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf("/* CS203-97: the applicant's bell"),html.indexOf('/** Red badge on "Review queue"'));

function element(){
  return {hidden:true,innerHTML:'',textContent:'',style:{},attrs:{},
    setAttribute(name,value){this.attrs[name]=value;},contains(){return false;},focus(){}};
}
function bell(fetch){
  const els={'report-updates-badge':element(),'report-updates-btn':element(),
    'report-updates-panel':element(),'report-updates-wrap':element()};
  const context=vm.createContext({state:{},apiFetch:fetch,parseApiResponse:async x=>x,
    escapeHtml:x=>String(x ?? '').replaceAll('<','&lt;'),reportCategoryLabel:x=>x,formatTimestamp:x=>x,
    openImpact(){},document:{getElementById:id=>els[id],addEventListener(){}}});
  vm.runInContext(source,context);
  return {context,els};
}
const update=(reportId,seen,note='Checked against your I-20.')=>({reportId,assessmentId:5,policyTitle:'Grace period rule',
  category:'UNCLEAR',status:'DISMISSED',resolvedAt:'2026-11-11',resolutionNote:note,seen});

test('badge shows how many reviewer answers are unseen',async()=>{
  const {context,els}=bell(async()=>({unseen:2,items:[update(1,false),update(2,false)]}));
  await context.loadReportUpdates();
  assert.equal(els['report-updates-badge'].textContent,'2');
  assert.equal(els['report-updates-badge'].style.display,'inline-flex');
});

test('opening the bell lists answers with the reviewer note, then marks them seen',async()=>{
  const calls=[];
  const {context,els}=bell(async(path,options)=>{calls.push([path,options]);
    return path.endsWith('/seen') ? {marked:1} : {unseen:1,items:[update(1,false)]};});
  await context.toggleReportUpdates();
  const panel=els['report-updates-panel'];
  assert.equal(panel.hidden,false);
  assert.match(panel.innerHTML,/Grace period rule/);
  assert.match(panel.innerHTML,/Reviewed — no change/);
  assert.match(panel.innerHTML,/Checked against your I-20\./);
  assert.match(panel.innerHTML,/New/);
  assert.ok(calls.some(([path,options])=>path==='/api/v1/report-updates/seen' && options.method==='POST'));
  assert.equal(els['report-updates-badge'].style.display,'none');
});

test('no seen call when everything was already seen, and an empty state otherwise',async()=>{
  const calls=[];
  const {context,els}=bell(async path=>{calls.push(path);return {unseen:0,items:[]};});
  await context.toggleReportUpdates();
  assert.match(els['report-updates-panel'].innerHTML,/No updates yet/);
  assert.ok(!calls.includes('/api/v1/report-updates/seen'));
});
