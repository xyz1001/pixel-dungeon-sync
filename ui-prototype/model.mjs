export const scenes = {
  empty: '双方为空', local: '只有本机存档', cloud: '只有云端存档', conflict: '双方进度不同',
  identical: '双方进度相同', failure: '连接失败', connecting: '连接中', running: '游戏运行中'
};
export function createState(scene = 'empty') {
  const local = ['local', 'conflict', 'identical', 'running'].includes(scene);
  const cloud = ['cloud', 'conflict', 'identical', 'running'].includes(scene);
  return {scene, page: 'game', selectedGame: true, local, cloud, same: ['identical', 'running'].includes(scene),
    connected: false, choice: '', busy: false, pending: '', token: 0, message: '', restoreConfirm: false,
    completed: false, returnPage: 'home', uploads: 0, restores: 0};
}
export function kind(s) {
  if (!s.connected) return 'unknown';
  if (!s.local && !s.cloud) return 'empty';
  if (s.local && s.cloud) return s.same ? 'identical' : 'conflict';
  return s.local ? 'local' : 'cloud';
}
export function canFinish(s) {
  if (s.busy || s.page !== 'compare' || !s.connected || s.completed) return false;
  const k = kind(s);
  return k === 'empty' || k === 'identical' || (s.choice === 'local' && s.local) || (s.choice === 'cloud' && s.cloud && s.scene !== 'running');
}
export function reduce(s, action) {
  if (action.type === 'reset') return createState(action.scene || s.scene);
  if (action.type === 'back') {
    if (s.busy && s.pending !== 'connect') return s;
    const page = s.page === 'game' ? 'game' : s.page === 'compare' ? 'connect' : s.page === 'connect' ? (s.completed ? 'settings' : 'game') : 'home';
    return {...s, page, token: s.token + 1, busy: false, pending: '', restoreConfirm: false, message: ''};
  }
  if (s.busy && action.type !== 'settled') return s;
  switch (action.type) {
    case 'chooseGame': return {...s, selectedGame: true, message: ''};
    case 'next': return s.page === 'game' && s.selectedGame ? {...s, page: 'connect', message: ''} : s;
    case 'connect': return s.page !== 'connect' ? s : {...s, busy: true, pending: 'connect', token: s.token + 1, message: ''};
    case 'settled': {
      if (!s.busy || action.token !== s.token) return s;
      if (s.pending === 'connect') return s.scene === 'failure'
        ? {...s, busy: false, pending: '', connected: false, message: '连接失败，请重试或切换测试场景。'}
        : {...s, busy: false, pending: '', connected: true, page: s.completed ? 'settings' : 'compare', choice: '', message: ''};
      if (s.pending === 'upload') return {...s, busy: false, pending: '', cloud: true, same: true, uploads: s.uploads + 1, message: '模拟上传完成。'};
      if (s.pending === 'restore') return {...s, busy: false, pending: '', local: true, same: true, restores: s.restores + 1, restoreConfirm: false, message: '模拟恢复完成。'};
      return s;
    }
    case 'choose': return s.page === 'compare' && ((action.value === 'local' && s.local) || (action.value === 'cloud' && s.cloud))
      ? {...s, choice: action.value, message: ''} : s;
    case 'finish': {
      if (!canFinish(s)) return s;
      const k = kind(s), upload = !['empty', 'identical'].includes(k) && s.choice === 'local', restore = !['empty', 'identical'].includes(k) && s.choice === 'cloud';
      return {...s, page: 'home', completed: true, local: s.local || restore, cloud: s.cloud || upload,
        same: s.local || s.cloud, uploads: s.uploads + Number(upload), restores: s.restores + Number(restore), message: ''};
    }
    case 'settings': return s.page === 'home' ? {...s, page: 'settings', message: ''} : s;
    case 'connectionSettings': return s.page === 'settings' ? {...s, page: 'connect', message: ''} : s;
    case 'upload': return s.page === 'home' && s.local && s.connected && s.scene !== 'running'
      ? {...s, busy: true, pending: 'upload', token: s.token + 1, message: ''} : s;
    case 'askRestore': return s.page === 'home' && s.cloud && s.connected && s.scene !== 'running' ? {...s, restoreConfirm: true, message: ''} : s;
    case 'cancelRestore': return {...s, restoreConfirm: false};
    case 'restore': return s.page === 'home' && s.restoreConfirm && s.cloud && s.scene !== 'running'
      ? {...s, busy: true, pending: 'restore', token: s.token + 1, message: ''} : s;
    default: return s;
  }
}
