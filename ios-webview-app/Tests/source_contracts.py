#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
policy = (ROOT / 'GraFitWebView/TrustedWebOrigin.swift').read_text(encoding='utf-8')
controller = (ROOT / 'GraFitWebView/ChallengeWebViewController.swift').read_text(encoding='utf-8')
reader = (ROOT / 'GraFitWebView/HealthActivityReader.swift').read_text(encoding='utf-8')
app_delegate = (ROOT / 'GraFitWebView/AppDelegate.swift').read_text(encoding='utf-8')
failures = []
checks = 0

def require(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        failures.append(message)

require('URL(string: "https://second.risedie.ru/web")' in controller, 'webview entrypoint must use HTTPS')
require('parts.scheme?.lowercased() == "https"' in policy, 'navigation policy must require HTTPS')
require('hosts.contains(host)' in policy, 'navigation policy must enforce the host allowlist')
require('decisionHandler(.cancel)' in controller, 'untrusted navigation must be cancelled in WebView')
require('UIApplication.shared.open(url' in controller, 'external navigation must leave the trusted WebView context')

handlers = ['requestActivityPermissions', 'getActivitySyncPayload', 'getBridgeInfo', 'getActivityPrerequisites']
for handler in handlers:
    require(f'"{handler}"' in controller, f'native bridge must explicitly expose {handler}')
require('"UNKNOWN_METHOD"' in controller, 'unknown bridge method must not silently execute privileged behavior')
require('json(response)' in controller and 'A-Za-z0-9:_-' in controller, 'request id must be escaped before JavaScript interpolation')
require('JSONSerialization.data(withJSONObject:' in controller, 'native payload must be serialized as JSON')

require('HKHealthStore.isHealthDataAvailable()' in controller, 'HealthKit availability must gate health operations')
require('.stepCount' in controller, 'HealthKit step count must be read')
require('.distanceWalkingRunning' in controller, 'HealthKit distance must be read')
require('options: .cumulativeSum' in reader, 'HealthKit aggregate reads must use cumulative sums')
require('preferred_source":"apple_healthkit"' in controller, 'HealthKit must be declared as the authoritative iOS source')
require('provider_type":"apple_healthkit"' in controller, 'bridge provider type must identify Apple HealthKit')
require('native_sensor' not in controller, 'iOS confirmed payload must not masquerade as native sensor truth')
require('window_from_utc' in controller and 'window_to_utc' in controller, 'health payload must carry explicit UTC windows')
require('external_batch_id' in controller, 'health batches need stable external IDs for idempotency')
require('source_hash' in controller, 'health records need source hashes for dedupe')
require('NotificationCenter.default.removeObserver(self)' in controller, 'controller must remove lifecycle observers')
for handler in handlers:
    require('removeScriptMessageHandler(forName: "grafitBridge")' in controller, 'bridge handlers must be removed on deinit')
    break

require('window.rootViewController = ChallengeWebViewController()' in app_delegate, 'app must boot into the hardened WebView controller')

require('frame.isMainFrame' in controller and 'frame.securityOrigin' in controller, 'native frame and origin must both be checked')
require('WeakScriptHandler(self)' in controller and 'weak var target' in controller, 'handlers must not retain controller')
require(controller.index('super.init(nibName:') < controller.index('WeakScriptHandler(self)'), 'handler registration must follow super.init')
require('granted":NSNull()' in controller, 'HealthKit read permission must not be inferred from authorization completion')

if failures:
    print(f'iOS source contracts: FAIL ({len(failures)}/{checks})')
    for item in failures:
        print(f' - {item}')
    sys.exit(1)

print(f'iOS source contracts: OK ({checks} checks)')
