(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.username || location.password || (location.port && location.port !== '443') || !['second.risedie.ru','www.second.risedie.ru'].includes(location.hostname)) return;
  if (window.__grafitNativeInstalled) return;
  window.__grafitNativeInstalled = true;
  const documentToken = __DOCUMENT_JSON__;
  const pending = new Map(); let sequence = 0, closed = false;
  const cancel = () => { for (const item of pending.values()) { clearTimeout(item.timer); item.reject(new Error('SESSION_CHANGED')); } pending.clear(); };
  const call = (method, args = []) => {
    if (method === 'clearAuthSession' || method === 'setWebSessionState') cancel();
    if (closed || pending.size >= 32) return Promise.reject(new Error('BRIDGE_UNAVAILABLE'));
    return new Promise((resolve, reject) => {
      const id = 'ios:' + (++sequence);
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('NATIVE_TIMEOUT')); }, 45000);
      pending.set(id, {resolve,reject,timer});
      try { window.webkit.messageHandlers.grafitBridge.postMessage({document:documentToken,id,method,args}); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  };
  window.__grafitNativeResolve = response => {
    if (response.document !== documentToken) return;
    const item = pending.get(response.id); if (!item) return;
    pending.delete(response.id); clearTimeout(item.timer);
    response.ok ? item.resolve(response.result) : item.reject(new Error(response.result?.code || 'NATIVE_REQUEST_FAILED'));
  };
  window.__grafitNativeEvent = (token, name, detail) => { if (token === documentToken && !closed) window.dispatchEvent(new CustomEvent(name, {detail})); };
  const bridge = {authTransport:'cookie',durableSyncVersion:1};
  ['getBridgeInfo','getActivityPrerequisites','getPermissionState','requestActivityPermissions','getActivitySyncPayload','getPendingHealthSync','acknowledgeHealthSync','failHealthSync','expireHealthSync','clearAuthSession'].forEach(method => { bridge[method] = (...args) => call(method,args); });
  window.ChallengeAppBridge = Object.freeze(bridge);
  const updateSession = () => call('setWebSessionState',[window.GrafitAuth?.isAuthenticated() === true || (!window.GrafitAuth && window.__GRAFIT_BOOTSTRAP__?.auth === true)]).catch(() => {});
  window.addEventListener('grafit:auth-changed', updateSession);
  window.addEventListener('pagehide', () => { closed = true; cancel(); });
  window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
  updateSession();
  window.dispatchEvent(new CustomEvent('grafit-native-bridge-ready'));
})();
