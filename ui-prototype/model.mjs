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
  empty: '无存档，无游戏记录', recordsOnly: '无存档，有游戏记录', recordsConflict: '本机仅记录，云端有存档', local: '本机多槽存档', cloud: '只有云端存档', longDevice: '云端设备长名称', conflict: '双方进度不同',
  identical: '双方进度相同', failure: '连接失败', connecting: '连接中', running: '游戏运行中',
  reading: '读取中', readFailure: '读取失败（重试可恢复）'
};
// Fixed local modification times, not scan timestamps. Slot count is independent of file count.
export function saveFixture(scene) {
  const slots = ['local', 'conflict', 'identical', 'running', 'readFailure'].includes(scene)
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
  const fixtureLocal = saveFixture(scene).records;
  const cloud = ['cloud', 'longDevice', 'recordsConflict', 'conflict', 'identical', 'running'].includes(scene);
  return {scene, gameId, currentDeviceId: 'demo-current', localSnapshot: fixtureLocal ? {deviceId: 'demo-current', deviceName: 'Windows-PC'} : null, cloudSnapshot: cloud ? {deviceId: 'demo-remote', deviceName: scene === 'longDevice' ? 'MacBook-Example-Very-Long-Device-Name-用于窄屏展示的虚构设备名称' : 'MacBook'} : null, savePath: defaultSavePaths[gameId] || '', folderOpen: false, page: 'game', selectedGame: true, fixtureLocal, preview: null, continueAfterRead: false, local: null, readStatus: 'unread', readAttempts: 0, cloud, same: ['identical', 'running'].includes(scene),
    connectionStatus: 'untested', configRevision: 0, testedRevision: null, connected: false, choice: '', busy: false, pending: '', token: 0, message: '', restoreConfirm: false,
    completed: false, returnPage: 'home', uploads: 0, restores: 0};
}
// Device identity belongs to the snapshot source, independently of storage location.
export function deviceLabel(snapshot, currentDeviceId) {
  if (snapshot?.deviceId && snapshot.deviceId === currentDeviceId) return '本机';
  return snapshot?.deviceName?.trim() || '未知设备';
}
export function kind(s) {
  if (!s.connected || !['found', 'empty'].includes(s.readStatus)) return 'unknown';
  if (!s.local && !s.cloud) return 'empty';
  if (s.local && s.cloud) return s.same ? 'identical' : 'conflict';
  return s.local ? 'local' : 'cloud';
}
export function localIsEmpty(s) {
  return s.readStatus === 'empty' && s.local === false && s.preview?.records === false && s.preview.slots.length === 0;
}
export function canFinish(s) {
  if (s.busy || s.page !== 'compare' || !s.connected || s.completed) return false;
  const k = kind(s);
  return k !== 'unknown' && (k === 'empty' || k === 'identical' || (s.choice === 'local' && s.local) || (s.choice === 'cloud' && s.cloud && s.scene !== 'running'));
}
function startRead(s, advance = false) {
  if (!s.savePath.trim()) return s;
  return {...s, preview: null, continueAfterRead: advance, page: 'read', readStatus: 'loading', local: null, busy: true, pending: 'read', token: s.token + 1,
    readAttempts: s.readAttempts + 1, connectionStatus: 'untested', testedRevision: null, connected: false, choice: '', message: ''};
}
export function reduce(s, action) {
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
      if (s.pending === 'upload') return {...s, busy: false, pending: '', cloud: true, cloudSnapshot: s.localSnapshot ? {...s.localSnapshot} : null, same: true, uploads: s.uploads + 1, message: '模拟上传完成。'};
      if (s.pending === 'restore') return {...s, busy: false, pending: '', local: true, localSnapshot: s.cloudSnapshot ? {...s.cloudSnapshot} : null, same: true, restores: s.restores + 1, restoreConfirm: false, message: '模拟恢复完成。'};
      return s;
    }
    case 'choose': return s.page === 'compare' && ((action.value === 'local' && s.local) || (action.value === 'cloud' && s.cloud))
      ? {...s, choice: action.value, message: ''} : s;
    case 'finish': {
      if (!canFinish(s)) return s;
      const k = kind(s), upload = !['empty', 'identical'].includes(k) && s.choice === 'local', restore = !['empty', 'identical'].includes(k) && s.choice === 'cloud';
      return {...s, page: 'home', completed: true, local: s.local || restore, cloud: s.cloud || upload,
        localSnapshot: restore ? (s.cloudSnapshot ? {...s.cloudSnapshot} : null) : s.localSnapshot, cloudSnapshot: upload ? (s.localSnapshot ? {...s.localSnapshot} : null) : s.cloudSnapshot,
        same: s.local || s.cloud, uploads: s.uploads + Number(upload), restores: s.restores + Number(restore), message: ''};
    }
    case 'settings': return s.page === 'home' ? {...s, page: 'settings', message: ''} : s;
    case 'connectionSettings': return s.page === 'settings' ? {...s, page: 'connect', connectionStatus: 'untested', testedRevision: null, connected: false, message: ''} : s;
    case 'upload': return s.page === 'home' && s.local && s.connected && s.scene !== 'running'
      ? {...s, busy: true, pending: 'upload', token: s.token + 1, message: ''} : s;
    case 'askRestore': return s.page === 'home' && s.cloud && s.connected && s.scene !== 'running' ? (localIsEmpty(s) ? {...s, busy: true, pending: 'restore', restoreConfirm: false, token: s.token + 1, message: ''} : {...s, restoreConfirm: true, message: ''}) : s;
    case 'cancelRestore': return {...s, restoreConfirm: false};
    case 'restore': return s.page === 'home' && s.restoreConfirm && s.cloud && s.scene !== 'running'
      ? {...s, busy: true, pending: 'restore', token: s.token + 1, message: ''} : s;
    default: return s;
  }
}
