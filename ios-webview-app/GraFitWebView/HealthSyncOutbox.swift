import Foundation
import CryptoKit

// One frozen packet per authenticated origin/account/source. File I/O is serialized
// off the UI thread. A callback is delivered only AFTER the atomic write completes.
final class HealthSyncOutbox {
    struct Owner: Codable, Equatable {
        let origin: String
        let userID: Int64
        let sourceID: Int64
        var key: String {
            SHA256.hash(data: Data("\(origin)|\(userID)|\(sourceID)".utf8)).map { String(format: "%02x", $0) }.joined()
        }
    }
    struct Entry: Codable {
        let id: String
        let owner: Owner
        let payload: Data
        let window: Data
        let createdAt: Date
        var attempts = 0
        var nextAttempt = Date(timeIntervalSince1970: 0)
        var lastError: String?
        func bridgeValue(now: Date = Date()) throws -> [String: Any] {
            let delay = nextAttempt.timeIntervalSince(now)
            // A backwards device-clock change must not postpone retries for days.
            let milliseconds = delay.isFinite && delay > 0 && delay <= 3600 ? Int(delay * 1000) : 0
            return ["id": id, "payload": try JSONSerialization.jsonObject(with: payload),
             "window": try JSONSerialization.jsonObject(with: window), "attempts": attempts,
             "retry_after_ms": milliseconds,
             "last_error": lastError as Any? ?? NSNull()]
        }
    }
    struct State: Codable {
        var version = 1
        var entries: [String: Entry] = [:]
    }
    enum Failure: String, Error {
        case corrupt = "OUTBOX_CORRUPT", full = "OUTBOX_FULL", mismatch = "OUTBOX_MISMATCH"
    }
    private let queue = DispatchQueue(label: "ru.risedie.grafit.health-outbox", qos: .utility)
    private let directory: URL
    private let file: URL
    init(directory: URL? = nil) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        self.directory = directory ?? support.appendingPathComponent("HealthDelivery", isDirectory: true)
        file = self.directory.appendingPathComponent("outbox-v1.json")
    }

    func perform<T>(_ operation: @escaping (HealthSyncOutbox) throws -> T, completion: @escaping (Result<T, Error>) -> Void) {
        queue.async {
            let result = Result { try operation(self) }
            DispatchQueue.main.async { completion(result) }
        }
    }

    // Only call the methods below from perform. No in-memory cache: failed writes,
    // locked files and corruption can never masquerade as an empty/saved outbox.
    func pending(for owner: Owner) throws -> Entry? {
        let entry = try load().entries[owner.key]
        guard entry == nil || entry?.owner == owner else { throw Failure.mismatch }
        return entry
    }
    func save(owner: Owner, payload: [String: Any], window: [String: Any]) throws -> Entry {
        var state = try load()
        if let entry = state.entries[owner.key] {
            guard entry.owner == owner else { throw Failure.mismatch }
            return entry // Never overwrite a packet whose delivery is uncertain.
        }
        guard state.entries.count < 16 else { throw Failure.full }
        let entry = Entry(id: UUID().uuidString, owner: owner,
                          payload: try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
                          window: try JSONSerialization.data(withJSONObject: window, options: [.sortedKeys]), createdAt: Date())
        state.entries[owner.key] = entry
        try write(state)
        return entry
    }
    func acknowledge(owner: Owner, id: String, externalID: String, batchID: Int64) throws {
        guard batchID > 0 else { throw Failure.mismatch }
        var state = try load()
        guard let entry = state.entries[owner.key] else { return } // Idempotent ACK.
        let payload = try JSONSerialization.jsonObject(with: entry.payload) as? [String: Any]
        let batches = payload?["batches"] as? [[String: Any]]
        guard entry.owner == owner, entry.id == id, batches?.first?["external_batch_id"] as? String == externalID else {
            throw Failure.mismatch
        }
        state.entries.removeValue(forKey: owner.key)
        try write(state)
    }
    func failed(owner: Owner, id: String, code: String, retryAfter: Double) throws -> Entry {
        var state = try load()
        guard var entry = state.entries[owner.key], entry.owner == owner, entry.id == id else { throw Failure.mismatch }
        entry.attempts = min(entry.attempts + 1, 10000)
        entry.lastError = String(code.prefix(80))
        let backoff = min(300.0, 15.0 * pow(2.0, Double(min(entry.attempts - 1, 5))))
        entry.nextAttempt = Date().addingTimeInterval(max(backoff, min(3600, max(0, retryAfter))))
        state.entries[owner.key] = entry
        try write(state)
        return entry
    }
    func expire(owner: Owner, id: String) throws {
        var state = try load()
        guard let entry = state.entries[owner.key] else { return }
        guard entry.owner == owner, entry.id == id else { throw Failure.mismatch }
        state.entries.removeValue(forKey: owner.key)
        try write(state)
    }
    func purge() throws {
        do { try FileManager.default.removeItem(at: file) }
        catch { if !isMissing(error) { throw error } }
    }
    private func load() throws -> State {
        let handle: FileHandle
        do { handle = try FileHandle(forReadingFrom: file) }
        catch { if isMissing(error) { return State() }; throw error }
        defer { try? handle.close() }
        let data = try handle.read(upToCount: 512 * 1024 + 1) ?? Data()
        guard data.count <= 512 * 1024 else { throw Failure.corrupt }
        guard let state = try? JSONDecoder().decode(State.self, from: data), state.version == 1,
              state.entries.count <= 16,
              state.entries.allSatisfy({ $0.key == $0.value.owner.key && $0.value.payload.count <= 32768 && $0.value.window.count <= 8192 }) else {
            throw Failure.corrupt
        }
        return state
    }
    private func isMissing(_ error: Error) -> Bool {
        let code = error as NSError
        return code.domain == NSCocoaErrorDomain && [NSFileNoSuchFileError, NSFileReadNoSuchFileError].contains(code.code)
    }
    private func write(_ state: State) throws {
        let data = try JSONEncoder().encode(state)
        guard data.count <= 512 * 1024 else { throw Failure.full }
        var attributes: [FileAttributeKey: Any] = [.posixPermissions: 0o700]
        #if os(iOS)
        attributes[.protectionKey] = FileProtectionType.complete
        #endif
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: attributes)
        var protectedDirectory = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try protectedDirectory.setResourceValues(values)
        var options: Data.WritingOptions = [.atomic]
        #if os(iOS)
        options.insert(.completeFileProtection)
        #endif
        try data.write(to: file, options: options)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
}
