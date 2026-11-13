import Foundation

@main struct ReliabilityTests {
    enum TestFailure: Error { case assertion(String), timeout }
    static var assertions = 0
    static func expect(_ condition: @autoclosure () -> Bool, _ message: String) throws {
        assertions += 1
        if !condition() { throw TestFailure.assertion(message) }
    }
    static func io<T>(_ store: HealthSyncOutbox, _ operation: @escaping (HealthSyncOutbox) throws -> T) throws -> T {
        var result: Result<T, Error>?
        store.perform(operation) { result = $0 }
        let deadline = Date().addingTimeInterval(10)
        while result == nil && Date() < deadline { _ = RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01)) }
        guard let result else { throw TestFailure.timeout }
        return try result.get()
    }
    static func main() throws {
        let time = Date(timeIntervalSince1970: 1000)
        func run(_ id: String, _ start: Double, _ end: Double, _ meters: Double) -> RunningDistancePolicy.Run {
            .init(id: id, start: time.addingTimeInterval(start), end: time.addingTimeInterval(end), meters: meters)
        }
        let first = run("watch", 0, 100, 500), duplicate = run("import", 0, 100, 500), next = run("next", 100, 200, 600)
        try expect(RunningDistancePolicy.total([first, next]).meters == 1100, "Adjacent runs must both count")
        let deduped = RunningDistancePolicy.total([first, duplicate, next])
        try expect(deduped.meters == 1100 && deduped.overlappingWorkouts, "Imported copy must not double distance")
        try expect(RunningDistancePolicy.total([next, duplicate, first]).meters == deduped.meters, "Query order must not change totals")
        try expect(RunningDistancePolicy.total([first, first]).meters == 500, "Duplicate UUID")
        try expect(RunningDistancePolicy.total([run("bad", 0, 10, .nan), run("negative", 0, 10, -1)]).meters == nil, "Invalid values omitted")
        try expect(RunningDistancePolicy.total([]).meters == nil, "No available data is not a proven zero")
        try expect(RunningDistancePolicy.total([run("long", 0, 150, 700), first]).meters == 700, "Partial overlaps are not blindly added")
        try expect(RunningDistancePolicy.total([run("round", 0, 10, 12.345)]).meters == 12.35, "Stable centi-meter rounding")

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("grafit-test-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let owner = HealthSyncOutbox.Owner(origin: "https://second.risedie.ru", userID: 1, sourceID: 7)
        let other = HealthSyncOutbox.Owner(origin: owner.origin, userID: 2, sourceID: 7)
        let store = HealthSyncOutbox(directory: directory)
        let payload: [String: Any] = ["batches": [["external_batch_id":"frozen", "records":[["metric_type":"meters","value":2500]]]]]
        let window: [String: Any] = ["server_day":"2026-10-22", "user_id":1, "healthkit_window_token":"opaque"]
        let saved = try io(store) { try $0.save(owner: owner, payload: payload, window: window) }
        // New object, no shared in-memory state: represents a killed/restarted app.
        let restarted = HealthSyncOutbox(directory: directory)
        let loaded = try io(restarted) { try $0.pending(for: owner) }
        try expect(loaded?.id == saved.id && loaded?.payload == saved.payload && loaded?.window == saved.window, "Frozen packet survives reconstruction")
        let isolated = try io(restarted) { try $0.pending(for: other) }
        try expect(isolated == nil, "Different account cannot read the pending packet")
        let replacement = try io(restarted) { try $0.save(owner: owner, payload: ["batches":[]], window: [:]) }
        try expect(replacement.payload == saved.payload, "Uncertain packet must never be overwritten")
        let failed = try io(restarted) { try $0.failed(owner: owner, id: saved.id, code: "HTTP_503", retryAfter: 120) }
        let reopened = try io(HealthSyncOutbox(directory: directory)) { try $0.pending(for: owner) }
        try expect(reopened?.attempts == 1 && reopened?.nextAttempt == failed.nextAttempt, "Backoff survives restart")
        var rejected = false
        do { try io(restarted) { try $0.acknowledge(owner: owner, id: saved.id, externalID: "wrong", batchID: 11) } }
        catch { rejected = true }
        try expect(rejected, "Mismatched server ACK cannot delete data")
        let stillPending = try io(restarted) { try $0.pending(for: owner) }
        try expect(stillPending != nil, "Failed ACK retains data")
        try io(restarted) { try $0.acknowledge(owner: owner, id: saved.id, externalID: "frozen", batchID: 11) }
        try io(restarted) { try $0.acknowledge(owner: owner, id: saved.id, externalID: "frozen", batchID: 11) }
        let empty = try io(restarted) { try $0.pending(for: owner) }
        try expect(empty == nil, "ACK is durable and idempotent")
        _ = try io(restarted) { try $0.save(owner: owner, payload: payload, window: window) }
        try Data("damaged".utf8).write(to: directory.appendingPathComponent("outbox-v1.json"), options: .atomic)
        rejected = false
        do { _ = try io(restarted) { try $0.pending(for: owner) } } catch { rejected = true }
        try expect(rejected, "Corruption must be visible, never silently treated as empty")
        try io(restarted) { try $0.purge() }
        let afterLogout = try io(restarted) { try $0.pending(for: owner) }
        try expect(afterLogout == nil, "Explicit logout removes even a corrupted packet")
        // A path occupied by a regular file simulates an unavailable storage directory.
        try Data([1]).write(to: directory.appendingPathComponent("blocked"))
        let blocked = HealthSyncOutbox(directory: directory.appendingPathComponent("blocked"))
        rejected = false
        do { _ = try io(blocked) { try $0.save(owner: owner, payload: payload, window: window) } } catch { rejected = true }
        try expect(rejected, "Storage failure cannot acknowledge a successful save")
        print("Swift running policy + real outbox disk I/O: passed (\(assertions) assertions)")
    }
}
