import Foundation

extension ApiClient {
    func streamTutorChat(
        _ session: Session,
        messages: [TutorMessage],
        context: TutorContext,
        onEvent: @escaping @MainActor (TutorSseEvent) -> Void,
        onError: @escaping @MainActor (Error) -> Void
    ) -> TutorStreamHandle {
        let handle = TutorStreamHandle()
        let recentMessages = Array(messages.suffix(20)).map { msg -> [String: String] in
            ["role": msg.role.rawValue, "content": msg.content]
        }
        let payload: [String: Any] = [
            "messages": recentMessages,
            "context": context.toPayloadDictionary()
        ]
        let serverUrl = session.serverUrl
        let token = session.token
        let streamURLSession = self.streamSession

        let task = Task {
            if handle.isCancelled { return }
            guard let url = URL(string: "\(serverUrl)/api/v1/tutor/chat/stream") else {
                if !handle.isCancelled {
                    await onError(ApiError.connectionFailure)
                }
                return
            }

            let bodyData: Data
            do {
                bodyData = try JSONSerialization.data(withJSONObject: payload, options: [])
            } catch {
                if !handle.isCancelled {
                    await onError(ApiError.malformed)
                }
                return
            }

            var urlRequest = URLRequest(url: url)
            urlRequest.httpMethod = "POST"
            urlRequest.timeoutInterval = 60
            urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            urlRequest.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            urlRequest.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            urlRequest.httpBody = bodyData

            do {
                let (asyncBytes, response) = try await streamURLSession.bytes(for: urlRequest)
                if handle.isCancelled { return }

                guard let http = response as? HTTPURLResponse else {
                    if !handle.isCancelled {
                        await onError(ApiError.connectionFailure)
                    }
                    return
                }

                let status = http.statusCode
                if status < 200 || status >= 300 {
                    var errorData = Data()
                    for try await byte in asyncBytes {
                        if handle.isCancelled { return }
                        errorData.append(byte)
                    }
                    if handle.isCancelled { return }
                    let apiErr = Self.buildHttpError(status: status, data: errorData)
                    await onError(apiErr)
                    return
                }

                var parser = SseLineParser()
                var lineBytes: [UInt8] = []
                lineBytes.reserveCapacity(256)

                for try await byte in asyncBytes {
                    if handle.isCancelled { return }
                    if byte == 0x0A {
                        if lineBytes.last == 0x0D {
                            lineBytes.removeLast()
                        }
                        let line = String(decoding: lineBytes, as: UTF8.self)
                        lineBytes.removeAll(keepingCapacity: true)

                        let outcome = parser.consumeLine(line)
                        if let event = outcome.event {
                            if !handle.isCancelled {
                                await onEvent(event)
                            }
                        } else if let error = outcome.error {
                            if !handle.isCancelled {
                                await onError(error)
                            }
                        }
                        if outcome.terminated || handle.isCancelled {
                            return
                        }
                    } else {
                        lineBytes.append(byte)
                    }
                }

                if handle.isCancelled { return }
                if !lineBytes.isEmpty {
                    if lineBytes.last == 0x0D {
                        lineBytes.removeLast()
                    }
                    let tailLine = String(decoding: lineBytes, as: UTF8.self)
                    let outcome = parser.consumeLine(tailLine)
                    if let event = outcome.event, !handle.isCancelled {
                        await onEvent(event)
                    } else if let error = outcome.error, !handle.isCancelled {
                        await onError(error)
                    }
                    if outcome.terminated || handle.isCancelled {
                        return
                    }
                }

                let eofOutcome = parser.finishAtEOF()
                if let event = eofOutcome.event, !handle.isCancelled {
                    await onEvent(event)
                }
                if let error = eofOutcome.error, !handle.isCancelled {
                    await onError(error)
                }
            } catch is CancellationError {
                return
            } catch let urlError as URLError {
                if handle.isCancelled || urlError.code == .cancelled {
                    return
                }
                await onError(Self.mapURLError(urlError))
            } catch let apiError as ApiError {
                if handle.isCancelled || apiError == .cancelled {
                    return
                }
                await onError(apiError)
            } catch {
                if handle.isCancelled { return }
                await onError(ApiError.connectionFailure)
            }
        }

        handle.attach(task: task)
        return handle
    }

    /// 供单元测试直接喂入原始 SSE 字节流的纯同步解析接缝。
    static func parseTutorSseStream(
        data: Data,
        isCancelled: () -> Bool = { false },
        onEvent: (TutorSseEvent) -> Void,
        onError: (Error) -> Void
    ) {
        var parser = SseLineParser()
        var lineBytes: [UInt8] = []
        lineBytes.reserveCapacity(256)

        for byte in data {
            if isCancelled() { return }
            if byte == 0x0A {
                if lineBytes.last == 0x0D {
                    lineBytes.removeLast()
                }
                let line = String(decoding: lineBytes, as: UTF8.self)
                lineBytes.removeAll(keepingCapacity: true)

                let outcome = parser.consumeLine(line)
                if isCancelled() { return }
                if let event = outcome.event {
                    onEvent(event)
                } else if let error = outcome.error {
                    onError(error)
                }
                if outcome.terminated || isCancelled() {
                    return
                }
            } else {
                lineBytes.append(byte)
            }
        }

        if isCancelled() { return }
        if !lineBytes.isEmpty {
            if lineBytes.last == 0x0D {
                lineBytes.removeLast()
            }
            let tailLine = String(decoding: lineBytes, as: UTF8.self)
            let outcome = parser.consumeLine(tailLine)
            if isCancelled() { return }
            if let event = outcome.event {
                onEvent(event)
            } else if let error = outcome.error {
                onError(error)
            }
            if outcome.terminated || isCancelled() {
                return
            }
        }

        let eofOutcome = parser.finishAtEOF()
        if isCancelled() { return }
        if let event = eofOutcome.event {
            onEvent(event)
        }
        if isCancelled() { return }
        if let error = eofOutcome.error {
            onError(error)
        }
    }


