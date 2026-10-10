import test from 'node:test';
import assert from 'node:assert/strict';
import {createState, reduce, kind, canFinish, games, gameLabel, defaultSavePaths, saveFixture, saveStatus, localIsEmpty, deviceLabel, isSynced, canRestore, latestCloud, selectedTarget, targetMatchesLocal, historyEdges, historyRows, historyColumnId, visibleHistory, displayEdges, localState, matchingCloud, snapshotName} from './model.mjs';
const act=(s,type,extra={})=>reduce(s,{type,...(type==='restore'?{confirmationToken:s.restoreConfirm?.token}:{}),...extra});
const settle=s=>act(s,'settled',{token:s.token});
const pathStep=(scene='empty',id='shattered')=>act(createState(scene,id),'next');
const read=(scene='empty',id='shattered')=>settle(pathStep(scene,id));
const connected=(scene='empty',id='shattered')=>act(settle(act(act(read(scene,id),'next'),'connect')),'next');
test('four exact Windows defaults preserve separator, leading dot, case and spaces',()=>{
 const tails={shattered:'.shatteredpixel\\Shattered Pixel Dungeon',experienced:'.trashboxbobylev\\Experienced Pixel Dungeon',cruel:'.cruelpixel\\Too Cruel Pixel Dungeon',rearranged:'.rearrangedpixel\\Rearranged Pixel Dungeon'};
 for(const [id,tail] of Object.entries(tails)) assert.equal(defaultSavePaths[id],'C:\\Users\\Demo\\AppData\\Roaming\\'+tail);
});
test('entry previews in place; next waits for that result without a second read',()=>{
 let s=pathStep();assert.equal(s.page,'read');assert.equal(s.readStatus,'loading');assert.equal(s.local,null);assert.equal(saveStatus(s),'正在检查存档…');
 const token=s.token;s=act(s,'next');assert.equal(s.token,token);s=settle(s);assert.equal(s.page,'connect');assert.equal(s.readStatus,'empty');
 let preview=read();assert.equal(preview.page,'read');assert.equal(saveStatus(preview),'游玩 0 次 · 存档 0 个');assert.equal(act(preview,'next').page,'connect');
 preview=act(preview,'editPath',{value:'  '});assert.equal(act(preview,'next'),preview);assert.equal(saveStatus(preview),'请设置存档路径');
});
test('edited path survives same-game back/forward and connection cancellation',()=>{
 let s=act(pathStep(),'editPath',{value:'D:\\My Saves'});s=act(s,'back');s=act(s,'chooseGame');s=act(s,'next');assert.equal(s.savePath,'D:\\My Saves');assert.equal(s.busy,true);
 s=settle(act(s,'next'));s=act(s,'connect');const token=s.token;s=act(s,'back');assert.equal(s.page,'read');assert.equal(s.savePath,'D:\\My Saves');assert.equal(act(s,'settled',{token}),s);
});
test('new path cancels pending read; stale callback cannot advance or populate saves',()=>{
 let s=act(pathStep('local'),'next');const token=s.token;s=act(s,'editPath',{value:'D:\\Different'});
 assert.equal(s.busy,false);assert.equal(s.local,null);assert.equal(s.readStatus,'unread');assert.equal(act(s,'settled',{token}),s);
 s=settle(act(s,'next'));assert.equal(s.page,'connect');assert.equal(s.local,true);
});
test('back cancels validation; changing game replaces default and invalidates previous state',()=>{
 let s=act(pathStep('local'),'next');const token=s.token;s=act(s,'back');s=act(s,'chooseGame',{gameId:'cruel'});
 assert.equal(s.savePath,defaultSavePaths.cruel);assert.equal(s.local,null);assert.equal(s.connected,false);assert.equal(s.readStatus,'unread');assert.equal(act(s,'settled',{token}),s);
});
test('folder chooser cancel preserves path; confirm selects only synthetic path without reading',()=>{
 let s=read();const path=s.savePath;s=act(s,'chooseFolder');assert.equal(act(s,'next'),s);s=act(s,'cancelFolder');assert.equal(s.savePath,path);
 s=act(act(s,'chooseFolder'),'useFolder');assert.equal(s.savePath,'D:\\Game Saves\\shattered');assert.equal(s.busy,true);assert.equal(s.readStatus,'loading');assert.equal(s.folderOpen,false);
});
test('failed read stays on path step, remains unknown, permits edit and successful retry',()=>{
 let s=read('readFailure');assert.equal(s.page,'read');assert.equal(s.readStatus,'failed');assert.equal(s.local,null);assert.equal(kind({...s,connected:true}),'unknown');
 s=act(s,'editPath',{value:'D:\\Retry'});s=settle(act(s,'next'));assert.equal(s.page,'connect');assert.equal(s.readStatus,'found');
});
test('empty and identical finish without transfer; differing progress requires deliberate choice',()=>{
 for(const scene of ['empty','identical']){let s=connected(scene);assert.ok(canFinish(s));s=act(s,'finish');assert.equal(s.page,'home');assert.equal(s.uploads+s.restores,0);assert.equal(act(s,'finish'),s);}
 for(const scene of ['local','cloud','conflict']){let s=connected(scene);assert.equal(canFinish(s),false);s=act(s,'choose',{value:scene==='cloud'?'cloud':'local'});assert.ok(canFinish(s));s=settle(act(s,'finish'));assert.equal(s.uploads+s.restores,1);}
 assert.equal(kind(connected('failure')),'unknown');
});
test('game metadata stays consistent through setup/settings and missing metadata stays omitted',()=>{
 for(const id of Object.keys(games)){
  let s=pathStep('empty',id);if(!s.savePath)s=act(s,'editPath',{value:'D:\\Example'});
  s=settle(act(s,'next'));s=act(settle(act(s,'connect')),'next');s=act(act(s,'finish'),'settings');const g=gameLabel(s);
  assert.equal(g.name,games[id].name||'未命名游戏');assert.equal(g.version,games[id].version);assert.equal(g.icon,games[id].icon||'');assert.equal(g.author,'');assert.equal(g.updated,'');
 }
 assert.equal(gameLabel(createState('empty','missing')).name,'未命名游戏');
});
test('restore confirmation and running-game guards remain intact',()=>{
 let s={...connected('conflict'),page:'home'};assert.equal(act(s,'restore'),s);s=act(act(s,'askRestore'),'cancelRestore');assert.equal(s.restores,0);
 s=act(act(s,'askRestore'),'restore');assert.equal(act(s,'back'),s);s=settle(s);assert.equal(s.restores,1);
 const running=act(connected('running'),'finish');assert.equal(act(running,'askRestore'),running);assert.equal(act(running,'upload'),running);
});

