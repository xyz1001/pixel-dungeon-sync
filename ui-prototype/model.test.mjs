import test from 'node:test';
import assert from 'node:assert/strict';
import {createState, reduce, kind, canFinish, scenes, games, gameLabel} from './model.mjs';
function read(scene) { let s = reduce(createState(scene), {type:'next'}); return reduce(s,{type:'settled',token:s.token}); }
function connected(scene) { let s = reduce(read(scene), {type:'next'}); s = reduce(s,{type:'connect'}); return reduce(s,{type:'settled',token:s.token}); }
test('empty completion does not simulate upload or restore; repeated finish is ignored', () => {
  const s=connected('empty'); assert.equal(kind(s),'empty'); assert.ok(canFinish(s));
  const done=reduce(s,{type:'finish'}); assert.equal(done.page,'home'); assert.equal(done.uploads+done.restores,0);
  assert.equal(reduce(done,{type:'finish'}),done);
});
test('different and single-sided progress require a deliberate valid selection', () => {
  for (const scene of ['local','cloud','conflict']) {
    let s=connected(scene); assert.equal(canFinish(s),false); assert.equal(reduce(s,{type:'finish'}),s);
    s=reduce(s,{type:'choose',value:scene==='cloud'?'cloud':'local'}); assert.ok(canFinish(s));
    const done=reduce(s,{type:'finish'}); assert.equal(done.uploads+done.restores,1);
  }
  assert.equal(canFinish(connected('identical')),true);
});
test('cancel connect invalidates late completion and allows retry', () => {
  let s=reduce(read('empty'),{type:'next'}); s=reduce(s,{type:'connect'}); const token=s.token;
  assert.equal(reduce(s,{type:'connect'}),s); s=reduce(s,{type:'back'}); assert.equal(s.page,'read');
  assert.equal(reduce(s,{type:'settled',token}),s);
});
test('selection never reads saves; next starts loading and does not call an unknown result empty', () => {
  let s=createState('empty'); assert.equal(s.readStatus,'unread'); assert.equal(s.local,null);
  s=reduce(s,{type:'chooseGame'}); assert.equal(s.readStatus,'unread'); assert.equal(s.page,'game');
  s=reduce(s,{type:'next'}); assert.equal(s.page,'read'); assert.equal(s.readStatus,'loading');
  assert.equal(kind({...s,connected:true}),'unknown'); assert.equal(reduce(s,{type:'next'}),s);
  s=reduce(s,{type:'settled',token:s.token}); assert.equal(s.readStatus,'empty'); assert.equal(s.local,false);
  s=reduce(s,{type:'next'}); assert.equal(s.page,'connect');
});
test('read failure is unknown and retry can find saves without entering the connection page early', () => {
  let s=read('readFailure'); assert.equal(s.readStatus,'failed'); assert.equal(s.local,null);
  assert.equal(reduce(s,{type:'next'}),s); assert.equal(kind({...s,connected:true}),'unknown');
  s=reduce(s,{type:'retryRead'}); assert.equal(s.readStatus,'loading'); assert.equal(reduce(s,{type:'retryRead'}),s);
  s=reduce(s,{type:'settled',token:s.token}); assert.equal(s.readStatus,'found'); assert.equal(s.local,true);
});
test('back and changing game invalidate completed reads and late read callbacks', () => {
  let s=reduce(createState('local'),{type:'next'}); const oldToken=s.token;
  s=reduce(s,{type:'back'}); assert.equal(s.page,'game'); assert.equal(s.readStatus,'unread'); assert.equal(s.local,null);
  assert.equal(reduce(s,{type:'settled',token:oldToken}),s);
  s=reduce(s,{type:'chooseGame'}); s=reduce(s,{type:'next'}); s=reduce(s,{type:'settled',token:s.token}); assert.equal(s.readStatus,'found');
  s=reduce(s,{type:'next'}); s=reduce(s,{type:'back'}); assert.equal(s.page,'read'); assert.equal(s.readStatus,'found');
  s=reduce(s,{type:'back'}); assert.equal(s.readStatus,'unread'); assert.equal(s.local,null); assert.equal(s.connected,false);
});
test('changing game from settings restarts the four-step setup with no stale read', () => {
  let s=reduce(connected('identical'),{type:'finish'}); s=reduce(s,{type:'settings'}); s=reduce(s,{type:'chooseGame'});
  assert.equal(s.page,'game'); assert.equal(s.completed,false); assert.equal(s.readStatus,'unread'); assert.equal(s.local,null);
  assert.equal(canFinish(s),false);
});
test('connection failure never becomes empty or completed', () => {
  const s=connected('failure'); assert.equal(s.page,'connect'); assert.equal(kind(s),'unknown'); assert.equal(canFinish(s),false); assert.ok(s.message);
});
test('home restore requires in-page confirmation and is disabled while game runs', () => {
  let s=reduce(connected('identical'),{type:'finish'}); assert.equal(reduce(s,{type:'restore'}),s);
  s=reduce(s,{type:'askRestore'}); s=reduce(s,{type:'cancelRestore'}); assert.equal(s.restores,0);
  s=reduce(s,{type:'askRestore'}); s=reduce(s,{type:'restore'}); assert.equal(reduce(s,{type:'restore'}),s);
  assert.equal(reduce(s,{type:'back'}),s); s=reduce(s,{type:'settled',token:s.token}); assert.equal(s.restores,1);
  const running=reduce(connected('running'),{type:'finish'}); assert.equal(reduce(running,{type:'askRestore'}),running); assert.equal(reduce(running,{type:'upload'}),running);
});
test('upload, settings, back and all scenarios remain navigable', () => {
  let s=reduce(connected('identical'),{type:'finish'}); s=reduce(s,{type:'upload'}); s=reduce(s,{type:'settled',token:s.token}); assert.equal(s.uploads,1);
  s=reduce(s,{type:'settings'}); s=reduce(s,{type:'connectionSettings'}); assert.equal(s.page,'connect'); s=reduce(s,{type:'back'}); assert.equal(s.page,'settings'); s=reduce(s,{type:'back'}); assert.equal(s.page,'home');
  for (const scene of Object.keys(scenes)) {const next=reduce(s,{type:'reset',scene}); assert.equal(next.scene,scene); assert.equal(next.page,'game'); assert.equal(next.completed,false);}
});

