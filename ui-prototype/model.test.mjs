import test from 'node:test';
import assert from 'node:assert/strict';
import {createState, reduce, kind, canFinish, scenes} from './model.mjs';
function connected(scene) { let s = reduce(createState(scene), {type:'next'}); s = reduce(s,{type:'connect'}); return reduce(s,{type:'settled',token:s.token}); }
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
  let s=reduce(createState(),{type:'next'}); s=reduce(s,{type:'connect'}); const token=s.token;
  assert.equal(reduce(s,{type:'connect'}),s); s=reduce(s,{type:'back'}); assert.equal(s.page,'game');
  assert.equal(reduce(s,{type:'settled',token}),s);
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