test('play total, slots and file count are independent; records-only must sync',()=>{
 const fixture=saveFixture('local');assert.equal(fixture.playCount,28);assert.equal(fixture.slots.length,2);assert.equal(fixture.fileCount,17);
 assert.deepEqual(fixture.slots.map(s=>s.modified),['2026/10/08 21:15','2026/10/09 09:30']);
 assert.equal(saveStatus(read('local')),'游玩 28 次 · 存档 2 个');
 assert.equal(saveStatus(read('recordsOnly')),'游玩 12 次 · 存档 0 个');
 let s=connected('recordsOnly');assert.equal(kind(s),'local');assert.equal(canFinish(s),false);s=act(s,'choose',{value:'local'});s=act(s,'finish');assert.equal(s.uploads,1);
 assert.equal(kind(connected('empty')),'empty');
});
test('debounced checks ignore old path tokens, errors and edits never claim empty',()=>{
 let s=pathStep('local');const old=s.token;s=act(s,'editPath',{value:'D:\\New'});assert.equal(s.preview,null);assert.equal(saveStatus(s),'等待检查…');
 assert.equal(act(s,'preview',{token:old}),s);assert.equal(act(s,'settled',{token:old}),s);
 s=act(s,'preview',{token:s.token});s=settle(s);assert.equal(s.page,'read');assert.equal(s.preview.slots.length,2);
 const failed=read('readFailure');assert.equal(failed.preview,null);assert.equal(saveStatus(failed),'检查失败，请修改路径或重试。');
});

test('connection test succeeds in place, then only a separate next advances',()=>{
 let s=act(read(),'next');assert.equal(act(s,'next'),s);
 s=act(s,'connect');assert.equal(act(s,'connect'),s);assert.equal(act(s,'next'),s);
 s=settle(s);assert.equal(s.page,'connect');assert.equal(s.connectionStatus,'success');
 assert.equal(act(s,'next').page,'compare');
});
test('empty password requires no secret value; every field change invalidates success and late tests',()=>{
 for(const field of ['endpoint','username','password']){
  let s=act(read(),'next');s=settle(act(s,'connect'));assert.equal(s.connectionStatus,'success');
  s=act(s,'editConnection',{field});assert.equal(s.connectionStatus,'untested');assert.equal(act(s,'next'),s);
  s=act(s,'connect');const token=s.token;s=act(s,'editConnection',{field});assert.equal(act(s,'settled',{token}),s);
  s=settle(act(s,'connect'));assert.equal(s.connectionStatus,'success');assert.equal(Object.hasOwn(s,'password'),false);
 }
});
test('failed test, returning and scene reset cannot retain connection authorization',()=>{
 let s=act(read('failure'),'next');s=settle(act(s,'connect'));assert.equal(s.connectionStatus,'failed');assert.equal(act(s,'next'),s);
 s=act(act(read(),'next'),'connect');const token=s.token;s=act(s,'back');assert.equal(act(s,'settled',{token}),s);assert.equal(s.connected,false);
 s=act(settle(act(act(read(),'next'),'connect')),'next');s=act(s,'back');assert.equal(s.page,'connect');assert.equal(act(s,'next'),s);
 assert.equal(act(s,'reset').connectionStatus,'untested');
});


