import {scenes, games, gameLabel, saveStatus, createState, reduce, kind, canFinish, localIsEmpty, deviceLabel, isSynced, canRestore, selectedTarget, latestCloud, targetMatchesLocal, historyEdges, historyRows, historyColumnId, visibleHistory, displayEdges, localState, snapshotName} from './model.mjs';
let state = createState('history');
let pathTimer;
let historyObserver;
const app = document.querySelector('#app');
const sceneSelect = document.querySelector('#scene');
const gameSelect = document.querySelector('#game-sample');
const gameSummary = () => { const g = gameLabel(state); return `${g.name}${g.version ? ` · ${g.version}` : ''}`; };
const gameIcon = () => `<div class="game-icon" aria-hidden="true"><span>◆</span>${gameLabel(state).icon ? `<img src="${gameLabel(state).icon}" alt="" width="58" height="58">` : ''}</div>`;
const gameDetails = () => { const g = gameLabel(state); return [g.author ? `作者：${g.author}` : '', g.updated ? `更新：${g.updated}` : ''].filter(Boolean).map(text => `<p class="small">${text}</p>`).join(''); };
const pathStatus = () => `${saveStatus(state)}${state.preview?.slots.length ? `<ul class="slot-times">${state.preview.slots.map(slot => `<li>存档 ${slot.slot}<time>${slot.modified}</time></li>`).join('')}</ul><span class="hint">示例存档修改时间</span>` : ''}`;
const escapeText = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const sourceName = snapshot => escapeText(deviceLabel(snapshot, state.currentDeviceId));
const restoreWarning = () => `将用来自 ${sourceName(selectedTarget(state))} 的“${escapeText(snapshotName(selectedTarget(state)))}”（${selectedTarget(state)?.time || '时间未知'}）替换本机存档及游戏记录，不保留本机备份。`;
const button = (label, action, disabled = false, cls = '') => `<button class="${cls}" data-action="${action}" ${disabled ? 'disabled' : ''}>${label}</button>`;
const header = (title, step = '', extra = '') => `<header class="header"><div>${step ? `<div class="eyebrow">${step}</div>` : ''}${title ? `<h1>${title}</h1>` : ''}${state.page !== 'game' ? `<p class="game-summary small">${gameSummary()}</p>` : ''}</div>${extra}</header>`;
const footer = (left, right) => `<footer class="footer">${left}<div class="right">${right}</div></footer>`;
const back = () => button('上一步', 'back', state.busy && !['connect', 'read'].includes(state.pending), 'text-button');
const notice = () => state.message ? `<p class="${state.scene === 'failure' ? 'error' : 'notice'}" role="status">${state.message}</p>` : '';
const choice = (value, title, description) => `<label class="choice"><input type="radio" name="progress" value="${value}" ${state.choice === value ? 'checked' : ''}><span><strong>${title}</strong><time>${description}</time></span></label>`;
function historyView() {
  const visibleState={...state,history:visibleHistory(state)};
  const rows=historyRows(visibleState);
  const nodes=rows.flat();
  const devices=[{deviceId:'cloud-column',deviceName:'云端存档'},{deviceId:state.currentDeviceId,deviceName:'本机'}];
  const lane=historyColumnId;
  if(!rows.length)rows.push([]);
  const cells=rows.map((row,rowIndex)=>devices.map(d=>{
    const n=row.find(n=>lane(n)===d.deviceId);
    if(!n)return rowIndex===0&&d.deviceId===state.currentDeviceId&&localState(state)==='empty'?'<div class="history-cell"><div class="empty-local">无存档</div></div>':'<div class="history-cell"></div>';
    const isCloud=n.storage==='cloud';
    const unsynced=!isCloud&&n.id===state.localSnapshot?.id&&localState(state)==='unsynced';
    const status=isCloud?(!n.available?'云端内容不可用':latestCloud(state)?.id===n.id?'最新云端':'云端存档'):(unsynced?'未同步云端':'已同步云端');
    return `<div class="history-cell"><button class="history-node ${isCloud?'cloud-node':unsynced?'unsynced-node':''} ${state.localSnapshot?.id===n.id?'current-node':''}" data-snapshot-id="${n.id}" aria-pressed="${state.selectedSnapshotId===n.id}" ${state.busy?'disabled':''}><strong class="snapshot-name">${escapeText(snapshotName(n))}</strong><time>${n.time}</time>${isCloud?`<span>来源：${sourceName(n)}</span>`:''}<span>存档 ${n.slots} 个 · 游玩 ${n.plays} 次</span><small>${state.localSnapshot?.id===n.id?'本机当前 · ':''}${status}</small></button>${state.renameId===n.id?`<form class="rename-form" data-rename-id="${n.id}"><input aria-label="存档名称" maxlength="80" placeholder="留空使用默认名，最多40字"><div><button type="submit">保存</button><button type="button" data-action="cancelRename">取消</button></div></form>`:`<button class="rename-button" data-rename-id="${n.id}" ${state.busy?'disabled':''}>命名</button>`}</div>`;
  }).join('')).join('');
  return `<section class="history-section"><h2>存档历史</h2><p class="hint">按存档时间从新到旧 · 选择云端存档恢复。本机保留同步基线；黄色为当前未同步存档，虚线连接本机基线；窄屏可左右滚动。</p><p class="small">本机当前：${state.localSnapshot ? `${sourceName(state.localSnapshot)} · ${state.localSnapshot.time}` : '暂无存档'}</p><div class="history-layout"><aside class="time-axis" aria-label="时间向上增加，越上越新"><span>时间</span><i aria-hidden="true"></i></aside><div class="history-scroll" role="region" aria-label="按设备排列的存档历史，可左右滚动" tabindex="0"><div class="history-canvas" data-columns="${devices.length}"><div class="device-columns">${devices.map(d=>`<strong>${d.deviceId==='cloud-column'?'云端存档':sourceName(d)}</strong>`).join('')}</div><div class="history-body"><svg class="history-lines" aria-hidden="true"></svg><div class="history-grid">${cells}</div></div></div></div></div>${nodes.length?'':'<p class="hint">暂无存档历史</p>'}</section>`;
}
// Measure rendered cards after each layout change; text and editors can resize rows.
function drawHistoryLines() {
  const body=app.querySelector('.history-body'),svg=body?.querySelector('.history-lines');
  if(!svg)return;
  const bounds=body.getBoundingClientRect();
  const boxes=new Map([...body.querySelectorAll('.history-node')].map(node=>{
    const r=node.getBoundingClientRect();return [node.dataset.snapshotId,{left:r.left-bounds.left,right:r.right-bounds.left,top:r.top-bounds.top,bottom:r.bottom-bounds.top,cx:(r.left+r.right)/2-bounds.left,cy:(r.top+r.bottom)/2-bounds.top}];
  }));
  const rounded=points=>{
    let d=`M ${points[0][0]} ${points[0][1]}`;
    for(let i=1;i<points.length-1;i++){
      const a=points[i-1],b=points[i],c=points[i+1],ab=Math.hypot(b[0]-a[0],b[1]-a[1]),bc=Math.hypot(c[0]-b[0],c[1]-b[1]);
      if(!ab||!bc)continue;
      const r=Math.min(6,ab/2,bc/2),before=[b[0]+(a[0]-b[0])*r/ab,b[1]+(a[1]-b[1])*r/ab],after=[b[0]+(c[0]-b[0])*r/bc,b[1]+(c[1]-b[1])*r/bc];
      d+=` L ${before[0]} ${before[1]} Q ${b[0]} ${b[1]} ${after[0]} ${after[1]}`;
    }
    return d+` L ${points.at(-1)[0]} ${points.at(-1)[1]}`;
  };
  svg.setAttribute('width',bounds.width);svg.setAttribute('height',bounds.height);svg.setAttribute('viewBox',`0 0 ${bounds.width} ${bounds.height}`);
  svg.innerHTML=displayEdges(state).map((edge,index)=>{
    const a=boxes.get(edge.parent),b=boxes.get(edge.child);if(!a||!b)return '';
    let points;
    if(Math.abs(a.cx-b.cx)>1){
      const right=b.cx>a.cx,start=[right?a.right:a.left,a.cy],end=[right?b.left:b.right,b.cy],middle=(start[0]+end[0])/2;
      points=[start,[middle,start[1]],[middle,end[1]],end];
    }else{
      const down=b.cy>a.cy,start=[a.cx,down?a.bottom:a.top],end=[b.cx,down?b.top:b.bottom];
      const rail=edge.kind==='cloud'?10+index*8:bounds.width-18;
      const exit=start[1]+(down?8:-12),entry=end[1]+(down?-12:8);
      points=[start,[start[0],exit],[rail,exit],[rail,entry],[end[0],entry],end];
    }
    const start=points[0],end=points.at(-1);
    return `<path class="edge-${edge.kind}" data-parent="${edge.parent}" data-child="${edge.child}" d="${rounded(points)}"/><circle class="anchor" cx="${start[0]}" cy="${start[1]}" r="3"/><circle class="anchor" cx="${end[0]}" cy="${end[1]}" r="3"/>`;
  }).join('');
}
function targetView() {
  const target=selectedTarget(state);
  const unavailable=target && (target.storage !== 'cloud' || !target.available || !Number.isFinite(target.publishedOrder));
  return `<div class="restore-target"><strong>${state.selectedSnapshotId ? '已选择存档' : '默认恢复最新云端存档'}</strong><p>${target ? `${escapeText(snapshotName(target))} · ${sourceName(target)} · ${target.time}` : '暂无可恢复的云端存档'}</p>${unavailable ? '<p class="error">请选择“云端存档”列中可用的存档恢复；设备上的存档需先上传。</p>' : targetMatchesLocal(state) ? '<p class="hint">本机已是此进度，无需恢复。</p>' : ''}${state.selectedSnapshotId ? button('清除选择，使用最新云端','clearSelection',state.busy,'text-button') : ''}</div>`;
}
function render() {
  historyObserver?.disconnect();
  app.setAttribute('aria-busy', String(state.busy));
  sceneSelect.value = state.scene;
  gameSelect.value = state.gameId;
  gameSelect.disabled = state.busy;
  if (state.page === 'game') {
    app.innerHTML = header('选择游戏', '1 / 4') + `<section class="content"><div class="game-card">${gameIcon()}<div class="grow"><strong>${gameLabel(state).name}</strong><p class="small">${gameLabel(state).version}</p>${gameDetails()}</div><span class="pill">已找到</span></div>${button('模拟选择游戏…', 'chooseGame', false, 'secondary')}${notice()}</section>` + footer('', button('下一步', 'next', !state.selectedGame));
  } else if (state.page === 'read') {
    app.innerHTML = header('设置存档路径', '2 / 4') + `<section class="content"><label class="field">存档路径<input id="save-path" type="text" spellcheck="false" autocomplete="off"></label>${button('选择其他存档路径', 'chooseFolder', state.busy, 'secondary')}<p class="hint">路径与文件夹选择仅作模拟。</p>${state.folderOpen ? `<div class="folder-picker"><p>模拟选择：D:\\Game Saves\\${state.gameId}</p>${button('取消', 'cancelFolder', false, 'text-button')}${button('使用此文件夹', 'useFolder')}</div>` : ''}<div id="path-status" role="status" class="${state.readStatus === 'failed' ? 'error' : 'hint'}">${pathStatus()}</div></section>` + footer(back(), button(state.readStatus === 'failed' ? '重试' : state.continueAfterRead ? '等待检查…' : '下一步', 'next', state.continueAfterRead || state.folderOpen || !state.savePath.trim()));
    const pathInput = document.querySelector('#save-path');
    pathInput.value = state.savePath;
    pathInput.addEventListener('input', () => {
      state = reduce(state, {type: 'editPath', value: pathInput.value});
      app.setAttribute('aria-busy', 'false');
      const next = app.querySelector('[data-action="next"]'); next.disabled = state.folderOpen || !state.savePath.trim(); next.textContent = '下一步';
      app.querySelector('[data-action="chooseFolder"]').disabled = false;
      gameSelect.disabled = false;
      const status = document.querySelector('#path-status'); status.textContent = saveStatus(state); status.className = 'hint';
      clearTimeout(pathTimer); const token = state.token;
      if (state.savePath.trim()) pathTimer = setTimeout(() => send({type: 'preview', token}), 400);
    });
  } else if (state.page === 'connect') {
    app.innerHTML = header(state.completed ? '连接设置' : '连接云端', state.completed ? '' : '3 / 4') + `<section class="content"><form id="connection-form" class="form" autocomplete="off"><label class="field">WebDAV 地址<input id="endpoint" type="url" value="https://example.invalid/dav"></label><label class="field">用户名<input id="username" value="demo-player"></label><label class="field">密码<input id="password" type="password" placeholder="任意示例密码（可留空）" autocomplete="off"></label><p class="hint">仅用示例数据，请勿输入真实密码。</p><button id="test-connection" type="submit" class="secondary">测试连接</button><p id="connection-status" class="hint" role="status"></p></form></section>` + footer(back(), button('下一步', 'next', true));
    document.querySelector('#connection-form').addEventListener('submit', event => {
      event.preventDefault();
      // Only field-change events are tracked. Password values are never read or persisted.
      send({type: 'connect'});
    });
    app.querySelectorAll('#connection-form input').forEach(input => input.addEventListener('input', () => send({type: 'editConnection'})));
    updateConnectionControls();
  } else if (state.page === 'compare') {
    const k = kind(state);
    let content;
    if (k === 'empty') content = `<section class="content complete"><h1>配置完成</h1></section>`;
    else if (k === 'identical') content = `<section class="content complete"><h1>两边进度相同</h1><p>可以完成配置。</p></section>`;
    else content = `<section class="content"><p>${k === 'conflict' ? '选择要继续的进度' : k === 'local' ? (state.preview?.slots.length === 0 ? '本机有游戏记录' : '本机有存档') : '云端有存档'}</p><div class="choices">${state.local ? choice('local', '使用本机进度', `${sourceName(state.localSnapshot)} · 今天 09:30 · 模拟上传`) : ''}${state.cloud ? choice('cloud', '使用云端进度', `${sourceName(state.cloudSnapshot)} · 昨天 21:15 · 模拟恢复`) : ''}</div>${state.choice === 'local' && state.cloud ? '<p class="small">上传本机进度，保留原云端记录。</p>' : ''}</section>`;
    app.innerHTML = header(k === 'empty' ? '' : '选择存档', '4 / 4') + content + footer(back(), button('完成', 'finish', !canFinish(state)));
  } else if (state.page === 'home') {
    app.innerHTML = header('像素地牢', '存档同步', button('设置', 'settings', state.busy, 'text-button')) + `<section class="content"><div class="status">${state.scene === 'running' ? '游戏运行中，请退出后再同步。' : isSynced(state) ? '已同步 · 模拟模式' : '云端已连接 · 模拟模式'}</div><div class="home-actions">${button(state.pending === 'upload' ? '上传中…' : '上传', 'upload', state.busy || !state.local || isSynced(state) || state.scene === 'running')}${button(state.pending === 'restore' ? '恢复中…' : '恢复', 'askRestore', !canRestore(state), 'secondary')}</div>${!state.local && !state.cloud ? '<p class="small">暂无示例存档</p>' : ''}${targetView()}${historyView()}${notice()}</section>`;
  } else {
    app.innerHTML = header('设置') + `<section class="content"><div class="settings-row"><div><strong>游戏</strong><p class="small">${gameSummary()}</p></div>${button('模拟更换', 'chooseGame', false, 'text-button')}</div><div class="settings-row"><div><strong>云端连接</strong><p class="small">示例账号</p></div>${button('修改', 'connectionSettings', false, 'text-button')}</div>${notice()}</section>` + footer(button('返回', 'back', false, 'text-button'), '');
  }
  if (state.restoreConfirm) {
    app.insertAdjacentHTML('beforeend', `<dialog id="restore-dialog" aria-labelledby="restore-title" aria-describedby="restore-description"><h2 id="restore-title">覆盖本机进度？</h2><p id="restore-description">${restoreWarning()}</p><div class="dialog-actions"><button data-action="cancelRestore" class="secondary" autofocus>取消</button><button data-action="restore" data-confirmation-token="${state.restoreConfirm.token}">覆盖恢复</button></div></dialog>`);
    const dialog = app.querySelector('#restore-dialog');
    dialog.addEventListener('cancel', event => { event.preventDefault(); send({type: 'cancelRestore'}); });
    dialog.addEventListener('keydown', event => {
      if (event.key !== 'Tab') return;
      const [cancel, confirm] = dialog.querySelectorAll('button');
      if (event.shiftKey && document.activeElement === cancel) { event.preventDefault(); confirm.focus(); }
      else if (!event.shiftKey && document.activeElement === confirm) { event.preventDefault(); cancel.focus(); }
    });
    dialog.showModal();
  }
  const canvas=app.querySelector('.history-canvas');
  if (canvas) {
    const columns=Number(canvas.dataset.columns); canvas.style.width=`${columns*280}px`;
    canvas.querySelector('.device-columns').style.gridTemplateColumns=`repeat(${columns},280px)`;
    canvas.querySelector('.history-grid').style.gridTemplateColumns=`repeat(${columns},280px)`;
    historyObserver=new ResizeObserver(drawHistoryLines);
    historyObserver.observe(canvas.querySelector('.history-grid'));
    canvas.querySelectorAll('.history-node').forEach(node=>historyObserver.observe(node));
    drawHistoryLines();
  }
  app.querySelectorAll('.rename-button').forEach(element=>element.addEventListener('click',()=>send({type:'beginRename',id:element.dataset.renameId})));
  const renameForm=app.querySelector('.rename-form');
  if(renameForm){
    const input=renameForm.querySelector('input');input.value=state.history.find(n=>n.id===state.renameId)?.displayName||'';
    renameForm.addEventListener('submit',event=>{event.preventDefault();send({type:'rename',id:renameForm.dataset.renameId,value:input.value});});
    input.addEventListener('keydown',event=>{if((event.key==='Escape'||event.key==='Esc'||event.code==='Escape'||event.keyCode===27)){event.preventDefault();send({type:'cancelRename'});}});
    input.focus();
  }
  app.querySelectorAll('[data-snapshot-id]').forEach(element=>element.addEventListener('click',()=>send({type:'selectSnapshot',id:element.dataset.snapshotId})));
  app.querySelectorAll('.game-icon img').forEach(img => {
    const fallback = () => img.remove();
    img.addEventListener('error', fallback, {once: true});
    if (img.complete && img.naturalWidth === 0) fallback();
  });
  app.querySelectorAll('[data-action]').forEach(element => element.addEventListener('click', () => send({type: element.dataset.action, confirmationToken: Number(element.dataset.confirmationToken)})));
  app.querySelectorAll('input[name="progress"]').forEach(element => element.addEventListener('change', () => send({type: 'choose', value: element.value})));
}
function updateConnectionControls() {
  const test = document.querySelector('#test-connection');
  test.disabled = state.busy; test.textContent = state.connectionStatus === 'testing' ? '测试中…' : '测试连接';
  const status = document.querySelector('#connection-status');
  status.textContent = {untested: '', testing: '正在测试…', success: '连接成功', failed: '连接失败，请重试。'}[state.connectionStatus];
  status.className = state.connectionStatus === 'failed' ? 'error' : 'hint';
  app.querySelector('[data-action="next"]').disabled = state.busy || !state.connected || state.connectionStatus !== 'success' || state.testedRevision !== state.configRevision;
  app.setAttribute('aria-busy', String(state.busy)); gameSelect.disabled = state.busy;
}
function send(action) {
  const previous = state;
  const scrollLeft=app.querySelector('.history-scroll')?.scrollLeft || 0;
  state = reduce(state, action);
  if (state === previous) return;
  if (previous.page === 'connect' && state.page === 'connect') updateConnectionControls(); else render();
  if (app.querySelector('.history-scroll')) app.querySelector('.history-scroll').scrollLeft=scrollLeft;
  if (action.type === 'selectSnapshot') app.querySelector(`[data-snapshot-id="${action.id}"]`)?.focus({preventScroll:true});
  if (['rename','cancelRename'].includes(action.type)) app.querySelector(`.rename-button[data-rename-id="${previous.renameId}"]`)?.focus({preventScroll:true});
  if (action.type === 'clearSelection') app.querySelector('[data-action="askRestore"]')?.focus();
  if (previous.restoreConfirm && !state.restoreConfirm && action.type === 'cancelRestore') app.querySelector(`[data-action="${state.page === 'compare' ? 'finish' : 'askRestore'}"]`)?.focus();
  if (state.busy && (!previous.busy || state.token !== previous.token)) {
    const token = state.token;
    const delay = state.pending === 'read' ? (state.scene === 'reading' ? 5000 : 1200) : state.scene === 'connecting' ? 5000 : 550;
    setTimeout(() => send({type: 'settled', token}), delay);
  }
}
for (const [value, label] of Object.entries(scenes)) { const option = document.createElement('option'); option.value = value; option.textContent = label; sceneSelect.append(option); }
function reset() {
  // Increment the epoch so a timer from the previous scene can never affect this one.
  const token = state.token + 1;
  state = {...createState(sceneSelect.value, state.gameId), token};
  render();
}
for (const [value, game] of Object.entries(games)) { const option = document.createElement('option'); option.value = value; option.textContent = game.name || '名称缺失'; gameSelect.append(option); }
gameSelect.addEventListener('change', () => send({type: 'chooseGame', gameId: gameSelect.value}));
sceneSelect.addEventListener('change', reset);
document.querySelector('#reset').addEventListener('click', reset);
render();
