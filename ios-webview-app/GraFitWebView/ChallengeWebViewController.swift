import Foundation
import HealthKit
import UIKit
import WebKit
import CryptoKit

// WKUserContentController retains its handlers. A weak forwarding handler breaks that cycle.
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}

final class ChallengeWebViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    private let webView: WKWebView
    private let healthStore = HKHealthStore()
    private let appUrl = URL(string: "https://second.risedie.ru/web")!
    private var documentID: String?
    private var sessionEpoch = 0
    private var authenticated = false
    private var permissionPending = false
    private var authorizationReviewed = false
    private var permissionEpoch = 0
    private lazy var reader = HealthActivityReader(store: healthStore)
    private let outbox = HealthSyncOutbox()
    private var syncOwner: HealthSyncOutbox.Owner?
    private var outboxBlocked = false
    private var pending: Set<String> = []
    private var readRequest: String?
    private struct Request {
        let id: String
        let document: String
        let origin: String
        let epoch: Int
    }

    init() {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.websiteDataStore = .default()
        configuration.applicationNameForUserAgent = "GraFitWebView/1.2"
        self.webView = WKWebView(frame: .zero, configuration: configuration)
        super.init(nibName: nil, bundle: nil)
        webView.configuration.userContentController.add(WeakScriptHandler(self), name: "grafitBridge")
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func loadView() { view = webView }
    override func viewDidLoad() {
        super.viewDidLoad()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        NotificationCenter.default.addObserver(self, selector: #selector(appDidBecomeActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        webView.load(URLRequest(url: appUrl, cachePolicy: .useProtocolCachePolicy, timeoutInterval: 30))
    }
    deinit {
        NotificationCenter.default.removeObserver(self)
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "grafitBridge")
        reader.cancel()
    }

    private func endSession() {
        authenticated = false
        sessionEpoch += 1
        reader.cancel()
        syncOwner = nil
        readRequest = nil
        pending.removeAll()
    }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        documentID = nil
        endSession()
    }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard TrustedWebOrigin.canonical(webView.url) != nil, documentID == nil,
              let path = Bundle.main.path(forResource: "BridgeBootstrap", ofType: "js"),
              let source = try? String(contentsOfFile: path, encoding: .utf8) else { return }
        let id = UUID().uuidString
        documentID = id
        webView.evaluateJavaScript(source.replacingOccurrences(of: "__DOCUMENT_JSON__", with: json(id)), completionHandler: nil)
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { documentID = nil; endSession() }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { documentID = nil; endSession() }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { documentID = nil; endSession(); webView.reload() }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        guard action.targetFrame?.isMainFrame != false else { decisionHandler(.cancel); return }
        if TrustedWebOrigin.canonical(url) != nil {
            if action.targetFrame == nil { decisionHandler(.cancel); webView.load(action.request) }
            else { decisionHandler(.allow) }
            return
        }
        // No automatic external launch from redirects, subframes or javascript: URLs.
        if action.navigationType == .linkActivated && TrustedWebOrigin.mayOpenExternally(url) {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        decisionHandler(.cancel)
    }
    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        decisionHandler(response.isForMainFrame && TrustedWebOrigin.canonical(response.response.url) != nil ? .allow : .cancel)
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        let frame = message.frameInfo
        let security = frame.securityOrigin
        guard message.webView === webView, frame.isMainFrame,
              security.protocol.lowercased() == "https", security.port == 0 || security.port == 443,
              TrustedWebOrigin.hosts.contains(security.host.lowercased()),
              let origin = TrustedWebOrigin.canonical(webView.url),
              origin == "https://" + security.host.lowercased(),
              TrustedWebOrigin.canonical(frame.request.url) == origin,
              let document = documentID, let body = message.body as? [String: Any],
              body["document"] as? String == document,
              let id = body["id"] as? String, id.range(of: "^[A-Za-z0-9:_-]{1,128}$", options: .regularExpression) != nil,
              let method = body["method"] as? String else { return }
        let args = body["args"] as? [Any] ?? []
        if method == "clearAuthSession" || (method == "setWebSessionState" && (args.first as? Bool) != true) { endSession() }
        guard pending.count < 32, !pending.contains(id) else { return }
        if method == "setWebSessionState" && (args.first as? Bool) == true {
            if authenticated { endSession() }
            authenticated = true
        }
        let request = Request(id: id, document: document, origin: origin, epoch: sessionEpoch)
        pending.insert(id)
        DispatchQueue.main.asyncAfter(deadline: .now() + 45) { [weak self] in
            guard let self, self.current(request), self.pending.contains(id) else { return }
            if self.readRequest == id {
                self.reader.cancel(); self.readRequest = nil
            }
            self.respond(request, ["code":"NATIVE_TIMEOUT"], ok:false)
        }
        switch method {
        case "getBridgeInfo": respond(request, ["bridge":"ChallengeAppBridge", "platform":"ios", "available":HKHealthStore.isHealthDataAvailable(), "preferred_source":"apple_healthkit", "provider_type":"apple_healthkit", "durable_sync_version":1, "running_distance":"running_workouts"])
        case "setWebSessionState", "clearAuthSession":
            if authenticated { respond(request, ["ok":true]) }
            else { purgeOutbox(request) }
        default:
            guard authenticated else { respond(request, ["code":"AUTH_REQUIRED"], ok: false); return }
            switch method {
            case "getActivityPrerequisites": respond(request, prerequisites())
            case "getPermissionState": respond(request, permissionState())
            case "requestActivityPermissions": requestPermissions(request)
            case "getPendingHealthSync": loadPending(request, rawOwner: args.first as? String)
            case "acknowledgeHealthSync", "failHealthSync", "expireHealthSync":
                updateOutbox(request, method: method, raw: args.first as? String)
            case "getActivitySyncPayload": readActivity(request, rawWindow: args.first as? String)
            default: respond(request, ["code":"UNKNOWN_METHOD"], ok: false)
            }
        }
    }

    private func current(_ request: Request) -> Bool {
        documentID == request.document && sessionEpoch == request.epoch && TrustedWebOrigin.canonical(webView.url) == request.origin
    }
    private func json(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        return text
    }
    private func respond(_ request: Request, _ payload: [String: Any], ok: Bool = true) {
        guard current(request), pending.contains(request.id) else { return }
        pending.remove(request.id)
        let response: [String: Any] = ["document":request.document,"id":request.id,"ok":ok,"result":payload]
        webView.evaluateJavaScript("window.__grafitNativeResolve && window.__grafitNativeResolve(" + json(response) + ");", completionHandler: nil)
    }
    private func dispatch(_ name: String, _ payload: [String: Any]) {
        guard let document = documentID, authenticated, TrustedWebOrigin.canonical(webView.url) != nil else { return }
        webView.evaluateJavaScript("window.__grafitNativeEvent && window.__grafitNativeEvent(" + json(document) + "," + json(name) + "," + json(payload) + ");", completionHandler: nil)
    }
    private func prerequisites() -> [String: Any] {
        let available = HKHealthStore.isHealthDataAvailable()
        return ["platform":"ios","available":available,"ready":available,"blocking":!available,"read_authorization":"unknown", "healthkit":["status":available ? "available" : "unsupported","available":available],"missing":available ? [] : ["healthkit"]]
    }
    private func permissionState() -> [String: Any] {
        // HealthKit intentionally does not reveal read authorization. Completion is not permission.
        return ["available":HKHealthStore.isHealthDataAvailable(),"granted":NSNull(),"read_authorization":"unknown","authorization_request_completed":authorizationReviewed,"can_query":HKHealthStore.isHealthDataAvailable(),"pending":permissionPending]
    }
    @objc private func appDidBecomeActive() {
        dispatch("challengeapp:prerequisites-changed", prerequisites())
        dispatch("challengeapp:permissions-changed", permissionState())
        dispatch("challengeapp:sync-resume", [:])
    }

    private func requestPermissions(_ request: Request) {
        guard !permissionPending else { respond(request, permissionState()); return }
        guard HKHealthStore.isHealthDataAvailable(),
              let steps = HKQuantityType.quantityType(forIdentifier: .stepCount),
              let distance = HKQuantityType.quantityType(forIdentifier: .distanceWalkingRunning) else {
            respond(request, ["available":false,"granted":false,"pending":false]); return
        }
        permissionPending = true
        permissionEpoch += 1
        let flow = permissionEpoch
        let readTypes: Set<HKObjectType> = [steps, distance, HKObjectType.workoutType()]
        healthStore.requestAuthorization(toShare: [], read: readTypes) { [weak self] completed, error in
            DispatchQueue.main.async {
                guard let self, flow == self.permissionEpoch else { return }
                self.permissionPending = false
                self.authorizationReviewed = completed && error == nil
                guard self.current(request) else { return }
                var payload = self.permissionState()
                payload["authorization_request_completed"] = completed && error == nil
                payload["message"] = completed ? "Настройка доступа завершена. Apple не раскрывает приложению, какие разрешения на чтение выбраны." : "Не удалось завершить настройку Apple Health. Повтори запрос."
                self.respond(request, payload)
                self.dispatch("challengeapp:permissions-changed", payload)
            }
        }
    }

    private func decode(_ raw: String?, limit: Int = 8192) -> [String: Any]? {
        guard let raw, raw.utf8.count <= limit, let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
    private func purgeOutbox(_ request: Request) {
        outboxBlocked = true
        outbox.perform({ try $0.purge() }) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success: self.outboxBlocked = false; self.respond(request, ["ok":true])
            case .failure: self.respond(request, ["code":"OUTBOX_CLEAR_FAILED"], ok:false)
            }
        }
    }
    private func loadPending(_ request: Request, rawOwner: String?) {
        if outboxBlocked {
            // A logout purge may have failed while iOS Data Protection was locked.
            // Complete it before allowing a new account to bind or read any packet.
            outbox.perform({ try $0.purge() }) { [weak self] result in
                guard let self, self.current(request) else { return }
                if case .success = result { self.outboxBlocked = false; self.loadPending(request, rawOwner: rawOwner) }
                else { self.respond(request, ["code":"OUTBOX_CLEAR_FAILED"], ok:false) }
            }
            return
        }
        guard let context = decode(rawOwner, limit: 2048),
              let user = context["user_id"] as? NSNumber, user.int64Value > 0,
              let source = context["source_id"] as? NSNumber, source.int64Value > 0 else {
            respond(request, ["code":"SYNC_CONTEXT_REQUIRED"], ok:false); return
        }
        let owner = HealthSyncOutbox.Owner(origin: request.origin, userID: user.int64Value, sourceID: source.int64Value)
        if let previous = syncOwner, previous != owner {
            reader.cancel(); readRequest = nil
            respond(request, ["code":"SESSION_CHANGED"], ok:false); return
        }
        syncOwner = owner
        outbox.perform({ store -> [String: Any] in
            if let entry = try store.pending(for: owner) { return ["entry": try entry.bridgeValue()] }
            return ["entry":NSNull()]
        }) { [weak self] result in self?.outboxReply(request, result) }
    }
    private func outboxReply(_ request: Request, _ result: Result<[String: Any], Error>) {
        switch result {
        case .success(let payload): respond(request, payload)
        case .failure(let error):
            let code = (error as? HealthSyncOutbox.Failure)?.rawValue ?? "OUTBOX_STORAGE_UNAVAILABLE"
            respond(request, ["code":code], ok:false)
        }
    }
    private func updateOutbox(_ request: Request, method: String, raw: String?) {
        guard !outboxBlocked, let owner = syncOwner, let args = decode(raw, limit: 2048),
              let id = args["id"] as? String, UUID(uuidString: id) != nil else {
            respond(request, ["code":"SYNC_CONTEXT_REQUIRED"], ok:false); return
        }
        outbox.perform({ store -> [String: Any] in
            if method == "acknowledgeHealthSync" {
                guard args["security_passed"] as? Bool == true,
                      let external = args["external_batch_id"] as? String,
                      let batch = args["batch_id"] as? NSNumber else { throw HealthSyncOutbox.Failure.mismatch }
                try store.acknowledge(owner: owner, id: id, externalID: external, batchID: batch.int64Value)
                return ["ok":true]
            }
            if method == "expireHealthSync" {
                guard args["code"] as? String == "HEALTHKIT_WINDOW_EXPIRED" else { throw HealthSyncOutbox.Failure.mismatch }
                try store.expire(owner: owner, id: id)
                return ["ok":true]
            }
            let code = args["code"] as? String ?? "NETWORK_ERROR"
            let retry = (args["retry_after_seconds"] as? NSNumber)?.doubleValue ?? 0
            let entry = try store.failed(owner: owner, id: id, code: code, retryAfter: retry.isFinite ? retry : 0)
            return ["entry":try entry.bridgeValue()]
        }) { [weak self] result in self?.outboxReply(request, result) }
    }
    private func readActivity(_ request: Request, rawWindow: String?) {
        guard readRequest == nil else { respond(request, ["code":"READ_IN_PROGRESS"], ok:false); return }
        guard !outboxBlocked, let owner = syncOwner,
              HKHealthStore.isHealthDataAvailable(), let window = decode(rawWindow),
              (window["user_id"] as? NSNumber)?.int64Value == owner.userID,
              let ticket = window["healthkit_window_token"] as? String, !ticket.isEmpty,
              let from = window["window_from_utc"] as? String, let to = window["window_to_utc"] as? String,
              let start = isoDate(from), let end = isoDate(to), end > start, end.timeIntervalSince(start) <= 86400,
              window["server_timezone"] as? String == "UTC" else {
            respond(request, ["code":"HEALTHKIT_WINDOW_REQUIRED"], ok:false); return
        }
        readRequest = request.id
        reader.read(start: start, end: end) { [weak self] result in
            guard let self, self.current(request), self.authenticated, self.pending.contains(request.id), self.readRequest == request.id else { return }
            self.finishRead(request, owner: owner, window: window, from: from, to: to, result: result)
        }
    }
    private func isoDate(_ text: String) -> Date? {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return parser.date(from: text) ?? ISO8601DateFormatter().date(from: text)
    }
    private func finishRead(_ request: Request, owner: HealthSyncOutbox.Owner, window: [String: Any], from: String, to: String, result: HealthActivityReader.Result) {
        let values = result.values
        let day = window["server_day"] as? String ?? window["activity_date"] as? String ?? String(from.prefix(10))
        let seed = "running-workouts-v2|" + from + "|" + to + "|" + values.keys.sorted().map { $0 + ":" + String(values[$0]!) }.joined(separator:"|")
        let batchID = "ios-" + SHA256.hash(data: Data(seed.utf8)).map { String(format:"%02x", $0) }.joined()
        let records: [[String: Any]] = values.keys.sorted().map { name in
            ["activity_type":name == "steps" ? "walk" : "run","metric_type":name,"value":values[name]!,"recorded_from":from,"recorded_to":to,"window_from_utc":from,"window_to_utc":to,"source_hash":batchID + "-" + name]
        }
        let generated = ISO8601DateFormatter().string(from: Date())
        let batch: [String: Any] = ["kind":"apple_healthkit_aggregate","external_batch_id":batchID,"generated_at":generated,"device_time":generated,"server_day":day,"activity_date":day,"server_timezone":"UTC","window_from_utc":from,"window_to_utc":to,"records":records]
        let payload: [String: Any] = ["batches":records.isEmpty ? [] : [batch],"generated_at":generated,"preferred_source":"apple_healthkit",
            "partial":!result.unavailable.isEmpty || !result.warnings.isEmpty || values.count < 2,
            "unavailable_metrics":result.unavailable.sorted(), "warnings":result.warnings.sorted(),
            "read_authorization":"unknown", "running_distance":"running_workouts",
            "message":records.isEmpty ? "Нет доступных записей Apple Health. Для дистанции нужна тренировка Бег и доступ к тренировкам." : "Доступные показатели прочитаны."]
        if records.isEmpty { readRequest = nil; respond(request, payload); return }
        outbox.perform({ store -> [String: Any] in
            let saved = try store.save(owner: owner, payload: payload, window: window)
            return ["entry":try saved.bridgeValue()]
        }) { [weak self] response in
            guard let self, self.current(request), self.readRequest == request.id else { return }
            self.readRequest = nil
            self.outboxReply(request, response)
        }
    }
}