test('cloud-only waits for an explicit choice and finishes once without overwrite warning',()=>{
 let s=connected('cloud');assert.ok(localIsEmpty(s));assert.equal(s.restores,0);assert.equal(act(s,'finish'),s);
 s=act(s,'choose',{value:'cloud'});assert.equal(s.restores,0);s=settle(act(s,'finish'));assert.equal(s.restores,1);assert.equal(s.restoreConfirm,false);
 assert.equal(localIsEmpty(s),false);assert.equal(act(s,'finish'),s);
});
test('confirmed-empty home restore starts directly; repeat click and back cannot duplicate it',()=>{
 let s={...connected('cloud'),page:'home',completed:true};assert.equal(s.restores,0);
 s=act(s,'askRestore');assert.equal(s.pending,'restore');assert.equal(s.restoreConfirm,false);
 assert.equal(act(s,'askRestore'),s);assert.equal(act(s,'back'),s);const token=s.token;
 s=settle(s);assert.equal(s.restores,1);assert.equal(act(s,'settled',{token}),s);
 s=act(s,'askRestore');assert.equal(s.restoreConfirm,false);assert.equal(s.busy,false);
});
test('records-only and occupied slots preserve overwrite warning and confirmation',()=>{
 for(const scene of ['recordsConflict','conflict']){
  let s=connected(scene);assert.equal(kind(s),'conflict');assert.equal(localIsEmpty(s),false);
  s={...s,page:'home',completed:true};assert.equal(act(s,'restore'),s);
  s=act(s,'askRestore');assert.ok(s.restoreConfirm);assert.equal(s.busy,false);
  s=act(s,'cancelRestore');assert.equal(s.restores,0);s=settle(act(act(s,'askRestore'),'restore'));assert.equal(s.restores,1);
 }
});
test('unknown, loading, failed and absent previews never qualify as empty',()=>{
 for(const readStatus of ['unread','loading','failed']){
  const s={...connected('cloud'),readStatus,page:'home'};assert.equal(localIsEmpty(s),false);
  assert.equal(act(s,'askRestore').pending,'');
 }
 assert.equal(localIsEmpty({...connected('cloud'),preview:null}),false);
 let s=act(connected('cloud'),'back');assert.equal(s.restores,0);assert.equal(s.connected,false);
 s=act(settle(act(s,'connect')),'next');assert.equal(s.restores,0);assert.equal(s.choice,'');
});

test('source device labels use identity, preserve remote names, and never invent missing names',()=>{
 assert.equal(deviceLabel({deviceId:'current',deviceName:'Windows-PC'},'current'),'本机');
 assert.equal(deviceLabel({deviceId:'remote',deviceName:'MacBook'},'current'),'MacBook');
 assert.equal(deviceLabel({deviceId:'other',deviceName:'Windows-PC'},'current'),'Windows-PC');
 for(const snapshot of [null,{}, {deviceId:'remote',deviceName:'  '}]) assert.equal(deviceLabel(snapshot,'current'),'未知设备');
 const s=createState('longDevice');assert.ok(deviceLabel(s.cloudSnapshot,s.currentDeviceId).length>40);
});
test('selection and restore keep snapshot source identity on both setup and home',()=>{
 let s=connected('cloud');const remote={...s.cloudSnapshot};s=act(s,'choose',{value:'cloud'});assert.deepEqual(s.cloudSnapshot,remote);
 s=settle(act(s,'finish'));assert.equal(s.localSnapshot.contentId,remote.contentId);assert.equal(s.localSnapshot.deviceId,remote.deviceId);assert.deepEqual(s.localSnapshot.parentIds,[remote.id]);assert.deepEqual(s.cloudSnapshot,remote);assert.equal(deviceLabel(s.localSnapshot,s.currentDeviceId),'MacBook');
 let home={...connected('conflict'),page:'home'};home=settle(act(act(home,'askRestore'),'restore'));assert.equal(home.localSnapshot.contentId,remote.contentId);assert.equal(home.localSnapshot.deviceId,remote.deviceId);
 home=settle(act(home,'upload'));assert.deepEqual(home.cloudSnapshot,remote);
 let local=act(act(connected('local'),'choose',{value:'local'}),'finish');assert.equal(deviceLabel(local.cloudSnapshot,local.currentDeviceId),'本机');
});

