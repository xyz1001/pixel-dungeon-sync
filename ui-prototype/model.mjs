// Fixed fixtures only: names mirror observed Specification-Title values, not compatibility claims.
export const games = {
  shattered: {icon: '.local-icons/shattered.png', name: 'Shattered Pixel Dungeon', version: '3.3.8'},
  experienced: {icon: '.local-icons/experienced.png', name: 'Experienced Pixel Dungeon', version: 'ExpPD-2.19.0'},
  cruel: {icon: '.local-icons/cruel.png', name: 'Too Cruel Pixel Dungeon', version: '1.0.4'},
  rearranged: {icon: '.local-icons/rearranged.png', name: 'Rearranged Pixel Dungeon', version: '3.2.0_based_3.40.0_FINAL'},
  unnamed: {name: '', version: ''}
};
export const defaultSavePaths = {
  shattered: String.raw`C:\Users\Demo\AppData\Roaming\.shatteredpixel\Shattered Pixel Dungeon`,
  experienced: String.raw`C:\Users\Demo\AppData\Roaming\.trashboxbobylev\Experienced Pixel Dungeon`,
  cruel: String.raw`C:\Users\Demo\AppData\Roaming\.cruelpixel\Too Cruel Pixel Dungeon`,
  rearranged: String.raw`C:\Users\Demo\AppData\Roaming\.rearrangedpixel\Rearranged Pixel Dungeon`
};
export function gameLabel(s) {
  const game = games[s.gameId];
  return {name: game?.name || '未命名游戏', version: game?.version || '', icon: game?.icon || '', author: game?.author || '', updated: game?.updated || ''};
}
export const scenes = {
  splitUpdates:'远端改 A，本机改 B · 两槽合并', sameRun:'同一局双端改动 · 拆成两槽', sameChanges:'两端相同改动 · 去重', mergeFull:'槽位不足 · 合并失败', missingBase:'无共同基线 · 合并失败', missingIdentity:'单局身份不明 · 合并失败', globalConflict:'全局记录冲突 · 合并失败', mergeFailure:'合并写入失败 · 原内容不变',
  history: '多设备存档历史', remoteUpdate: '远端更新，本机无改动', empty: '无存档，无游戏记录', recordsOnly: '无存档，有游戏记录', recordsConflict: '本机仅记录，云端有存档', local: '本机多槽存档', cloud: '只有云端存档', longDevice: '云端设备长名称', conflict: '双方进度不同',
  identical: '双方进度相同', failure: '连接失败', connecting: '连接中', running: '游戏运行中',
  reading: '读取中', readFailure: '读取失败（重试可恢复）'
};
// Fixed local modification times, not scan timestamps. Slot count is independent of file count.
export function saveFixture(scene) {
  const slots = ['history', 'remoteUpdate', 'local', 'conflict', 'identical', 'running', 'readFailure'].includes(scene)
    ? [{slot: 1, modified: '2026/10/08 21:15'}, {slot: 3, modified: '2026/10/09 09:30'}] : [];
  return {playCount: slots.length ? 28 : ['recordsOnly', 'recordsConflict'].includes(scene) ? 12 : 0, slots, records: slots.length > 0 || ['recordsOnly', 'recordsConflict'].includes(scene), fileCount: slots.length ? 17 : ['recordsOnly', 'recordsConflict'].includes(scene) ? 3 : 0};
}
export function saveStatus(s) {
  if (s.readStatus === 'loading') return '正在检查存档…';
  if (s.readStatus === 'failed') return '检查失败，请修改路径或重试。';
  if (!s.preview) return s.savePath.trim() ? '等待检查…' : '请设置存档路径';
  return `游玩 ${s.preview.playCount} 次 · 存档 ${s.preview.slots.length} 个`;
}
export function createState(scene = 'empty', gameId = 'shattered') {
  if(['splitUpdates','sameRun','sameChanges','mergeFull','missingBase','missingIdentity','globalConflict','mergeFailure'].includes(scene))return mergeFixture({...createState('history',gameId),scene});
  const fixtureLocal = saveFixture(scene).records;
  const cloud = ['history', 'remoteUpdate', 'cloud', 'longDevice', 'recordsConflict', 'conflict', 'identical', 'running'].includes(scene);
  const state = {scene, gameId, currentDeviceId: 'demo-current', localSnapshot: fixtureLocal ? {deviceId: 'demo-current', deviceName: 'Windows-PC', contentId: 'demo-local-v1'} : null, cloudSnapshot: cloud ? {contentId: ['identical', 'running'].includes(scene) ? 'demo-local-v1' : 'demo-cloud-v2', deviceId: 'demo-remote', deviceName: scene === 'longDevice' ? 'MacBook-Example-Very-Long-Device-Name-用于窄屏展示的虚构设备名称' : 'MacBook'} : null, savePath: defaultSavePaths[gameId] || '', folderOpen: false, page: 'game', selectedGame: true, fixtureLocal, preview: null, continueAfterRead: false, local: null, readStatus: 'unread', readAttempts: 0, cloud,
    connectionStatus: 'untested', configRevision: 0, testedRevision: null, connected: false, choice: '', busy: false, pending: '', token: 0, message: '', restoreConfirm: false,
    completed: false, returnPage: 'home', uploads: 0, restores: 0, selectedSnapshotId: null, pendingRestore: null, renameId: null, syncedBaseline: null, merges:0,pendingMerge:null,mergeResult:null};
  state.localSnapshot = state.localSnapshot && {...state.localSnapshot, id: 'local-initial', storage:'device', ownerDeviceId:'demo-current', time: '2026/10/09 10:00', order: 6, slots: saveFixture(scene).slots.length, plays: saveFixture(scene).playCount, parentIds: cloud ? ['device-save-2'] : [], available: true, publishedOrder: null};
  state.history = cloud ? historyFixture(state.cloudSnapshot) : [];
  state.cloudSnapshot = cloud ? {...state.history.find(n => n.id === 'save-4')} : null;
  if (state.localSnapshot) {
    if(cloud && state.localSnapshot.contentId===state.cloudSnapshot.contentId)state.localSnapshot.parentIds=[state.cloudSnapshot.id];
    if(cloud && state.localSnapshot.contentId!==state.cloudSnapshot.contentId){
      const source=state.history.find(n=>n.id==='save-2');
      const baseline={...source,id:'local-baseline',storage:'device',ownerDeviceId:state.currentDeviceId,publishedOrder:null,parentIds:[source.id]};
      state.history.push(baseline);state.localSnapshot.parentIds=[baseline.id];
    }
    state.history.push({...state.localSnapshot});
  }
  state.devices = [{deviceId:'demo-current',deviceName:'Windows-PC'}, {deviceId:'demo-remote',deviceName:state.cloudSnapshot?.deviceName || 'MacBook'}, {deviceId:'demo-tablet',deviceName:'Pixel Tablet'}];
  if(scene==='remoteUpdate'){
    const cloudBase=state.history.find(n=>n.id==='save-2');
    state.localSnapshot={...cloudBase,id:'local-initial',storage:'device',ownerDeviceId:state.currentDeviceId,publishedOrder:null,parentIds:[cloudBase.id]};
    state.history=state.history.filter(n=>n.id!=='local-initial'&&n.id!=='local-baseline');state.history.push(state.localSnapshot);
    state.syncedBaseline={cloudId:cloudBase.id,contentId:cloudBase.contentId};
  }
  if (['history','remoteUpdate'].includes(scene)) Object.assign(state, {page:'home',completed:true,connected:true,connectionStatus:'success',testedRevision:0,local:true,readStatus:'found',preview:saveFixture(scene)});
  return mergeFixture(state);
}
// Synthetic, fully parsed single-run data. No real game file parsing or rewriting.
function mergeFixture(s) {
  const run=(id,hash,slot)=>({runUUID:id,contentHash:hash,slot,name:`冒险 ${id.replace('run-','')}`});
  let base=[run('run-A','A0',1),run('run-B','B0',2)],local=[run('run-A','A0',1),run('run-B','B1',2)],remote=[run('run-A','A1',1),run('run-B','B0',2)];
  if(['sameRun','mergeFull'].includes(s.scene)){
    base=Array.from({length:s.scene==='mergeFull'?6:1},(_,i)=>run(`run-${i+1}`,`base-${i}`,i+1));
    local=base.map((r,i)=>({...r,contentHash:i===0?'local-change':r.contentHash}));remote=base.map((r,i)=>({...r,contentHash:i===0?'remote-change':r.contentHash}));
  }
  if(s.scene==='sameChanges'){local=[run('run-A','A1',1),run('run-B','B1',2)];remote=[run('run-A','A1',1),run('run-B','B0',2)];}
  if(s.scene==='missingIdentity')local=local.map(r=>({...r,runUUID:null}));
  const profile=(runs,records='records-0')=>({format:'shattered-3.3.8-demo',runs,globals:{records,playCount:records==='local-records'?28:records==='remote-records'?24:22}});
  const baseProfile=profile(base),remoteProfile=profile(remote,s.scene==='globalConflict'?'remote-records':'records-0');
  const localProfile=s.localSnapshot?.contentId===s.cloudSnapshot?.contentId?remoteProfile:s.scene==='remoteUpdate'?baseProfile:profile(local,s.scene==='globalConflict'?'local-records':'records-0');
  const history=s.history.map(n=>({...n,profile:n.id==='save-2'?baseProfile:n.id===s.localSnapshot?.id?localProfile:n.id==='save-4'?remoteProfile:profile(base)}));
  const localSnapshot=s.localSnapshot?history.find(n=>n.id===s.localSnapshot.id):null;
  const cloudSnapshot=s.cloudSnapshot?history.find(n=>n.id===s.cloudSnapshot.id):null;
  // In the targeted demo scenes, card counts and parsed units describe the same data.
  if(['splitUpdates','sameRun','sameChanges','mergeFull','missingBase','missingIdentity','globalConflict','mergeFailure'].includes(s.scene)){
    for(const n of history)if(n.id===localSnapshot?.id||n.id===cloudSnapshot?.id){n.slots=n.profile.runs.length;n.plays=n.profile.globals.playCount;}
  }
  return {...s,history,localSnapshot,cloudSnapshot,mergeBase:s.scene==='missingBase'?null:{snapshotId:'save-2',contentId:history.find(n=>n.id==='save-2')?.contentId,profile:baseProfile},slotCapacity:6};
}

