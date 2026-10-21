#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
checks = 0
errors: list[str] = []


def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing source: {rel}")
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def contains(rel: str, *needles: str) -> None:
    global checks
    source = text(rel)
    for needle in needles:
        checks += 1
        if needle not in source:
            errors.append(f"{rel}: missing {needle!r}")


def excludes(rel: str, *needles: str) -> None:
    global checks
    source = text(rel)
    for needle in needles:
        checks += 1
        if needle in source:
            errors.append(f"{rel}: forbidden {needle!r}")


contains(
    "app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt",
    "TYPE_STEP_COUNTER is local UI overlay only",
    "source_of_truth\", \"native_live_ui",
    "hard_reset_truth_increased",
    "hard_reset_new_server_day",
    "raw_counter_reset",
    "return safeHealthSteps",
)
excludes(
    "app/src/main/java/com/second/risedie/challengeapp/health/LiveStepTracker.kt",
    "LocalDate.now(",
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt",
    "StepsRecord::class",
    "DistanceRecord::class",
    "ExerciseSessionRecord::class",
    '.put("preferred_source", "health_connect")',
    '.put("source_of_truth", "health_connect_day_close_snapshot")',
    '.put("kind", "activity_detail_windows")',
)
excludes(
    "app/src/main/java/com/second/risedie/challengeapp/health/HealthConnectRepository.kt",
    "Sensor.TYPE_STEP_COUNTER",
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt",
    "private val processSyncMutex = Mutex()",
    "processSyncMutex.withLock",
    "fetchSyncWindow",
    "buildFreshServerWindowSyncPayload",
    'require(syncWindow.serverTimezone == "UTC")',
    "server_processing_queued",
    "postPreviousDayCloseSnapshotIfNeeded",
    "postPendingDetailRequests",
)
excludes(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncCoordinator.kt",
    "LiveStepTracker",
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncApiClient.kt",
    "TrustedWebOrigin.isTrustedUrl(url)",
    "instanceFollowRedirects = false",
    "/api/v1/me/activity/payload-nonce",
    'body.put("nonce", nonce)',
    'body.put("payload_signature"',
    "HmacSHA256",
    "code in 200..299",
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncWorker.kt",
    "HealthSyncCoordinator(applicationContext)",
    "NetworkType.CONNECTED",
    "enqueueUniqueWork",
    "enqueueUniquePeriodicWork",
    "ExistingPeriodicWorkPolicy.KEEP",
    "Result.retry()",
    "Result.failure()",
)
excludes(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncWorker.kt",
    "HealthConnectClient.getOrCreate",
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/security/SecureCredentialStore.kt",
    "AndroidKeyStore",
    "AES/GCM/NoPadding",
    "setRandomizedEncryptionRequired(true)",
    "KEY_REFRESH_CIPHERTEXT",
    "clearLegacyTokens()",
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/security/NativeAuthSession.kt",
    "SecureCredentialStore",
    "TrustedWebOrigin",
)
excludes(
    "app/src/main/java/com/second/risedie/challengeapp/security/NativeAuthSession.kt",
    'getSharedPreferences("refresh',
)
excludes(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncLogger.kt",
    'access_token"',
    'refresh_token"',
    'Authorization"',
)
contains(
    "app/src/main/java/com/second/risedie/challengeapp/sync/HealthSyncLogger.kt",
    "trace_id",
)
contains(
    "app/build.gradle.kts",
    'create("instrumentation")',
    'testBuildType = "instrumentation"',
    'androidTestImplementation("androidx.test.ext:junit:1.2.1")',
    'androidTestImplementation("androidx.test:core-ktx:1.6.1")',
)
contains(
    "app/src/androidTest/java/com/second/risedie/challengeapp/security/AppSecurityInstrumentedTest.kt",
    "AndroidJUnit4::class",
    "TrustedWebOrigin.isTrustedUrl(BuildConfig.APP_WEB_URL)",
    "isCleartextTrafficPermitted",
)
contains(
    "app/src/androidTest/java/com/second/risedie/challengeapp/health/HealthConnectManifestInstrumentedTest.kt",
    "android.permission.health.READ_STEPS",
    "android.permission.health.READ_DISTANCE",
    "android.permission.health.READ_EXERCISE",
    "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
)
contains(
    "app/src/androidTest/java/com/second/risedie/challengeapp/health/LiveActivityOverlayStoreInstrumentedTest.kt",
    "LiveActivityOverlayStore(context)",
    "store.write(state)",
    "val restored = store.read()",
    "assertEquals(state.displaySteps, restored.displaySteps)",
)
contains(
    "app/src/androidTest/java/com/second/risedie/challengeapp/health/HealthSyncCanonicalInstrumentedTest.kt",
    'getDeclaredMethod(\n            "canonicalActivityPayload"',
    'getDeclaredMethod(\n            "hmacSha256Hex"',
    "6ee5c6b71fbe0bcf98db8e5d429838571b6d1c4030c004263cf928595b90068b",
)

if errors:
    print("Android source contracts: FAILED")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print(f"Android source contracts: OK ({checks} checks)")