test('matching content disables transfers regardless of device name or timestamps',()=>{
 const s={...connected('identical'),page:'home'};assert.ok(isSynced(s));assert.equal(canRestore(s),false);
 assert.equal(act(s,'askRestore'),s);assert.equal(act(s,'upload'),s);
 const different={...s,history:s.history.map(n=>n.id==='save-4'?{...n,contentId:'different'}:n)};
 assert.equal(isSynced(different),false);assert.ok(act(different,'askRestore').restoreConfirm);
 for(const readStatus of ['unread','loading','failed']){const unknown={...s,readStatus};assert.equal(isSynced(unknown),false);assert.equal(canRestore(unknown),false);assert.equal(act(unknown,'askRestore'),unknown);}
 assert.equal(isSynced({...s,localSnapshot:{deviceName:'MacBook'}}),false);
});
test('setup conflict and records-only require the same cancellable confirmation as home',()=>{
 for(const scene of ['conflict','recordsConflict']){
  let s=act(connected(scene),'choose',{value:'cloud'});const original=s.localSnapshot;
  s=act(s,'finish');assert.ok(s.restoreConfirm);assert.equal(s.restores,0);assert.equal(s.busy,false);
  s=act(s,'cancelRestore');assert.deepEqual(s.localSnapshot,original);assert.equal(s.page,'compare');
  s=act(s,'finish');s=act(s,'restore');assert.equal(s.restoreConfirm,false);assert.equal(act(s,'restore'),s);assert.equal(act(s,'finish'),s);
  const token=s.token;s=settle(s);assert.equal(s.restores,1);assert.equal(s.page,'home');assert.ok(isSynced(s));assert.equal(act(s,'settled',{token}),s);
 }
});
test('changed snapshot, selection, page or old confirmation token cannot overwrite',()=>{
 const base=act(act(connected('conflict'),'choose',{value:'cloud'}),'finish');const token=base.restoreConfirm.token;
 for(const changed of [
  {...base,cloudSnapshot:{...base.cloudSnapshot,contentId:'new'}},
  {...base,localSnapshot:{...base.localSnapshot,contentId:'new-local'}},
  {...base,cloudSnapshot:{...base.cloudSnapshot,deviceName:'Changed name'}},
  {...base,choice:'local'}, {...base,readStatus:'failed'}, {...base,connected:false}, {...base,page:'home'}
 ]){const result=act(changed,'restore',{confirmationToken:token});assert.equal(result.restores,0);assert.equal(result.busy,false);assert.equal(result.restoreConfirm,false);}
 let s=act(base,'choose',{value:'local'});assert.equal(s.restoreConfirm,false);assert.equal(act(s,'restore',{confirmationToken:token}),s);
 s=act(act(s,'choose',{value:'cloud'}),'finish');assert.notEqual(s.restoreConfirm.token,token);s=act(s,'restore',{confirmationToken:token});assert.equal(s.busy,false);
 s=act(base,'back');assert.equal(s.restoreConfirm,false);assert.equal(act(s,'restore',{confirmationToken:token}),s);
});

