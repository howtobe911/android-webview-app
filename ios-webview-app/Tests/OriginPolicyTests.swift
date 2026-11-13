import Foundation
@main struct OriginPolicyTests {
    static func main() {
        for value in ["https://second.risedie.ru/web", "https://www.second.risedie.ru:443/web"] { precondition(TrustedWebOrigin.canonical(URL(string:value)) != nil, value) }
        for value in ["http://second.risedie.ru/", "https://second.risedie.ru:8443/", "https://user@second.risedie.ru/", "https://second.risedie.ru.evil.test/", "file:///tmp/a", "about:blank", "https://localhost/", "https://second.risedie.ru./"] { precondition(TrustedWebOrigin.canonical(URL(string:value)) == nil, value) }
        precondition(!TrustedWebOrigin.mayOpenExternally(URL(string:"javascript:alert(1)")!))
        print("Swift origin policy: passed")
    }
}
