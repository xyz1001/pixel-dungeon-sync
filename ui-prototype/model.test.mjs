import test from 'node:test';
import assert from 'node:assert/strict';
import {createState, reduce, kind, canFinish, games, gameLabel, defaultSavePaths} from './model.mjs';
const act=(s,type,extra={})=>reduce(s,{type,...extra});
const settle=s=>act(s,'settled',{token:s.token});
const pathStep=(scene='empty',id='shattered')=>act(createState(scene,id),'next');
const read=(scene='empty',id='shattered')=>settle(act(pathStep(scene,id),'next'));
const connected=(scene='empty',id='shattered')=>settle(act(read(scene,id),'connect'));
test('four exact Windows defaults preserve separator, leading dot, case and spaces',()=>{
 const tails={shattered:'.shatteredpixel\\Shattered Pixel Dungeon',experienced:'.trashboxbobylev\\Experienced Pixel Dungeon',cruel:'.cruelpixel\\Too Cruel Pixel Dungeon',rearranged:'.rearrangedpixel\\Rearranged Pixel Dungeon'};
 for(const [id,tail] of Object.entries(tails)) assert.equal(defaultSavePaths[id],'C:\\Users\\Demo\\AppData\\Roaming\\'+tail);
});
test('entering path step never reads; blank path cannot advance; confirmation starts validation',()=>{
 let s=pathStep();assert.equal(s.page,'read');assert.equal(s.busy,false);assert.equal(s.readStatus,'unread');assert.equal(s.local,null);
 s=act(s,'editPath',{value:'  '});assert.equal(act(s,'next'),s);
 s=act(s,'editPath',{value:'D:\\Saves'});s=act(s,'next');assert.equal(s.readStatus,'loading');assert.equal(kind({...s,connected:true}),'unknown');
 assert.equal(act(s,'next'),s);s=settle(s);assert.equal(s.page,'connect');assert.equal(s.readStatus,'empty');
});
test('edited path survives same-game back/forward and connection cancellation',()=>{
 let s=act(pathStep(),'editPath',{value:'D:\\My Saves'});s=act(s,'back');s=act(s,'chooseGame');s=act(s,'next');assert.equal(s.savePath,'D:\\My Saves');assert.equal(s.busy,false);
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
 let s=pathStep();const path=s.savePath;s=act(s,'chooseFolder');assert.equal(act(s,'next'),s);s=act(s,'cancelFolder');assert.equal(s.savePath,path);
 s=act(act(s,'chooseFolder'),'useFolder');assert.equal(s.savePath,'D:\\Game Saves\\shattered');assert.equal(s.busy,false);assert.equal(s.readStatus,'unread');assert.equal(s.folderOpen,false);
});
test('failed read stays on path step, remains unknown, permits edit and successful retry',()=>{
 let s=read('readFailure');assert.equal(s.page,'read');assert.equal(s.readStatus,'failed');assert.equal(s.local,null);assert.equal(kind({...s,connected:true}),'unknown');
 s=act(s,'editPath',{value:'D:\\Retry'});s=settle(act(s,'next'));assert.equal(s.page,'connect');assert.equal(s.readStatus,'found');
});
test('empty and identical finish without transfer; differing progress requires deliberate choice',()=>{
 for(const scene of ['empty','identical']){let s=connected(scene);assert.ok(canFinish(s));s=act(s,'finish');assert.equal(s.page,'home');assert.equal(s.uploads+s.restores,0);assert.equal(act(s,'finish'),s);}
 for(const scene of ['local','cloud','conflict']){let s=connected(scene);assert.equal(canFinish(s),false);s=act(s,'choose',{value:scene==='cloud'?'cloud':'local'});assert.ok(canFinish(s));s=act(s,'finish');assert.equal(s.uploads+s.restores,1);}
 assert.equal(kind(connected('failure')),'unknown');
});
test('game metadata stays consistent through setup/settings and missing metadata stays omitted',()=>{
 for(const id of Object.keys(games)){
  let s=pathStep('empty',id);if(!s.savePath)s=act(s,'editPath',{value:'D:\\Example'});
  s=settle(act(s,'next'));s=settle(act(s,'connect'));s=act(act(s,'finish'),'settings');const g=gameLabel(s);
  assert.equal(g.name,games[id].name||'未命名游戏');assert.equal(g.version,games[id].version);assert.equal(g.icon,games[id].icon||'');assert.equal(g.author,'');assert.equal(g.updated,'');
 }
 assert.equal(gameLabel(createState('empty','missing')).name,'未命名游戏');
});
test('restore confirmation and running-game guards remain intact',()=>{
 let s=act(connected('identical'),'finish');assert.equal(act(s,'restore'),s);s=act(act(s,'askRestore'),'cancelRestore');assert.equal(s.restores,0);
 s=act(act(s,'askRestore'),'restore');assert.equal(act(s,'back'),s);s=settle(s);assert.equal(s.restores,1);
 const running=act(connected('running'),'finish');assert.equal(act(running,'askRestore'),running);assert.equal(act(running,'upload'),running);
});