test('default latest uses available cloud publication order, not local save time',()=>{
 let s=createState('history');assert.equal(latestCloud(s).id,'save-4');assert.equal(selectedTarget(s).id,'save-4');
 s={...s,history:s.history.map(n=>n.id==='save-2'?{...n,time:'2099/01/01 00:00'}:n)};assert.equal(selectedTarget(s).id,'save-4');
 assert.equal(s.history.find(n=>n.id==='local-initial').publishedOrder,null);
});
test('explicit old selection overrides synced latest, clearing restores the default',()=>{
 let s={...connected('identical'),page:'home'};assert.ok(isSynced(s));assert.equal(canRestore(s),false);
 s=act(s,'selectSnapshot',{id:'save-2'});assert.equal(selectedTarget(s).id,'save-2');assert.ok(canRestore(s));assert.equal(targetMatchesLocal(s),false);
 s=act(s,'clearSelection');assert.equal(selectedTarget(s).id,'save-4');assert.equal(canRestore(s),false);
});
test('only real parent references produce edges, including cross-device branching',()=>{
 const s=createState('history'),edges=historyEdges(s);assert.deepEqual(edges.filter(e=>e.parent==='device-save-1'&&e.child.startsWith('device-')),[{parent:'device-save-1',child:'device-save-2'},{parent:'device-save-1',child:'device-save-3'}]);
 for(const edge of edges){const child=s.history.find(n=>n.id===edge.child);assert.ok(child.parentIds.includes(edge.parent));assert.ok(s.history.some(n=>n.id===edge.parent));}
 assert.equal(historyEdges({...s,history:[{id:'orphan',parentIds:['missing']}]}).length,0);
});
test('unavailable and local-only nodes never silently fall back to latest',()=>{
 for(const id of ['save-5','local-initial']){let s=act(createState('history'),'selectSnapshot',{id});assert.equal(selectedTarget(s).id,id);assert.equal(canRestore(s),false);assert.equal(act(s,'askRestore'),s);}
});
test('old restore changes local pointer only, leaves immutable history and latest intact',()=>{
 let s=act(createState('history'),'selectSnapshot',{id:'save-2'});const history=JSON.stringify(s.history);s=act(s,'askRestore');s=act(s,'restore');assert.equal(s.pendingRestore.id,'save-2');assert.equal(act(s,'askRestore'),s);
 const token=s.token;s=settle(s);assert.deepEqual(s.localSnapshot.parentIds,['save-2']);assert.equal(s.localSnapshot.storage,'device');assert.equal(s.localSnapshot.ownerDeviceId,s.currentDeviceId);assert.equal(s.localSnapshot.deviceName,'MacBook');assert.equal(JSON.stringify(s.history.slice(0,-1)),history);assert.equal(s.history.at(-1).storage,'device');assert.equal(latestCloud(s).id,'save-4');assert.equal(s.restores,1);assert.equal(act(s,'settled',{token}),s);assert.equal(canRestore(s),false);
 s=act(s,'clearSelection');assert.ok(canRestore(s));assert.equal(selectedTarget(s).id,'save-4');
});
test('selection and selected content changes invalidate confirmation',()=>{
 let s=act(act(createState('history'),'selectSnapshot',{id:'save-2'}),'askRestore');const token=s.restoreConfirm.token;
 let changed=act(s,'selectSnapshot',{id:'save-3'});assert.equal(changed.restoreConfirm,false);assert.equal(act(changed,'restore',{confirmationToken:token}).busy,false);
 changed={...s,history:s.history.map(n=>n.id==='save-2'?{...n,contentId:'changed'}:n)};assert.equal(act(changed,'restore',{confirmationToken:token}).busy,false);
 changed=act(s,'clearSelection');assert.equal(changed.restoreConfirm,false);
});
test('empty local restores selected old cloud without confirmation; unknown source stays unknown',()=>{
 let s={...connected('cloud'),page:'home'};s=act(s,'selectSnapshot',{id:'save-3'});s=act(s,'askRestore');assert.equal(s.restoreConfirm,false);s=settle(s);assert.deepEqual(s.localSnapshot.parentIds,['save-3']);
 s={...s,history:s.history.map(n=>n.id==='save-2'?{...n,deviceName:''}:n)};s=act(s,'selectSnapshot',{id:'save-2'});assert.equal(deviceLabel(selectedTarget(s),s.currentDeviceId),'未知设备');
});
test('explicit upload appends a published snapshot without rewriting history',()=>{
 let s=createState('history');const old=JSON.stringify(s.history);s=settle(act(s,'upload'));assert.equal(s.uploads,1);assert.equal(s.history.length,13);assert.equal(JSON.stringify(s.history.slice(0,-1)),old);assert.equal(latestCloud(s).contentId,s.localSnapshot.contentId);
});

test('cloud copies are distinct from device originals and connected by upload edges',()=>{
 const s=createState('history');const cloud=s.history.filter(n=>n.storage==='cloud');assert.equal(cloud.length,5);
 for(const n of cloud){const original=s.history.find(d=>d.id===n.parentIds[0]);assert.equal(original.storage,'device');assert.equal(original.contentId,n.contentId);assert.equal(original.deviceId,n.deviceId);assert.equal(original.publishedOrder,null);}
 const original=s.history.find(n=>n.id==='device-save-4');const selected=act(s,'selectSnapshot',{id:original.id});assert.equal(canRestore(selected),false);
});
test('upload adds only one cloud copy, restore adds only a current-device copy',()=>{
 let s=createState('history');const cloudBefore=s.history.filter(n=>n.storage==='cloud').length;const source=s.localSnapshot.id;
 s=act(s,'upload');assert.equal(act(s,'upload'),s);s=settle(s);assert.equal(s.history.filter(n=>n.storage==='cloud').length,cloudBefore+1);assert.equal(latestCloud(s).storage,'cloud');assert.deepEqual(latestCloud(s).parentIds,[source]);assert.equal(act(s,'upload'),s);
 s=act(s,'selectSnapshot',{id:'save-2'});const published=JSON.stringify(s.history.filter(n=>n.storage==='cloud'));s=settle(act(act(s,'askRestore'),'restore'));
 assert.equal(JSON.stringify(s.history.filter(n=>n.storage==='cloud')),published);assert.equal(s.localSnapshot.storage,'device');assert.equal(s.localSnapshot.ownerDeviceId,s.currentDeviceId);assert.deepEqual(s.localSnapshot.parentIds,['save-2']);assert.equal(canRestore(s),false);
});

