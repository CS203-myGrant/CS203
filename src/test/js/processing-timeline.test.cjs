const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');

const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('async function loadForecasts(){'),html.indexOf('async function loadImpacts(){'));
function page(fetch){
  const state={role:'student',view:'dashboard',user:{email:'first@example.org'},processingForecasts:null,
    processingForecastError:null,forecastSeries:{}};
  const context=vm.createContext({state,URLSearchParams,apiFetch:fetch,parseApiResponse:async value=>value,
    render(){},escapeHtml:value=>String(value).replaceAll('<','&lt;'),formatDate:value=>value});
  vm.runInContext(source,context);
  return context;
}
test('timeline shows source dates, uncertainty and deadline conflict',()=>{
  const view=page(async()=>({}));
  view.state.processingForecasts={'I-765':{status:'AVAILABLE',formType:'I-765',
    formCategory:'OPT',office:'NBC',filingDate:'2026-01-01',rangeStartDate:'2026-04-01',
    medianDecisionDate:null,upperBoundDecisionDate:'2026-06-01',deadlineType:'OPT_START_DATE',
    deadlineDate:'2026-05-01',deadlineConflict:true,deadlineWarning:'Timing risk',
    sourceSnapshotDates:['2025-12-01','2025-12-08'],uncertaintyExplanation:'Few observations widened the range.'},
    'I-539':{status:'UNAVAILABLE',reason:'MISSING_FILING_DATE'}};
  const rendered=view.renderProcessingTimeline();
  assert.match(rendered,/2026-01-01/);
  assert.match(rendered,/2026-06-01/);
  assert.match(rendered,/2025-12-08/);
  assert.match(rendered,/Few observations widened/);
  assert.match(rendered,/Timing risk/);
  assert.match(rendered,/Add your filing date/);
});
test('late result from previous account is discarded',async()=>{
  const finishes=[];
  const view=page(()=>new Promise(resolve=>{finishes.push(resolve);}));
  const loading=view.loadForecasts();
  view.state.user={email:'second@example.org'};
  finishes.forEach(finish=>finish({status:'UNAVAILABLE',reason:'MISSING_FILING_DATE'}));
  await loading;
  assert.equal(view.state.processingForecasts,null);
});
test('ambiguous USCIS series offers matching category and office choices',()=>{
  const view=page(async()=>({}));
  view.state.processingForecasts={'I-765':{status:'UNAVAILABLE',reason:'AMBIGUOUS_SERIES',
    availableSeries:[{category:'OPT',office:'NBC'},{category:'STEM OPT',office:'Texas'}]}};
  const rendered=view.renderProcessingTimeline();
  assert.match(rendered,/Your USCIS category and office/);
  assert.match(rendered,/STEM OPT · Texas/);
});
test('saved deadline warning appears in the dashboard notifications section',()=>{
  const dashboardSource=html.slice(html.indexOf('function renderLiveDashboard(){'),
    html.indexOf('async function loadForecastAlerts(){'));
  const state={user:{visaType:'F-1'},testClock:null,policyRecords:[],notifications:[],
    forecastAlerts:[{formType:'I-765',deadlineDate:'2026-05-01',
      message:'USCIS decision may pass the OPT start date'}],unreadCount:0};
  const context=vm.createContext({state,window:{},permissionPromptedThisSession:false,
    escapeHtml:value=>String(value),formatDate:value=>value,formatTimestamp:value=>value,
    renderProcessingTimeline:()=>'',renderImpactFeed:()=>'',renderActionChecklist:()=>''});
  vm.runInContext(dashboardSource,context);
  assert.match(context.renderLiveDashboard(),/USCIS decision may pass the OPT start date/);
});
