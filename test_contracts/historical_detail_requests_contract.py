#!/usr/bin/env python3
"""Static cross-component regression guards; native instrumentation remains necessary."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
api=(r/'app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt').read_text()
coordinator=(r/'app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt').read_text()
checks={
  'historical fetch has no current-day filter': '/api/v1/me/activity/detail-requests?limit=100' in api and 'detail-requests?activity_date=' not in api,
  'API fetch call updated': 'fetchDetailRequests(config, sessionId)' in coordinator,
  'still attempts historical details if current batches absent': 'no_current_batches_detail_requests_still_checked' in coordinator and coordinator.index('postPendingDetailRequests(config, syncWindow, sessionId)') < coordinator.index('if (batches.length() == 0 && posted == 0)'),
  'no-data UI result preserved': 'return@withContext noData(reason, sessionId, syncWindow, payload.optString(' in coordinator,
  'detail transport still uses signed current server-day': '.put("server_day", syncWindow.serverDay)' in coordinator and 'postSourceSync(config, body, syncWindow.serverDay, sessionId)' in coordinator,
  'backend max 2000 records per batch respected': 'step 2000' in coordinator and 'offset + 2000' in coordinator,
  'unique id for each chunk': 'offset / 2000' in coordinator,
  'historical records preserve original exact timestamps': 'repository.buildDetailWindowsPayload(requests' in coordinator,
}
for k,v in checks.items():
 print(('PASS' if v else 'FAIL')+': '+k)
assert all(checks.values()), 'historical detail sync contract violated'
print('Historical-detail regression: %d checks passed' % len(checks))