test('one shared save-time axis sorts interleaved devices independently of publication order',()=>{
 const s={history:[
  {id:'a',time:'2026/10/08 12:00',storage:'device',deviceId:'a',order:99},
  {id:'b',time:'2026/10/09 08:00',storage:'cloud',publishedOrder:1},
  {id:'c',time:'2026/10/08 15:00',storage:'device',deviceId:'c',order:1}
 ]};assert.deepEqual(historyRows(s).flat().map(n=>n.id),['b','c','a']);
});
test('equal save times have stable IDs and never overlap within a column',()=>{
 const s=createState('history');const a=historyRows(s);const b=historyRows({...s,history:[...s.history].reverse()});assert.deepEqual(a,b);
 for(const row of a){assert.equal(new Set(row.map(historyColumnId)).size,row.length);assert.equal(new Set(row.map(n=>n.time)).size,1);}
 const duplicates={history:[{id:'b',time:'2026/10/09 08:00',storage:'cloud'},{id:'a',time:'2026/10/09 08:00',storage:'cloud'}]};assert.deepEqual(historyRows(duplicates).map(r=>r[0].id),['a','b']);
});
test('upload and restore keep displayed save time and selection while timeline reorders',()=>{
 let s=createState('history');s=settle(act(s,'upload'));s=act(s,'selectSnapshot',{id:'save-2'});s=settle(act(act(s,'askRestore'),'restore'));
 assert.equal(s.localSnapshot.time,'2026/10/08 09:00');assert.equal(selectedTarget(s).id,'save-2');assert.equal(latestCloud(s).time,'2026/10/09 10:00');
 const rows=historyRows(s);const times=rows.map(r=>r[0].time);assert.deepEqual(times,[...times].sort().reverse());
 assert.ok(rows.findIndex(r=>r.some(n=>n.id===s.localSnapshot.id))>0);
 for(const edge of historyEdges(s)){assert.ok(rows.some(r=>r.some(n=>n.id===edge.parent)));assert.ok(rows.some(r=>r.some(n=>n.id===edge.child)));}
});

test('two-column visibility retains every cloud source and only current-device local copies',()=>{
 const s=createState('history'),visible=visibleHistory(s);
 assert.deepEqual(visible.filter(n=>n.storage==='cloud'),s.history.filter(n=>n.storage==='cloud'));
 assert.ok(visible.some(n=>n.storage==='cloud'&&n.deviceName==='MacBook'));assert.ok(visible.some(n=>n.storage==='cloud'&&n.deviceName==='Pixel Tablet'));
 assert.ok(visible.filter(n=>n.storage==='device').every(n=>n.ownerDeviceId===s.currentDeviceId));
 assert.equal(visible.some(n=>n.id==='device-save-2'),false);
 const view={...s,history:visible};const rows=historyRows(view);assert.ok(rows.every(r=>r.length>0));
 assert.deepEqual(new Set(rows.flat().map(historyColumnId)),new Set(['cloud-column','demo-current']));
 for(const e of historyEdges(view)){assert.ok(visible.some(n=>n.id===e.parent));assert.ok(visible.some(n=>n.id===e.child));}
});
test('upload and another-device restore retain visible transfer edges and sorted rows',()=>{
 let s=settle(act(createState('history'),'upload'));let view={...s,history:visibleHistory(s)};const upload=latestCloud(s);
 assert.ok(historyEdges(view).some(e=>e.parent==='local-initial'&&e.child===upload.id));
 s=act(s,'selectSnapshot',{id:'save-3'});s=settle(act(act(s,'askRestore'),'restore'));assert.equal(s.localSnapshot.deviceName,'Pixel Tablet');
 view={...s,history:visibleHistory(s)};assert.ok(view.history.some(n=>n.id===s.localSnapshot.id));assert.ok(historyEdges(view).some(e=>e.parent==='save-3'&&e.child===s.localSnapshot.id));
 assert.equal(historyColumnId(s.localSnapshot),s.currentDeviceId);assert.equal(selectedTarget(s).id,'save-3');
 const times=historyRows(view).map(r=>r[0].time);assert.deepEqual(times,[...times].sort().reverse());
 assert.equal(view.history.filter(n=>n.storage==='cloud').length,6);
});

