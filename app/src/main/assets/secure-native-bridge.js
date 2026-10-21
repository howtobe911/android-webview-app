(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.username || location.password || (location.port && location.port !== '443') || !['second.risedie.ru','www.second.risedie.ru'].includes(location.hostname)) return;
  if (!window.GrafitNative || window.__grafitSecureBridgeInstalled) return;
  window.__grafitSecureBridgeInstalled = true;
  const documentToken = __DOCUMENT_JSON__;
  const pending = new Map();
  let sequence = 0, closed = false;
  function cancelPending() {
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(new Error('DOCUMENT_CHANGED')); }
    pending.clear();
  }
  GrafitNative.onmessage = function (event) {
    let response; try { response = JSON.parse(event.data); } catch (_) { return; }
    const entry = pending.get(response.id); if (!entry) return;
    pending.delete(response.id); clearTimeout(entry.timer);
    if (response.ok) entry.resolve(response.result); else entry.reject(new Error(response.error || 'NATIVE_REQUEST_FAILED'));
  };
  function call(method, args) {
    if (method === 'clearAuthSession') cancelPending();
    if (closed || pending.size >= 32) return Promise.reject(new Error('BRIDGE_UNAVAILABLE'));
    return new Promise(function (resolve, reject) {
      const id = 'native:' + (++sequence);
      const timer = setTimeout(function () { pending.delete(id); reject(new Error('NATIVE_TIMEOUT')); }, 30000);
      pending.set(id, {resolve, reject, timer});
      try { GrafitNative.postMessage(JSON.stringify({document: documentToken, id, method, args})); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  }
  const bridge = {};
  ["getHealthSyncLog", "clearHealthSyncLog", "shareHealthSyncLog", "getBridgeInfo", "getActivityPrerequisites", "getBackgroundActivityReadiness", "openBackgroundActivitySettings", "getBatteryOptimizationState", "openBatterySettingsFor", "performActivityPrerequisiteAction", "authenticate", "clearAuthSession", "refreshAccessToken", "configureNativeHealthSync", "clearNativePushRegistration", "resetLiveAnchorFromServer", "triggerNativeHealthSync", "requestForegroundSync", "getPermissionState", "requestActivityPermissions", "requestPhysicalActivityPermission", "requestHealthSourcePermissions", "requestBackgroundReadPermission", "requestNotificationPermission", "getLiveActivitySnapshot", "openHealthConnectSettings", "openKnownHealthApp"].forEach(function (method) { bridge[method] = function () { return call(method, Array.from(arguments)); }; });
  window.ChallengeAppBridge = Object.freeze(bridge);
  window.addEventListener('pagehide', function () { closed = true; cancelPending(); });
  // BFCache documents need a new native document binding, so force a real reload on restoration.
  window.addEventListener('pageshow', function (event) { if (event.persisted) location.reload(); });
  window.dispatchEvent(new CustomEvent('grafit-native-bridge-ready'));
})();