test('each English game identity persists through all steps, home, settings and back', () => {
  for (const gameId of Object.keys(games)) {
    let s=reduce(createState(),{type:'chooseGame',gameId});
    const label=gameLabel(s);
    assert.equal(label.name,games[gameId].name || '未命名游戏');
    s=reduce(s,{type:'next'}); s=reduce(s,{type:'settled',token:s.token});
    s=reduce(s,{type:'next'}); s=reduce(s,{type:'back'});
    assert.equal(s.page,'read'); assert.deepEqual(gameLabel(s),label);
    s=reduce(s,{type:'next'}); s=reduce(s,{type:'connect'}); s=reduce(s,{type:'settled',token:s.token});
    s=reduce(s,{type:'finish'}); s=reduce(s,{type:'settings'});
    assert.deepEqual(gameLabel(s),label);
    s=reduce(s,{type:'chooseGame',gameId:'cruel'});
    assert.equal(s.gameId,'cruel'); assert.equal(s.readStatus,'unread'); assert.equal(s.local,null);
    assert.equal(s.connected,false); assert.equal(s.completed,false); assert.equal(s.choice,'');
  }
});
test('switching game invalidates previous reads and unknown names have a short fallback', () => {
  let s=reduce(createState('local'),{type:'next'}); const token=s.token;
  s=reduce(s,{type:'back'}); s=reduce(s,{type:'chooseGame',gameId:'experienced'});
  assert.equal(reduce(s,{type:'settled',token}),s);
  assert.equal(gameLabel(s).name,'Experienced Pixel Dungeon'); assert.equal(s.readStatus,'unread');
  assert.equal(gameLabel(createState('empty','missing')).name,'未命名游戏');
  assert.equal(reduce(s,{type:'reset',scene:'cloud'}).gameId,'experienced');
});