test('current local has three states and old restored content matches its cloud copy',()=>{
 assert.equal(localState(connected('empty')),'empty');assert.equal(localState(createState('history')),'unsynced');assert.equal(localState(connected('identical')),'synced');
 assert.equal(localState(connected('recordsOnly')),'unsynced');
 let s=act(createState('history'),'selectSnapshot',{id:'save-2'});s=settle(act(act(s,'askRestore'),'restore'));assert.equal(localState(s),'synced');assert.equal(matchingCloud(s).id,'save-2');assert.equal(visibleHistory(s).filter(n=>n.storage==='device').length,1);assert.ok(displayEdges(s).some(e=>e.parent==='save-2'&&e.child===s.localSnapshot.id&&e.kind==='synced'));
});
test('unsynced baseline is real; new local and empty local invent no cloud edge',()=>{
 const s=createState('history');assert.ok(displayEdges(s).some(e=>e.parent==='local-baseline'&&e.child==='local-initial'&&e.kind==='unsynced'));
 assert.equal(displayEdges(connected('local')).length,0);assert.equal(displayEdges(connected('empty')).length,0);
 const uploaded=settle(act(s,'upload'));assert.equal(localState(uploaded),'synced');assert.ok(displayEdges(uploaded).some(e=>e.parent===uploaded.localSnapshot.id&&e.child===latestCloud(uploaded).id&&e.kind==='synced'));
});
test('cloud branches use explicit cloud parents, not time adjacency',()=>{
 const edges=displayEdges(createState('history')).filter(e=>e.kind==='cloud');assert.deepEqual(edges.filter(e=>e.parent==='save-1').map(e=>e.child),['save-2','save-3']);assert.equal(edges.some(e=>e.parent==='save-3'&&e.child==='save-4'),false);
});

test('rename changes display metadata only, supports cancel, duplicate names and plain special text',()=>{
 let s=createState('history');const original=s.history.find(n=>n.id==='save-2');s=act(s,'beginRename',{id:'save-2'});s=act(s,'cancelRename');assert.deepEqual(s.history.find(n=>n.id==='save-2'),original);
 for(const id of ['save-2','local-initial']){s=act(s,'beginRename',{id});s=act(s,'rename',{id,value:' <img src=x onerror=alert(1)> & "冒险" '});assert.equal(snapshotName(s.history.find(n=>n.id===id)),'<img src=x onerror=alert(1)> & "冒险"');}
 const renamed=s.history.find(n=>n.id==='save-2');const {displayName,...content}=renamed;assert.deepEqual(content,original);assert.equal(localState(s),'unsynced');assert.equal(latestCloud(s).id,'save-4');
 s=act(s,'selectSnapshot',{id:'save-2'});assert.equal(selectedTarget(s).id,'save-2');
 s=act(s,'beginRename',{id:'save-2'});s=act(s,'rename',{id:'save-2',value:'  '});assert.equal(snapshotName(s.history.find(n=>n.id==='save-2')),'云端存档');
 s=act(s,'beginRename',{id:'save-2'});s=act(s,'rename',{id:'save-2',value:'名'.repeat(60)});assert.equal(Array.from(snapshotName(s.history.find(n=>n.id==='save-2'))).length,40);
});
test('rename a matching snapshot does not change synced status or ancestry',()=>{
 let s=settle(act(createState('history'),'upload'));const edges=displayEdges(s);const cloud=latestCloud(s);s=act(s,'beginRename',{id:cloud.id});s=act(s,'rename',{id:cloud.id,value:'昨天的探索'});assert.equal(localState(s),'synced');assert.deepEqual(displayEdges(s),edges);assert.equal(latestCloud(s).contentId,cloud.contentId);assert.equal(latestCloud(s).time,cloud.time);
});

