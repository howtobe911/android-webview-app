import Foundation

enum TrustedWebOrigin {
    static let hosts: Set<String> = ["second.risedie.ru", "www.second.risedie.ru"]
    static func canonical(_ url: URL?) -> String? {
        guard let url, let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https", parts.user == nil, parts.password == nil,
              let host = parts.host?.lowercased(), hosts.contains(host),
              parts.port == nil || parts.port == 443 else { return nil }
        return "https://" + host
    }
    static func mayOpenExternally(_ url: URL) -> Bool {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.user == nil, parts.password == nil else { return false }
        return ["https", "http", "mailto", "tel"].contains(parts.scheme?.lowercased() ?? "")
    }
}
