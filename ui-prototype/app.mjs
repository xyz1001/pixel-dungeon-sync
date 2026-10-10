import {scenes, games, gameLabel, saveStatus, createState, reduce, kind, canFinish, localIsEmpty} from './model.mjs';
let state = createState();
let pathTimer;
const app = document.querySelector('#app');
const sceneSelect = document.querySelector('#scene');
const gameSelect = document.querySelector('#game-sample');
const gameSummary = () => { const g = gameLabel(state); return `${g.name}${g.version ? ` · ${g.version}` : ''}`; };
const gameIcon = () => `<div class="game-icon" aria-hidden="true"><span>◆</span>${gameLabel(state).icon ? `<img src="${gameLabel(state).icon}" alt="" width="58" height="58">` : ''}</div>`;
const gameDetails = () => { const g = gameLabel(state); return [g.author ? `作者：${g.author}` : '', g.updated ? `更新：${g.updated}` : ''].filter(Boolean).map(text => `<p class="small">${text}</p>`).join(''); };
const pathStatus = () => `${saveStatus(state)}${state.preview?.slots.length ? `<ul class="slot-times">${state.preview.slots.map(slot => `<li>存档 ${slot.slot}<time>${slot.modified}</time></li>`).join('')}</ul><span class="hint">示例存档修改时间</span>` : ''}`;
const restoreWarning = '会先备份，再替换本机进度。云端没有的本机进度会移除。';
const button = (label, action, disabled = false, cls = '') => `<button class="${cls}" data-action="${action}" ${disabled ? 'disabled' : ''}>${label}</button>`;
const header = (title, step = '', extra = '') => `<header class="header"><div>${step ? `<div class="eyebrow">${step}</div>` : ''}${title ? `<h1>${title}</h1>` : ''}${state.page !== 'game' ? `<p class="game-summary small">${gameSummary()}</p>` : ''}</div>${extra}</header>`;
const footer = (left, right) => `<footer class="footer">${left}<div class="right">${right}</div></footer>`;
const back = () => button('上一步', 'back', state.busy && !['connect', 'read'].includes(state.pending), 'text-button');
const notice = () => state.message ? `<p class="${state.scene === 'failure' ? 'error' : 'notice'}" role="status">${state.message}</p>` : '';
const choice = (value, title, description) => `<label class="choice"><input type="radio" name="progress" value="${value}" ${state.choice === value ? 'checked' : ''}><span><strong>${title}</strong><time>${description}</time></span></label>`;
function render() {
  app.setAttribute('aria-busy', String(state.busy));
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
    else content = `<section class="content"><p>${k === 'conflict' ? '选择要继续的进度' : k === 'local' ? (state.preview?.slots.length === 0 ? '本机有游戏记录' : '本机有存档') : '云端有存档'}</p><div class="choices">${state.local ? choice('local', '使用本机进度', '这台电脑 · 今天 09:30 · 模拟上传') : ''}${state.cloud ? choice('cloud', '使用云端进度', '另一台设备 · 昨天 21:15 · 模拟恢复') : ''}</div>${state.choice === 'cloud' && !localIsEmpty(state) ? `<p class="warning">${restoreWarning}</p>` : state.choice === 'local' && state.cloud ? '<p class="small">上传本机进度，保留原云端记录。</p>' : ''}</section>`;
    app.innerHTML = header(k === 'empty' ? '' : '选择存档', '4 / 4') + content + footer(back(), button('完成', 'finish', !canFinish(state)));
  } else if (state.page === 'home') {
    app.innerHTML = header('像素地牢', '存档同步', button('设置', 'settings', state.busy, 'text-button')) + `<section class="content"><div class="status">${state.scene === 'running' ? '游戏运行中，请退出后再同步。' : '云端已连接 · 模拟模式'}</div><div class="home-actions">${button(state.pending === 'upload' ? '上传中…' : '上传', 'upload', state.busy || !state.local || state.scene === 'running')}${button(state.pending === 'restore' ? '恢复中…' : '恢复', 'askRestore', state.busy || !state.cloud || state.scene === 'running', 'secondary')}</div>${!state.local && !state.cloud ? '<p class="small">暂无示例存档</p>' : ''}${state.restoreConfirm ? `<div class="warning">${restoreWarning}<div class="home-actions">${button('取消', 'cancelRestore', state.busy, 'secondary')}${button('备份并恢复', 'restore', state.busy)}</div></div>` : ''}<div class="records">${state.local ? '<div class="save-card"><div><strong>本机进度</strong><p class="small">这台电脑</p></div><span class="small muted">今天 09:30</span></div>' : ''}${state.cloud ? '<div class="save-card"><div><strong>云端进度</strong><p class="small">另一台设备</p></div><span class="small muted">昨天 21:15</span></div>' : ''}</div>${notice()}</section>`;
  } else {
    app.innerHTML = header('设置') + `<section class="content"><div class="settings-row"><div><strong>游戏</strong><p class="small">${gameSummary()}</p></div>${button('模拟更换', 'chooseGame', false, 'text-button')}</div><div class="settings-row"><div><strong>云端连接</strong><p class="small">示例账号</p></div>${button('修改', 'connectionSettings', false, 'text-button')}</div>${notice()}</section>` + footer(button('返回', 'back', false, 'text-button'), '');
  }
  app.querySelectorAll('.game-icon img').forEach(img => {
    const fallback = () => img.remove();
    img.addEventListener('error', fallback, {once: true});
    if (img.complete && img.naturalWidth === 0) fallback();
  });
  app.querySelectorAll('[data-action]').forEach(element => element.addEventListener('click', () => send({type: element.dataset.action})));
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
  state = reduce(state, action);
  if (state === previous) return;
  if (previous.page === 'connect' && state.page === 'connect') updateConnectionControls(); else render();
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