    // MARK: - SSE Line State Machine

    private struct SseStepOutcome {
        let event: TutorSseEvent?
        let error: Error?
        let terminated: Bool

        static let none = SseStepOutcome(event: nil, error: nil, terminated: false)
    }

    private struct SseLineParser {
        private var currentEvent: String? = nil
        private var dataBuffer: String = ""
        private var terminated: Bool = false

        mutating func consumeLine(_ line: String) -> SseStepOutcome {
            if terminated {
                return SseStepOutcome(event: nil, error: nil, terminated: true)
            }
            if line.isEmpty {
                if currentEvent != nil || !dataBuffer.isEmpty {
                    let ev = currentEvent
                    let payload = dataBuffer
                    currentEvent = nil
                    dataBuffer = ""
                    let outcome = dispatch(eventName: ev, data: payload)
                    if outcome.terminated {
                        terminated = true
                    }
                    return outcome
                }
                return .none
            }
            if line.hasPrefix(":") {
                return .none
            }
            if line.hasPrefix("event:") {
                currentEvent = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("data:") {
                let afterColon = line.dropFirst(5)
                let value: Substring
                if afterColon.first == " " {
                    value = afterColon.dropFirst()
                } else {
                    value = afterColon
                }
                if !dataBuffer.isEmpty {
                    dataBuffer.append("\n")
                }
                dataBuffer.append(contentsOf: value)
            }
            return .none
        }

        mutating func finishAtEOF() -> SseStepOutcome {
            if terminated {
                return SseStepOutcome(event: nil, error: nil, terminated: true)
            }
            if currentEvent != nil || !dataBuffer.isEmpty {
                let ev = currentEvent
                let payload = dataBuffer
                currentEvent = nil
                dataBuffer = ""
                let outcome = dispatch(eventName: ev, data: payload)
                if outcome.terminated {
                    terminated = true
                    return outcome
                }
                // Trailing non-terminal event without done/error: emit event then report premature EOF.
                terminated = true
                return SseStepOutcome(event: outcome.event, error: ApiError.connectionFailure, terminated: true)
            }
            terminated = true
            return SseStepOutcome(event: nil, error: ApiError.connectionFailure, terminated: true)
        }

        private func dispatch(eventName: String?, data: String) -> SseStepOutcome {
            let ev = (eventName ?? "").trimmingCharacters(in: .whitespaces)
            switch ev {
            case "meta":
                guard let obj = Self.parseJsonObject(data) else {
                    return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                }
                let mode = ApiClient.optClean(obj, "mode")
                let contextType = ApiClient.optClean(obj, "contextType")
                return SseStepOutcome(
                    event: .meta(mode: mode, contextType: contextType),
                    error: nil,
                    terminated: false
                )

            case "delta":
                guard let obj = Self.parseJsonObject(data) else {
                    return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                }
                let text = (obj["text"] as? String) ?? ""
                if text.isEmpty {
                    return .none
                }
                return SseStepOutcome(event: .delta(text: text), error: nil, terminated: false)

            case "done":
                var followUps: [String] = []
                if !data.isEmpty {
                    guard let obj = Self.parseJsonObject(data) else {
                        return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                    }
                    if let rawList = obj["followUps"] as? [Any] {
                        for entry in rawList {
                            if let item = entry as? String, !TextMetrics.trimSpace(item).isEmpty {
                                followUps.append(item)
                            }
                        }
                    }
                }
                return SseStepOutcome(event: .done(followUps: followUps), error: nil, terminated: true)

            case "error":
                let obj: [String: Any]
                if data.isEmpty {
                    obj = [:]
                } else if let parsed = Self.parseJsonObject(data) {
                    obj = parsed
                } else {
                    return SseStepOutcome(event: nil, error: ApiError.malformed, terminated: true)
                }
                let rawCode = ApiClient.optClean(obj, "code")
                let code: String? = rawCode.isEmpty ? nil : rawCode
                let rawMessage = (obj["message"] as? String) ?? ""
                let message = TextMetrics.trimSpace(rawMessage).isEmpty
                    ? "AI 助教暂时不可用，请稍后再试。"
                    : rawMessage
                return SseStepOutcome(
                    event: .error(code: code, message: message),
                    error: nil,
                    terminated: true
                )

            default:
                return .none
            }
        }

        private static func parseJsonObject(_ raw: String) -> [String: Any]? {
            guard let bytes = raw.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
                return nil
            }
            return obj
        }
    }

}