export function planMerge(s, target=selectedTarget(s)) {
  const fail=error=>({ok:false,error:`合并失败：${error}。本机和云端均未修改。`});
  const local=s.localSnapshot?.profile,remote=target?.profile,b=s.mergeBase;
  if(s.gameId!=='shattered'||!local||!remote||local.format!==remote.format)return fail('此示例没有可用的同版本单局解析数据');
  const baseNode=s.history.find(n=>n.id===b?.snapshotId&&n.storage==='cloud'&&n.available&&n.contentId===b.contentId);
  if(!b||!baseNode||b.profile.format!==local.format||JSON.stringify(b.profile)!==JSON.stringify(baseNode.profile))return fail('缺少已验证的共同基线，无法判断哪一局被修改');
  // A common base must actually be an ancestor of both snapshots, not merely a saved file.
  const descends=(node,seen=new Set())=>{
    if(!node||seen.has(node.id))return false;seen.add(node.id);
    if(node.id===baseNode.id)return true;
    return [...(node.parentIds||[]),...(node.cloudParentIds||[])].some(id=>descends(s.history.find(n=>n.id===id),seen));
  };
  if(!descends(s.localSnapshot)||!descends(target))return fail('所选版本没有可验证的共同基线');
  const profiles=[b.profile,local,remote];
  if(profiles.some(p=>p.runs.some(r=>!r.runUUID||!r.contentHash)||new Set(p.runs.map(r=>r.runUUID)).size!==p.runs.length))return fail('单局身份缺失或重复，不能按槽号或种子猜测');
  if(profiles.some(p=>p.runs.some(r=>!Number.isInteger(r.slot)||r.slot<1||r.slot>s.slotCapacity)||new Set(p.runs.map(r=>r.slot)).size!==p.runs.length))return fail('输入槽位数据无效');
  const [beforeGlobals,localGlobals,remoteGlobals]=profiles.map(p=>JSON.stringify(p.globals));
  let globals;
  if(localGlobals===remoteGlobals||remoteGlobals===beforeGlobals)globals={...local.globals};
  else if(localGlobals===beforeGlobals)globals={...remote.globals};
  else return fail('全局记录双方均有不同修改，尚无安全的语义合并规则');
  const maps=profiles.map(p=>new Map(p.runs.map(r=>[r.runUUID,r]))),runs=[],decisions=[];
  for(const id of new Set(profiles.flatMap(p=>p.runs.map(r=>r.runUUID)))){
    const [before,l,r]=maps.map(m=>m.get(id)),hash=x=>x?.contentHash;
    if(hash(l)===hash(r)){if(l)runs.push({...l});decisions.push(`${id}：相同内容只保留一份`);}
    else if(hash(l)===hash(before)){if(r)runs.push({...r});decisions.push(`${id}：采用远端改动`);}
    else if(hash(r)===hash(before)){if(l)runs.push({...l});decisions.push(`${id}：采用本机改动`);}
    else if(!l||!r)return fail(`${id} 存在删除与继续修改冲突`);
    else {
      // Deterministic mock IDs stand in for persisted UUIDs in one frozen merge plan.
      for(const [side,run] of [['local',l],['remote',r]])runs.push({...run,runUUID:`demo-fork-${s.merges+1}-${id}-${side}`,sourceRunUUID:id,parentRunUUIDs:[id],name:`${run.name} · ${side==='local'?'本机':'远端'}分支`});
      decisions.push(`${id}：双方改动不同，保留两个槽位`);
    }
  }
  if(runs.length>s.slotCapacity)return fail(`需要 ${runs.length} 个游戏槽位，容量只有 ${s.slotCapacity} 个`);
  const occupied=new Set();
  for(const run of runs){if(!Number.isInteger(run.slot)||run.slot<1||run.slot>s.slotCapacity||occupied.has(run.slot))run.slot=Array.from({length:s.slotCapacity},(_,i)=>i+1).find(n=>!occupied.has(n));occupied.add(run.slot);}
  const profile={format:local.format,runs,globals};
  return {ok:true,profile,decisions,slots:runs.length,addedSlots:Math.max(0,runs.length-local.runs.length),baseId:baseNode.id,localId:s.localSnapshot.id,remoteId:target.id,contentId:`demo-merged:${JSON.stringify(profile)}`};
}
// Synthetic immutable snapshots. Published order is independent of save time and local edits.
function historyFixture(latest) {
  const make = (id,deviceId,deviceName,time,order,parentIds,contentId,extra={}) => ({id,deviceId,deviceName,time,order,parentIds,contentId,slots:2,plays:20+order,available:true,publishedOrder:order,...extra});
  return [
    make('save-1','demo-current','Windows-PC','2026/10/07 08:00',1,[],'demo-root'),
    make('save-2','demo-remote',latest.deviceName,'2026/10/08 09:00',2,['save-1'],'demo-older'),
    make('save-3','demo-tablet','Pixel Tablet','2026/10/08 11:00',3,['save-1'],'demo-branch'),
    make('save-4',latest.deviceId,latest.deviceName,'2026/10/09 09:00',4,['save-2'],latest.contentId)
  ].flatMap(n=>[
    {...n,id:`device-${n.id}`,storage:'device',ownerDeviceId:n.deviceId,publishedOrder:null,available:true,parentIds:n.parentIds.map(id=>`device-${id}`)},
    {...n,storage:'cloud',cloudParentIds:[...n.parentIds],parentIds:[`device-${n.id}`]}
  ]);
}
export function latestCloud(s) {
  return s.history.filter(n => n.storage === 'cloud' && n.available && n.contentId && Number.isFinite(n.publishedOrder))
    .sort((a,b)=>b.publishedOrder-a.publishedOrder)[0] || null;
}
export function selectedTarget(s) {
  return s.page === 'home' && s.selectedSnapshotId !== null
    ? s.history.find(n => n.id === s.selectedSnapshotId) || null : latestCloud(s);
}
export function targetMatchesLocal(s) {
  const target=selectedTarget(s);
  return s.connected && s.readStatus === 'found' && s.local === true && Boolean(s.localSnapshot?.contentId)
    && target?.storage === 'cloud' && Boolean(target?.available) && Number.isFinite(target.publishedOrder) && s.localSnapshot.contentId === target.contentId;
}
// Visibility is based on where the copy lives, never on its content's source device.
export function snapshotName(node) {
  return node?.displayName || (node?.storage==='cloud'?'云端存档':'本机存档');
}
export function visibleHistory(s) {
  const baseline=localState(s)==='unsynced'?localBaselineNode(s):null;
  return s.history.filter(n=>n.storage==='cloud' || (s.local === true && n.storage==='device' && (n.id===s.localSnapshot?.id || n.id===baseline?.id)));
}
export function matchingCloud(s) {
  return s.local===true && s.readStatus==='found' ? s.history.filter(n=>n.storage==='cloud'&&n.available&&n.contentId===s.localSnapshot?.contentId)
    .sort((a,b)=>b.publishedOrder-a.publishedOrder)[0] || null : null;
}
export function localState(s) {
  return localIsEmpty(s)?'empty':s.local===true&&s.readStatus==='found'?(matchingCloud(s)?'synced':'unsynced'):'unknown';
}
export function localBaseline(s) {
  const visited=new Set();
  const find=id=>{
    if(visited.has(id))return null;visited.add(id);
    const node=s.history.find(n=>n.id===id);if(!node)return null;
    if(node.storage==='cloud')return node;
    const copy=s.history.find(n=>n.storage==='cloud'&&n.parentIds?.includes(id)&&n.contentId===node.contentId);
    return copy || (node.parentIds||[]).map(find).find(Boolean) || null;
  };
  return (s.localSnapshot?.parentIds||[]).map(find).find(Boolean)||null;
}
export function localBaselineNode(s) {
  const cloud=localBaseline(s);if(!cloud)return null;
  const visited=new Set();
  const find=id=>{
    if(visited.has(id))return null;visited.add(id);
    const n=s.history.find(n=>n.id===id);if(!n||n.storage==='cloud')return null;
    if(n.ownerDeviceId===s.currentDeviceId&&n.contentId===cloud.contentId)return n;
    return (n.parentIds||[]).map(find).find(Boolean)||null;
  };
  return (s.localSnapshot?.parentIds||[]).map(find).find(Boolean)||null;
}
export function displayEdges(s) {
  const nodes=visibleHistory(s),ids=new Set(nodes.map(n=>n.id));
  const edges=nodes.filter(n=>n.storage==='cloud').flatMap(n=>(n.cloudParentIds||[]).filter(id=>ids.has(id)).map(id=>({parent:id,child:n.id,kind:'cloud'})));
  const add=(parent,child,kind)=>{if(ids.has(parent)&&ids.has(child))edges.push({parent,child,kind});};
  const transfer=(cloud,local)=>{
    // Copy provenance, not position or timestamps, defines transfer direction.
    if(cloud?.parentIds?.includes(local?.id))add(local.id,cloud.id,'synced');
    else if(local?.parentIds?.includes(cloud?.id))add(cloud.id,local.id,'synced');
  };
  if(localState(s)==='synced')transfer(matchingCloud(s),s.localSnapshot);
  if(localState(s)==='unsynced'){
    const baseline=localBaselineNode(s);
    if(baseline){transfer(localBaseline(s),baseline);add(baseline.id,s.localSnapshot.id,'unsynced');}
  }
  return edges;
}
export function historyColumnId(node) {
  return node.storage === 'cloud' ? 'cloud-column' : node.ownerDeviceId || node.deviceId;
}
// The displayed time is the save-content time, retained by uploads/restores.
// Parse the fixed fixture format explicitly; publication order never affects this axis.
function saveTime(node) {
  const m=/^(\d{4})\/(\d{2})\/(\d{2}) (\d{2}):(\d{2})$/.exec(node.time || '');
  return m ? Date.UTC(+m[1],+m[2]-1,+m[3],+m[4],+m[5]) : -Infinity;
}
export function historyRows(s) {
  const sorted=[...s.history].sort((a,b)=>{
    const at=saveTime(a),bt=saveTime(b);
    return at===bt ? (a.id<b.id?-1:a.id>b.id?1:0) : at>bt?-1:1;
  });
  const rows=[];let groupStart=0,previous;
  for(const node of sorted){
    const time=saveTime(node);
    if(time!==previous){groupStart=rows.length;previous=time;}
    let row=rows.slice(groupStart).find(r=>!r.some(n=>historyColumnId(n)===historyColumnId(node)));
    if(!row){row=[];rows.push(row);}row.push(node);
  }
  return rows;
}
export function historyEdges(s) {
  const ids=new Set(s.history.map(n=>n.id));
  return s.history.flatMap(n=>(n.parentIds||[]).filter(id=>ids.has(id)).map(id=>({parent:id,child:n.id})));
}
function publishLocal(s) {
  const nextOrder=Math.max(0,...s.history.map(n=>n.order))+1;
  const publishedOrder=Math.max(0,...s.history.map(n=>n.publishedOrder||0))+1;
  const snapshot={...s.localSnapshot,storage:'cloud',id:`upload-${s.uploads+1}-${nextOrder}`,order:nextOrder,publishedOrder,
    cloudParentIds:localBaseline(s)?[localBaseline(s).id]:[],parentIds:s.localSnapshot?.id ? [s.localSnapshot.id] : [],available:true};
  return {...s,syncedBaseline:{cloudId:snapshot.id,contentId:snapshot.contentId},history:[...s.history,snapshot],cloud:true,cloudSnapshot:snapshot,uploads:s.uploads+1};
}
function receiveCloud(s) {
  const order=Math.max(0,...s.history.map(n=>n.order))+1;
  const local={...s.pendingRestore,id:`restore-${s.restores+1}-${order}`,storage:'device',ownerDeviceId:s.currentDeviceId,
    publishedOrder:null,parentIds:[s.pendingRestore.id],order,restored:true};
  return {...s,syncedBaseline:{cloudId:s.pendingRestore.id,contentId:s.pendingRestore.contentId},localSnapshot:local,history:[...s.history,local],mergeResult:null};
}
function receiveMerge(s) {
  const plan=s.pendingMerge.plan,remote=s.history.find(n=>n.id===plan.remoteId),original=s.localSnapshot;
  // Only a successful, fully planned operation publishes sources and merged output.
  const saved=matchingCloud(s)?s:publishLocal(s),localCloud=matchingCloud(saved);
  const order=Math.max(...saved.history.map(n=>n.order))+1;
  const merged={id:`merge-${s.merges+1}-${order}`,storage:'cloud',ownerDeviceId:s.currentDeviceId,deviceId:s.currentDeviceId,deviceName:'Windows-PC',time:'2026/10/10 10:00',order,publishedOrder:Math.max(...saved.history.map(n=>n.publishedOrder||0))+1,contentId:plan.contentId,profile:plan.profile,slots:plan.slots,plays:plan.profile.globals.playCount,available:true,parentIds:[localCloud.id,remote.id],cloudParentIds:[localCloud.id,remote.id],displayName:'合并后的完整档案',mergedFrom:[original.id,remote.id],baseId:plan.baseId};
  const received={...merged,id:`merge-local-${s.merges+1}-${order}`,storage:'device',publishedOrder:null,parentIds:[merged.id],cloudParentIds:undefined};
  return {...saved,history:[...saved.history,merged,received],cloudSnapshot:merged,localSnapshot:received,selectedSnapshotId:merged.id,syncedBaseline:{cloudId:merged.id,contentId:merged.contentId},mergeBase:{snapshotId:merged.id,contentId:merged.contentId,profile:merged.profile},merges:s.merges+1,mergeResult:plan,preview:{...s.preview,playCount:merged.plays,slots:plan.profile.runs.map(r=>({slot:r.slot,modified:merged.time}))}};
}
// Device identity belongs to the snapshot source, independently of storage location.
export function deviceLabel(snapshot, currentDeviceId) {
  if (snapshot?.deviceId && snapshot.deviceId === currentDeviceId) return '本机';
  return snapshot?.deviceName?.trim() || '未知设备';
}
export function kind(s) {
  if (!s.connected || !['found', 'empty'].includes(s.readStatus)) return 'unknown';
  if (!s.local && !s.cloud) return 'empty';
  if (s.local && s.cloud) return isSynced(s) ? 'identical' : 'conflict';
  return s.local ? 'local' : 'cloud';
}
export function localIsEmpty(s) {
  return s.readStatus === 'empty' && s.local === false && s.preview?.records === false && s.preview.slots.length === 0;
}
export function isSynced(s) {
  return s.connected && s.readStatus === 'found' && s.local === true && s.cloud === true
    && Boolean(s.localSnapshot?.contentId) && s.localSnapshot.contentId === latestCloud(s)?.contentId;
}
export function canRestore(s) {
  const target=selectedTarget(s);
  return !s.busy && s.connected && s.cloud === true && Boolean(target?.contentId) && target.storage === 'cloud' && target.available && Number.isFinite(target.publishedOrder)
    && s.scene !== 'running' && !targetMatchesLocal(s)
    && (localIsEmpty(s) || (s.readStatus === 'found' && s.local === true && Boolean(s.localSnapshot?.contentId)));
}
function restoreTarget(s) {
  return JSON.stringify([s.page, s.choice, s.gameId, s.savePath, s.configRevision, s.connected, s.readStatus, s.local, s.cloud, s.localSnapshot, s.cloudSnapshot, s.selectedSnapshotId, selectedTarget(s), s.syncedBaseline]);
}
function beginRestore(s) {
  return {...s, pendingRestore: {...selectedTarget(s)}, renameId:null, busy: true, pending: 'restore', restoreConfirm: false, token: s.token + 1, message: ''};
}
// contentId stands in for the recomputed full game-content hash in this mock.
export function localUnchangedSinceSync(s) {
  const baseline=s.syncedBaseline;
  return s.readStatus==='found'&&s.local===true&&Boolean(baseline?.contentId)
    &&s.localSnapshot?.contentId===baseline.contentId
    &&s.history.some(n=>n.id===baseline.cloudId&&n.storage==='cloud'&&n.available&&n.contentId===baseline.contentId);
}
function requestRestore(s) {
  if (!canRestore(s)) return s;
  return localIsEmpty(s)||localUnchangedSinceSync(s)||Boolean(matchingCloud(s)) ? beginRestore(s) : {...s, token: s.token + 1,
    restoreConfirm: {target: restoreTarget(s), token: s.token + 1, direction:'restore',snapshotId:selectedTarget(s).id}, message: ''};
}
function requestUpload(s) {
  if(s.cloud&&!matchingCloud(s)&&!localUnchangedSinceSync(s))return {...s,token:s.token+1,restoreConfirm:{target:restoreTarget(s),token:s.token+1,direction:'upload',snapshotId:latestCloud(s).id},message:''};
  return {...s,busy:true,pending:'upload',renameId:null,token:s.token+1,message:''};
}
export function canFinish(s) {
  if (s.busy || s.page !== 'compare' || !s.connected || s.completed) return false;
  const k = kind(s);
  return k !== 'unknown' && (k === 'empty' || k === 'identical' || (k==='conflict'&&s.scene!=='running') || (s.choice === 'local' && s.local) || (s.choice === 'cloud' && s.cloud && s.scene !== 'running'));
}
function startRead(s, advance = false) {
  if (!s.savePath.trim()) return s;
  return {...s, preview: null, continueAfterRead: advance, page: 'read', readStatus: 'loading', local: null, busy: true, pending: 'read', token: s.token + 1,
    readAttempts: s.readAttempts + 1, connectionStatus: 'untested', testedRevision: null, connected: false, choice: '', message: ''};
}
export function reduce(s, action) {
  if (s.restoreConfirm && !['restore', 'merge', 'cancelRestore'].includes(action.type)) s = {...s, restoreConfirm: false};
  if (action.type === 'reset') return createState(action.scene || s.scene, s.gameId);
  if (action.type === 'back') {
    if (s.busy && !['connect', 'read'].includes(s.pending)) return s;
    const page = ['game', 'read'].includes(s.page) ? 'game' : s.page === 'compare' ? 'connect' : s.page === 'connect' ? (s.completed ? 'settings' : 'read') : 'home';
    return {...s, ...(s.page === 'connect' || page === 'connect' ? {connectionStatus: 'untested', testedRevision: null, connected: false} : {}), page, token: s.token + 1, busy: false, pending: '', restoreConfirm: false, folderOpen: false, message: '',
      ...(page === 'game' ? {preview: null, continueAfterRead: false, local: null, readStatus: 'unread', connected: false, choice: ''} : {})};
  }
  if (action.type === 'editPath' && s.page === 'read') return {...s, savePath: action.value, preview: null, continueAfterRead: false, local: null, readStatus: 'unread', connected: false, choice: '', busy: false, pending: '', token: s.token + 1, message: ''};
  if (action.type === 'editConnection' && s.page === 'connect') return {...s, connectionStatus: 'untested', testedRevision: null, configRevision: s.configRevision + 1, connected: false, busy: false, pending: '', token: s.token + 1, message: ''};
  if (s.pending === 'read' && action.type === 'next') return {...s, continueAfterRead: true};
  if (s.busy && action.type !== 'settled') return s;
  switch (action.type) {
    case 'beginRename': return s.page==='home'&&visibleHistory(s).some(n=>n.id===action.id)?{...s,renameId:action.id}:s;
    case 'cancelRename': return {...s,renameId:null};
    case 'rename': {
      if(s.page!=='home'||s.renameId!==action.id||!visibleHistory(s).some(n=>n.id===action.id))return s;
      const displayName=Array.from(String(action.value||'').trim()).slice(0,40).join('');
      const update=n=>n?.id===action.id?{...n,displayName}:n;
      return {...s,history:s.history.map(update),localSnapshot:update(s.localSnapshot),cloudSnapshot:update(s.cloudSnapshot),renameId:null};
    }
    case 'selectSnapshot': return s.page === 'home' && s.history.some(n=>n.id===action.id) ? {...s,selectedSnapshotId:action.id,renameId:null,restoreConfirm:false,message:''} : s;
    case 'clearSelection': return {...s,selectedSnapshotId:null,restoreConfirm:false,message:''};
    case 'chooseGame': return {...s, connectionStatus: 'untested', testedRevision: null, gameId: action.gameId || s.gameId, savePath: action.gameId && action.gameId !== s.gameId ? defaultSavePaths[action.gameId] || '' : s.savePath, folderOpen: false, page: 'game', selectedGame: true, preview: null, continueAfterRead: false, local: null, readStatus: 'unread', readAttempts: 0, connected: false, completed: false, choice: '', token: s.token + 1, message: ''};
    case 'next': return s.page === 'connect' ? (s.connectionStatus === 'success' && s.testedRevision === s.configRevision && s.connected ? {...s, page: s.completed ? 'settings' : 'compare', choice: '', message: ''} : s) : s.page === 'game' && s.selectedGame ? startRead({...s, page: 'read', message: ''})
      : s.page === 'read' && !s.folderOpen && s.savePath.trim() ? (s.preview ? {...s, page: 'connect'} : startRead(s, true)) : s;
    case 'preview': return s.page === 'read' && action.token === s.token && !s.folderOpen ? startRead(s) : s;
    case 'retryRead': return s.page === 'read' && s.savePath.trim() ? startRead(s) : s;
    case 'chooseFolder': return s.page === 'read' ? {...s, folderOpen: true} : s;
    case 'cancelFolder': return {...s, folderOpen: false};
    case 'useFolder': return s.page === 'read' && s.folderOpen
      ? startRead({...reduce(s, {type: 'editPath', value: `D:\\Game Saves\\${s.gameId}`}), folderOpen: false}) : s;
    case 'connect': return s.page !== 'connect' ? s : {...s, connected: false, connectionStatus: 'testing', testedRevision: null, busy: true, pending: 'connect', token: s.token + 1, message: ''};
    case 'settled': {
      if (!s.busy || action.token !== s.token) return s;
      if (action.type === 'editConnection' && s.page === 'connect') return {...s, connectionStatus: 'untested', testedRevision: null, configRevision: s.configRevision + 1, connected: false, busy: false, pending: '', token: s.token + 1, message: ''};
  if (s.pending === 'read') return s.scene === 'readFailure' && s.readAttempts === 1
        ? {...s, busy: false, pending: '', readStatus: 'failed', preview: null, continueAfterRead: false, local: null, message: ''}
        : {...s, page: s.continueAfterRead ? 'connect' : 'read', preview: saveFixture(s.scene), continueAfterRead: false, busy: false, pending: '', readStatus: s.fixtureLocal ? 'found' : 'empty', local: s.fixtureLocal, message: ''};
      if (s.pending === 'connect') return s.scene === 'failure'
        ? {...s, busy: false, pending: '', connected: false, connectionStatus: 'failed', testedRevision: null, message: ''}
        : {...s, busy: false, pending: '', connected: true, connectionStatus: 'success', testedRevision: s.configRevision, choice: '', message: ''};
      if (s.pending === 'upload') return {...publishLocal(s), busy: false, pending: '', message: '模拟上传完成。'};
      if(s.pending==='merge'){
        const plan=planMerge(s,s.history.find(n=>n.id===s.pendingMerge.plan.remoteId));
        if(s.pendingMerge.guard!==restoreTarget(s)||!plan.ok||JSON.stringify(plan)!==JSON.stringify(s.pendingMerge.plan))return {...s,busy:false,pending:'',pendingMerge:null,message:'合并失败：内容或基线已变化，请重新操作。本机和云端均未修改。'};
        if(s.scene==='mergeFailure'||action.failMerge)return {...s,busy:false,pending:'',pendingMerge:null,message:'合并失败：模拟写入未成功，本机和云端均未修改。'};
        return {...receiveMerge(s),page:'home',completed:true,busy:false,pending:'',pendingMerge:null,restoreConfirm:false,message:`模拟合并完成：${plan.slots} 个游戏槽位，新增占用 ${plan.addedSlots} 个。`};
      }
      if (s.pending === 'restore') return {...receiveCloud(s), page: s.page === 'compare' ? 'home' : s.page, completed: s.page === 'compare' || s.completed, readStatus: 'found', preview: {playCount:s.pendingRestore.plays,slots:Array.from({length:s.pendingRestore.slots},(_,i)=>({slot:i+1,modified:s.pendingRestore.time})),records:true,fileCount:s.pendingRestore.slots*7+3}, busy: false, pending: '', local: true, pendingRestore: null, restores: s.restores + 1, restoreConfirm: false, message: '模拟恢复完成。'};
      return s;
    }
    case 'choose': return s.page === 'compare' && ((action.value === 'local' && s.local) || (action.value === 'cloud' && s.cloud))
      ? {...s, choice: action.value, message: ''} : s;
    case 'finish': {
      if (!canFinish(s)) return s;
      const k = kind(s), upload = !['empty', 'identical'].includes(k) && s.choice === 'local';
      if(k==='conflict')return s.choice==='local'?requestUpload(s):requestRestore(s);
      if (!['empty', 'identical'].includes(k) && s.choice === 'cloud') return requestRestore(s);
      return {...(upload ? publishLocal(s) : s), page:'home',completed:true,message:''};
    }
    case 'settings': return s.page === 'home' ? {...s, page: 'settings', message: ''} : s;
    case 'connectionSettings': return s.page === 'settings' ? {...s, page: 'connect', connectionStatus: 'untested', testedRevision: null, connected: false, message: ''} : s;
    case 'upload': return s.page === 'home' && s.local && s.connected && !isSynced(s) && s.scene !== 'running'
      ? requestUpload(s) : s;
    case 'askRestore': return s.page === 'home' ? requestRestore(s) : s;
    case 'cancelRestore': return {...s, restoreConfirm: false};
    case 'restore': {
      if (!s.restoreConfirm) return s;
      const valid = action.confirmationToken === s.restoreConfirm.token && s.restoreConfirm.target === restoreTarget(s)
        && ['home', 'compare'].includes(s.page) && s.scene!=='running' && s.connected && (s.restoreConfirm.direction==='upload'?s.local:canRestore(s));
      return valid ? s.restoreConfirm.direction==='upload'?{...s,restoreConfirm:false,busy:true,pending:'upload',token:s.token+1,message:''}:beginRestore(s) : {...s, restoreConfirm: false, message: '进度已变化，请重新选择操作。'};
    }
    case 'merge': {
      if(!s.restoreConfirm)return s;
      if(action.confirmationToken!==s.restoreConfirm.token||s.restoreConfirm.target!==restoreTarget(s)||!s.connected||s.scene==='running')return {...s,restoreConfirm:false,message:'进度已变化，请重新选择操作。'};
      const plan=planMerge(s,s.history.find(n=>n.id===s.restoreConfirm.snapshotId));
      if(!plan.ok)return {...s,restoreConfirm:false,message:plan.error};
      return {...s,restoreConfirm:false,busy:true,pending:'merge',pendingMerge:{plan,guard:restoreTarget(s)},token:s.token+1,message:''};
    }
    default: return s;
  }
}
