import test from 'node:test';
import assert from 'node:assert/strict';
import {createState, reduce, kind, canFinish, games, gameLabel, defaultSavePaths, saveFixture, saveStatus, localIsEmpty, deviceLabel, isSynced, canRestore} from './model.mjs';
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
 s=settle(act(s,'finish'));assert.deepEqual(s.localSnapshot,remote);assert.deepEqual(s.cloudSnapshot,remote);assert.equal(deviceLabel(s.localSnapshot,s.currentDeviceId),'MacBook');
 let home={...connected('conflict'),page:'home'};home=settle(act(act(home,'askRestore'),'restore'));assert.deepEqual(home.localSnapshot,remote);
 home=settle(act(home,'upload'));assert.deepEqual(home.cloudSnapshot,remote);
 let local=act(act(connected('local'),'choose',{value:'local'}),'finish');assert.equal(deviceLabel(local.cloudSnapshot,local.currentDeviceId),'本机');
});

test('matching content disables transfers regardless of device name or timestamps',()=>{
 const s={...connected('identical'),page:'home'};assert.ok(isSynced(s));assert.equal(canRestore(s),false);
 assert.equal(act(s,'askRestore'),s);assert.equal(act(s,'upload'),s);
 const different={...s,cloudSnapshot:{...s.localSnapshot,contentId:'different'}};
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
