#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v xcodebuild >/dev/null || ! xcrun --find swiftc >/dev/null 2>&1; then
    echo 'BLOCKED: Xcode with iOS SDK and Swift compiler is required. No native build was performed.' >&2
    exit 2
fi
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/grafit-ios-verify.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
xcodebuild -project GraFitWebView.xcodeproj -scheme GraFitWebView -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$build_dir/build" CODE_SIGNING_ALLOWED=NO build
xcrun swiftc -swift-version 5 GraFitWebView/TrustedWebOrigin.swift Tests/OriginPolicyTests.swift -o "$build_dir/origin-tests"
"$build_dir/origin-tests"
xcrun swiftc -swift-version 5 GraFitWebView/RunningDistancePolicy.swift GraFitWebView/HealthSyncOutbox.swift \
    Tests/ReliabilityTests.swift -o "$build_dir/reliability-tests"
"$build_dir/reliability-tests"
echo 'Build and core tests completed. Run the iPhone scenarios in Tests/DEVICE_ACCEPTANCE.md before release.'
