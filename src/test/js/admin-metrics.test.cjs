const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('app/mygrant-prototype.html','utf8');
const source=html.slice(html.indexOf('/* Admin quality metrics:'),html.indexOf('/* End admin quality metrics. */'));
const escaping=html.slice(html.indexOf('function escapeHtml(value){'),html.indexOf('function formatDate(value){'));
const fixture={evaluatedVersions:3,passedVersions:1,failedVersions:1,unavailableVersions:1,
  passRate:1/3,averageFaithfulness:.65,faithfulnessSampleSize:2,averageRelevance:.7,relevanceSampleSize:2,
  pendingReviewAtEnd:2,reviewQueueEntries:2,managerReviewedOutputs:3,comparableManagerReviews:2,
  managerOverrides:1,managerOverrideRate:.5};
function page(fetch,role='ADMIN'){
  const state={role:role==='ADMIN'?'admin':role==='MANAGER'?'manager':'student',user:{role},view:'admin',
    adminMetrics:null,adminMetricsLoading:false,adminMetricsError:null,
    adminMetricsFrom:'2032-04-05',adminMetricsTo:'2032-04-05',adminMetricsRequest:0};
  const context=vm.createContext({state,apiFetch:fetch,parseApiResponse:async x=>x,render(){},URLSearchParams});
  vm.runInContext(escaping,context);vm.runInContext(source,context);return context;
}
test('admin requests the selected dates and displays known rates, scores and queue counts',async()=>{
  const calls=[];
  const view=page(async path=>{calls.push(path);return fixture;});
  await view.loadAdminMetrics();
  assert.equal(calls[0],'/api/v1/admin/metrics?from=2032-04-05&to=2032-04-05');
  const rendered=view.renderAdminMetrics();
  for(const value of ['33.3%','0.650','0.700','50.0%','1 / 3','1 / 2','3 manager-reviewed records',
    'Pending review at range end','Entered review in range','UTC']) assert.ok(rendered.includes(value),value);
  assert.equal(view.state.adminMetricsLoading,false);
});
test('applicant and manager cannot fetch or render admin metrics even with cached values',async()=>{
  for(const role of ['APPLICANT','MANAGER']){
    const view=page(async()=>{throw new Error('must not fetch');},role);
    view.state.adminMetrics=fixture;
    assert.equal(view.renderAdminMetrics(),'');
    await view.loadAdminMetrics();
    assert.equal(view.state.adminMetrics,null);
  }
});
test('empty data displays No data rather than misleading zero rates or NaN',()=>{
  const view=page(async()=>({}));
  view.state.adminMetrics={...fixture,evaluatedVersions:0,passedVersions:0,failedVersions:0,unavailableVersions:0,
    passRate:null,averageFaithfulness:null,averageRelevance:null,managerOverrideRate:null};
  const rendered=view.renderAdminMetrics();
  assert.match(rendered,/No evaluated versions/);
  assert.equal((rendered.match(/No data/g)||[]).length,4);
  assert.doesNotMatch(rendered,/NaN|undefined/);
});
test('server errors clear old metrics and are escaped; reversed dates never fetch',async()=>{
  const view=page(async()=>{throw new Error('<script>denied</script>');});
  view.state.adminMetrics=fixture;
  await view.loadAdminMetrics();
  assert.equal(view.state.adminMetrics,null);
  assert.match(view.renderAdminMetrics(),/&lt;script&gt;denied&lt;\/script&gt;/);
  assert.doesNotMatch(view.renderAdminMetrics(),/<script>/);
  view.apiFetch=async()=>{throw new Error('should not fetch');};
  view.state.adminMetricsFrom='2032-04-06';
  await view.loadAdminMetrics();
  assert.match(view.state.adminMetricsError,/Start date/);
});
test('date form submits selected values and later requests win races',async()=>{
  const resolvers=[];
  const view=page(()=>new Promise(resolve=>resolvers.push(resolve)));
  const first=view.loadAdminMetrics();
  const second=view.applyAdminMetricsRange({preventDefault(){},currentTarget:{elements:{
    from:{value:'2032-04-06'},to:{value:'2032-04-07'}}}});
  resolvers[1]({...fixture,evaluatedVersions:7});await second;
  resolvers[0](fixture);await first;
  assert.equal(view.state.adminMetrics.evaluatedVersions,7);
  assert.equal(view.state.adminMetricsFrom,'2032-04-06');
});
test('a response arriving after logout cannot leak metrics into the new session',async()=>{
  let finish;
  const view=page(()=>new Promise(resolve=>finish=resolve));
  const request=view.loadAdminMetrics();
  view.state={role:'student',user:{role:'APPLICANT'},adminMetrics:null};
  finish(fixture);await request;
  assert.equal(view.state.adminMetrics,null);
});
test('an invalid replacement range prevents an older request from displaying stale metrics',async()=>{
  let finish;
  const view=page(()=>new Promise(resolve=>finish=resolve));
  const first=view.loadAdminMetrics();
  view.state.adminMetricsFrom='2032-04-06';
  await view.loadAdminMetrics();
  finish(fixture);await first;
  assert.equal(view.state.adminMetrics,null);
  assert.equal(view.state.adminMetricsLoading,false);
  assert.match(view.state.adminMetricsError,/Start date/);
});
