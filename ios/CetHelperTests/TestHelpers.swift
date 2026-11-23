import Foundation
@testable import CetHelper

final class StubURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    private static let lock = NSLock()

    static func setHandler(_ block: ((URLRequest) throws -> (HTTPURLResponse, Data))?) {
        lock.lock()
        handler = block
        lock.unlock()
    }

    static func makeConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return config
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        let currentHandler = Self.handler
        Self.lock.unlock()

        guard let currentHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }

        do {
            let (response, data) = try currentHandler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

func makeJSONResponse(url: URL, status: Int = 200, json: Any) -> (HTTPURLResponse, Data) {
    let response = HTTPURLResponse(
        url: url,
        statusCode: status,
        httpVersion: "HTTP/1.1",
        headerFields: ["Content-Type": "application/json; charset=utf-8"]
    )!
    let data = (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
    return (response, data)
}

func makeSampleFavorite(
    serverUrl: String = "http://127.0.0.1:8080",
    studentId: Int64 = 1,
    articleId: String = DemoArticle.id,
    startOffset: Int = 26,
    endOffset: Int = 33,
    selection: String = "decline",
    sentence: String = "The report attributed the decline to lower demand.",
    meaning: String = "下降",
    explanation: String = "数量或程度下降",
    mode: String = "demo",
    savedAt: Int64 = 1000,
    masteryLevel: Int = 0,
    reviewCount: Int = 0,
    lastReviewedAt: Int64 = 0,
    nextReviewAt: Int64 = 0
) -> Favorite {
    Favorite(
        serverUrl: serverUrl,
        studentId: studentId,
        articleId: articleId,
        startOffset: startOffset,
        endOffset: endOffset,
        selection: selection,
        sentence: sentence,
        meaning: meaning,
        explanation: explanation,
        mode: mode,
        savedAt: savedAt,
        phonetic: "/dɪˈklaɪn/",
        partOfSpeech: "n.",
        masteryLevel: masteryLevel,
        reviewCount: reviewCount,
        lastReviewedAt: lastReviewedAt,
        nextReviewAt: nextReviewAt
    )
}

func readBodyData(from request: URLRequest) -> Data {
    if let body = request.httpBody {
        return body
    }
    guard let stream = request.httpBodyStream else { return Data() }
    stream.open()
    defer { stream.close() }
    var data = Data()
    let bufferSize = 4096
    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
    defer { buffer.deallocate() }
    while stream.hasBytesAvailable {
        let count = stream.read(buffer, maxLength: bufferSize)
        if count > 0 {
            data.append(buffer, count: count)
        } else {
            break
        }
    }
    return data
}