test('local history has exactly zero, one or baseline plus yellow current nodes',()=>{
 const locals=s=>visibleHistory(s).filter(n=>n.storage==='device');
 assert.equal(locals(connected('empty')).length,0);
 assert.equal(locals(connected('local')).length,1);
 let s=createState('history');assert.deepEqual(locals(s).map(n=>n.id),['local-baseline','local-initial']);
 assert.ok(displayEdges(s).some(e=>e.parent==='save-2'&&e.child==='local-baseline'&&e.kind==='synced'));
 assert.ok(displayEdges(s).some(e=>e.parent==='local-baseline'&&e.child==='local-initial'&&e.kind==='unsynced'));
 s=settle(act(s,'upload'));assert.equal(locals(s).length,1);assert.equal(localState(s),'synced');
 for(const id of ['save-3','save-2']){s=act(s,'selectSnapshot',{id});s=settle(act(act(s,'askRestore'),'restore'));assert.equal(locals(s).length,1);assert.ok(displayEdges(s).some(e=>e.parent===id&&e.child===s.localSnapshot.id&&e.kind==='synced'));}
 const baseline=s.localSnapshot;const current={...baseline,id:'new-local',contentId:'new-content',parentIds:[baseline.id]};s={...s,localSnapshot:current,history:[...s.history,current]};
 assert.deepEqual(locals(s).map(n=>n.id),[baseline.id,current.id]);assert.ok(displayEdges(s).some(e=>e.parent===baseline.id&&e.child===current.id));
});

test('transfer arrows follow explicit upload and restore provenance, including old versions',()=>{
 let s=settle(act(createState('history'),'upload'));let cloud=latestCloud(s);
 assert.deepEqual(displayEdges(s).filter(e=>e.kind==='synced'),[{parent:s.localSnapshot.id,child:cloud.id,kind:'synced'}]);
 s=act(s,'selectSnapshot',{id:'save-2'});s=settle(act(act(s,'askRestore'),'restore'));
 assert.deepEqual(displayEdges(s).filter(e=>e.kind==='synced'),[{parent:'save-2',child:s.localSnapshot.id,kind:'synced'}]);
 const baseline=s.localSnapshot,current={...baseline,id:'changed',contentId:'changed-content',parentIds:[baseline.id]};
 s={...s,localSnapshot:current,history:[...s.history,current]};
 assert.ok(displayEdges(s).some(e=>e.parent===baseline.id&&e.child==='changed'&&e.kind==='unsynced'));
 const fixture=createState('history');assert.ok(displayEdges(fixture).some(e=>e.parent==='save-1'&&e.child==='save-3'&&e.kind==='cloud'));
});

test('default, explicit latest and old restores retain exact target through confirmation',()=>{
 for(const id of [null,'save-4','save-2']){
  let s=createState('history');if(id)s=act(s,'selectSnapshot',{id});const target=selectedTarget(s);
  s=act(s,'askRestore');assert.ok(s.restoreConfirm);const history=JSON.stringify(s.history);
  let cancelled=act(s,'cancelRestore');assert.equal(cancelled.restores,0);assert.equal(JSON.stringify(cancelled.history),history);
  s=act(cancelled,'askRestore');s=act(s,'restore');assert.equal(s.pendingRestore.id,target.id);assert.equal(act(s,'restore'),s);
  s=settle(s);assert.equal(s.localSnapshot.contentId,target.contentId);assert.deepEqual(s.localSnapshot.parentIds,[target.id]);assert.equal(s.restores,1);assert.equal(canRestore(s),false);assert.equal(act(s,'askRestore'),s);
 }
});
test('renamed latest and uploaded cloud copies retain identity and safety checks',()=>{
 let s=createState('history');s=act(s,'beginRename',{id:'save-4'});s=act(s,'rename',{id:'save-4',value:'命名后的最新存档'});s=act(s,'selectSnapshot',{id:'save-4'});
 assert.equal(canRestore(s),true);s=settle(act(act(s,'askRestore'),'restore'));assert.equal(s.localSnapshot.contentId,'demo-cloud-v2');
 s=createState('history');s=settle(act(s,'upload'));const uploaded=latestCloud(s);s=act(s,'selectSnapshot',{id:uploaded.id});assert.equal(canRestore(s),false);assert.equal(act(s,'askRestore'),s);
 s=act(s,'selectSnapshot',{id:'save-2'});s=settle(act(act(s,'askRestore'),'restore'));s=act(s,'selectSnapshot',{id:uploaded.id});assert.equal(canRestore(s),true);
 s=settle(act(act(s,'askRestore'),'restore'));assert.equal(s.localSnapshot.contentId,uploaded.contentId);assert.deepEqual(s.localSnapshot.parentIds,[uploaded.id]);
 const bad=act(createState('history'),'selectSnapshot',{id:'save-5'});assert.equal(selectedTarget(bad).available,false);assert.equal(canRestore(bad),false);assert.equal(act(bad,'askRestore'),bad);
});
