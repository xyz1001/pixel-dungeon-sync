import {scenes, games, gameLabel, createState, reduce, kind, canFinish} from './model.mjs';
let state = createState();
const app = document.querySelector('#app');
const sceneSelect = document.querySelector('#scene');
const gameSelect = document.querySelector('#game-sample');
const gameSummary = () => { const g = gameLabel(state); return `${g.name}${g.version ? ` · ${g.version}` : ''}`; };
const restoreWarning = '会先备份，再替换本机进度。云端没有的本机进度会移除。';
const button = (label, action, disabled = false, cls = '') => `<button class="${cls}" data-action="${action}" ${disabled ? 'disabled' : ''}>${label}</button>`;
const header = (title, step = '', extra = '') => `<header class="header"><div>${step ? `<div class="eyebrow">${step}</div>` : ''}${title ? `<h1>${title}</h1>` : ''}${state.page !== 'game' ? `<p class="game-summary small">${gameSummary()}</p>` : ''}</div>${extra}</header>`;
const footer = (left, right) => `<footer class="footer">${left}<div class="right">${right}</div></footer>`;
const back = () => button(state.busy && state.pending === 'connect' ? '取消连接' : '上一步', 'back', state.busy && !['connect', 'read'].includes(state.pending), 'text-button');
const notice = () => state.message ? `<p class="${state.scene === 'failure' ? 'error' : 'notice'}" role="status">${state.message}</p>` : '';
const choice = (value, title, description) => `<label class="choice"><input type="radio" name="progress" value="${value}" ${state.choice === value ? 'checked' : ''}><span><strong>${title}</strong><time>${description}</time></span></label>`;
function render() {
  app.setAttribute('aria-busy', String(state.busy));
  gameSelect.value = state.gameId;
  gameSelect.disabled = state.busy;
  if (state.page === 'game') {
    app.innerHTML = header('选择游戏', '1 / 4') + `<section class="content"><div class="game-card"><div class="game-icon" aria-hidden="true">◆</div><div class="grow"><strong>${gameLabel(state).name}</strong><p class="small">${gameLabel(state).version}</p></div><span class="pill">已找到</span></div>${button('模拟选择游戏…', 'chooseGame', false, 'secondary')}${notice()}</section>` + footer('', button('下一步', 'next', !state.selectedGame));
  } else if (state.page === 'read') {
    const copy = {unread: '尚未读取存档', loading: '正在读取本地存档…', found: '已找到本地存档', empty: '本地暂无存档', failed: '读取失败，请重试。'}[state.readStatus];
    app.innerHTML = header('读取本地存档', '2 / 4') + `<section class="content"><p class="${state.readStatus === 'failed' ? 'error' : 'status'}" role="status">${state.readStatus === 'loading' ? '<span class="spinner" aria-hidden="true"></span>' : ''}${copy}</p>${state.readStatus === 'loading' ? '<p class="hint">正在检查游戏默认位置（模拟）</p>' : ''}</section>` + footer(back(), state.readStatus === 'failed' ? button('重试', 'retryRead') : button('下一步', 'next', !['found', 'empty'].includes(state.readStatus)));
  } else if (state.page === 'connect') {
    app.innerHTML = header(state.completed ? '连接设置' : '连接云端', state.completed ? '' : '3 / 4') + `<section class="content"><form id="connection-form" class="form" autocomplete="off"><label class="field">WebDAV 地址<input id="endpoint" type="url" value="https://example.invalid/dav" ${state.busy ? 'disabled' : ''}></label><label class="field">用户名<input id="username" value="demo-player" ${state.busy ? 'disabled' : ''}></label><label class="field">密码<input id="password" type="password" placeholder="任意示例密码" autocomplete="off" ${state.busy ? 'disabled' : ''}></label><p class="hint">仅用示例数据，请勿输入真实密码。</p></form>${notice()}</section>` + footer(back(), `<button form="connection-form" type="submit" ${state.busy ? 'disabled' : ''}>${state.busy ? '<span class="spinner" aria-hidden="true"></span>连接中…' : '连接'}</button>`);
    document.querySelector('#connection-form').addEventListener('submit', event => {
      event.preventDefault();
      // Never read, retain or persist the password. No request is issued.
      document.querySelector('#password').value = '';
      send({type: 'connect'});
    });
  } else if (state.page === 'compare') {
    const k = kind(state);
    let content;
    if (k === 'empty') content = `<section class="content complete"><h1>配置完成</h1></section>`;
    else if (k === 'identical') content = `<section class="content complete"><h1>两边进度相同</h1><p>可以完成配置。</p></section>`;
    else content = `<section class="content"><p>${k === 'conflict' ? '选择要继续的进度' : k === 'local' ? '本机有存档' : '云端有存档'}</p><div class="choices">${state.local ? choice('local', '使用本机进度', '这台电脑 · 今天 09:30 · 模拟上传') : ''}${state.cloud ? choice('cloud', '使用云端进度', '另一台设备 · 昨天 21:15 · 模拟恢复') : ''}</div>${state.choice === 'cloud' ? `<p class="warning">${restoreWarning}</p>` : state.choice === 'local' && state.cloud ? '<p class="small">上传本机进度，保留原云端记录。</p>' : ''}</section>`;
    app.innerHTML = header(k === 'empty' ? '' : '选择存档', '4 / 4') + content + footer(back(), button('完成', 'finish', !canFinish(state)));
  } else if (state.page === 'home') {
    app.innerHTML = header('像素地牢', '存档同步', button('设置', 'settings', state.busy, 'text-button')) + `<section class="content"><div class="status">${state.scene === 'running' ? '游戏运行中，请退出后再同步。' : '云端已连接 · 模拟模式'}</div><div class="home-actions">${button(state.pending === 'upload' ? '上传中…' : '上传', 'upload', state.busy || !state.local || state.scene === 'running')}${button(state.pending === 'restore' ? '恢复中…' : '恢复', 'askRestore', state.busy || !state.cloud || state.scene === 'running', 'secondary')}</div>${!state.local && !state.cloud ? '<p class="small">暂无示例存档</p>' : ''}${state.restoreConfirm ? `<div class="warning">${restoreWarning}<div class="home-actions">${button('取消', 'cancelRestore', state.busy, 'secondary')}${button('备份并恢复', 'restore', state.busy)}</div></div>` : ''}<div class="records">${state.local ? '<div class="save-card"><div><strong>本机进度</strong><p class="small">这台电脑</p></div><span class="small muted">今天 09:30</span></div>' : ''}${state.cloud ? '<div class="save-card"><div><strong>云端进度</strong><p class="small">另一台设备</p></div><span class="small muted">昨天 21:15</span></div>' : ''}</div>${notice()}</section>`;
  } else {
    app.innerHTML = header('设置') + `<section class="content"><div class="settings-row"><div><strong>游戏</strong><p class="small">${gameSummary()}</p></div>${button('模拟更换', 'chooseGame', false, 'text-button')}</div><div class="settings-row"><div><strong>云端连接</strong><p class="small">示例账号</p></div>${button('修改', 'connectionSettings', false, 'text-button')}</div>${notice()}</section>` + footer(button('返回', 'back', false, 'text-button'), '');
  }
  app.querySelectorAll('[data-action]').forEach(element => element.addEventListener('click', () => send({type: element.dataset.action})));
  app.querySelectorAll('input[name="progress"]').forEach(element => element.addEventListener('change', () => send({type: 'choose', value: element.value})));
}
function send(action) {
  const previous = state;
  state = reduce(state, action);
  if (state === previous) return;
  render();
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
