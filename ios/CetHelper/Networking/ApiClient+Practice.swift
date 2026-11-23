import Foundation

extension ApiClient {
    func explain(_ session: Session, selection: String, sentence: String) async throws -> ExplanationResult {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/explanations",
            token: session.token,
            payload: ["selection": selection, "sentence": sentence]
        )
        guard let meaning = body["meaning"] as? String,
              let explanation = body["explanation"] as? String else {
            throw ApiError.malformed
        }
        var mode = Self.optClean(body, "mode")
        if mode.isEmpty {
            let demo = (body["demo"] as? Bool) ?? false
            mode = demo ? "demo" : "live"
        }
        let contextual: Bool
        if let rawContextual = body["contextual"] as? Bool {
            contextual = rawContextual
        } else {
            contextual = true
        }
        return ExplanationResult(
            selection: selection,
            sentence: sentence,
            meaning: meaning,
            explanation: explanation,
            mode: mode,
            phonetic: Self.optClean(body, "phonetic"),
            partOfSpeech: Self.optClean(body, "partOfSpeech"),
            lemma: Self.optClean(body, "lemma"),
            cetLevel: Self.optClean(body, "cetLevel"),
            contextual: contextual
        )
    }

    func analyzeSentence(_ session: Session, sentence: String) async throws -> SentenceAnalysis {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/sentence-analyses",
            token: session.token,
            payload: ["sentence": sentence]
        )
        let echoedSentence = try Self.requiredText(body, "sentence")
        let mode = try Self.requireMode(body)
        guard echoedSentence == sentence,
              let rawChunks = body["chunks"] as? [[String: Any]],
              !rawChunks.isEmpty,
              rawChunks.count <= 20 else {
            throw ApiError.malformed
        }

        let translation = try Self.requiredText(body, "translation")
        let structure = try Self.requiredText(body, "structure")

        var parsedChunks: [(text: String, role: String, explanation: String)] = []
        parsedChunks.reserveCapacity(rawChunks.count)
        for chunk in rawChunks {
            parsedChunks.append((
                text: try Self.requiredText(chunk, "text"),
                role: try Self.requiredText(chunk, "role"),
                explanation: try Self.requiredText(chunk, "explanation")
            ))
        }

        let located = try SentenceAnalysis.locate(sentence: echoedSentence, chunks: parsedChunks)
        return SentenceAnalysis(
            sentence: echoedSentence,
            translation: translation,
            structure: structure,
            mode: mode,
            chunks: located
        )
    }

    func translationHint(
        _ session: Session,
        sourceText: String,
        type: TranslationHintType
    ) async throws -> TranslationHint {
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/translation-hints",
            token: session.token,
            payload: ["sourceText": sourceText, "hintType": type.rawValue]
        )
        let echoedSource = try Self.requiredText(body, "sourceText")
        let echoedType = try Self.requiredText(body, "hintType")
        let mode = try Self.requireMode(body)
        guard echoedSource == sourceText, echoedType == type.rawValue else {
            throw ApiError.malformed
        }

        var items: [TranslationHintItem] = []
        switch type {
        case .structure:
            guard let structure = body[type.responseField] as? [String: Any] else {
                throw ApiError.malformed
            }
            let mainClause = try Self.requiredText(structure, "mainClause")
            items.append(TranslationHintItem(id: 0, title: "主干", body: mainClause))

            guard let clauses = structure["clauses"] as? [[String: Any]],
                  let connectives = structure["connectives"] as? [[String: Any]] else {
                throw ApiError.malformed
            }

            for clause in clauses {
                let role = try Self.requiredText(clause, "role")
                let source = try Self.requiredText(clause, "source")
                let guidance = try Self.requiredText(clause, "guidance")
                items.append(
                    TranslationHintItem(id: items.count, title: "\(role) · \(source)", body: guidance)
                )
            }
            for connective in connectives {
                let source = try Self.requiredText(connective, "source")
                let suggestion = try Self.requiredText(connective, "suggestion")
                items.append(
                    TranslationHintItem(id: items.count, title: source, body: suggestion)
                )
            }
            guard items.count > 1 else {
                throw ApiError.malformed
            }

        case .keyword, .phrase:
            guard let entries = body[type.responseField] as? [[String: Any]], !entries.isEmpty else {
                throw ApiError.malformed
            }
            for entry in entries {
                let source = try Self.requiredText(entry, "source")
                let note = try Self.requiredText(entry, "note")
                guard let rawExpressions = entry["expressions"] as? [Any],
                      !rawExpressions.isEmpty,
                      rawExpressions.count <= 5 else {
                    throw ApiError.malformed
                }
                var expressions: [String] = []
                expressions.reserveCapacity(rawExpressions.count)
                for rawExpr in rawExpressions {
                    guard let expr = rawExpr as? String, !TextMetrics.trimSpace(expr).isEmpty else {
                        throw ApiError.malformed
                    }
                    expressions.append(expr)
                }
                let joined = expressions.joined(separator: " / ")
                items.append(
                    TranslationHintItem(id: items.count, title: source, body: "\(joined)\n\(note)")
                )
            }
        }

        return TranslationHint(type: type, sourceText: echoedSource, mode: mode, items: items)
    }

    func reviewTranslation(
        _ session: Session,
        sourceText: String,
        studentTranslation: String
    ) async throws -> TranslationFeedback {
        let (body, rawJson) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/translation-reviews",
            token: session.token,
            payload: ["sourceText": sourceText, "studentTranslation": studentTranslation]
        )
        let feedback = try Self.parseTranslationReview(body: body, rawJson: rawJson)
        guard feedback.sourceText == sourceText, feedback.studentTranslation == studentTranslation else {
            throw ApiError.malformed
        }
        return feedback
    }

    func reviewWriting(_ session: Session, prompt: String, essay: String) async throws -> WritingFeedback {
        let (body, rawJson) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-reviews",
            token: session.token,
            payload: ["prompt": prompt, "essay": essay]
        )
        let feedback = try Self.parseWritingReview(body: body, rawJson: rawJson)
        guard feedback.prompt == prompt, feedback.essay == essay else {
            throw ApiError.malformed
        }
        return feedback
    }

    func attemptWritingExercise(
        _ session: Session,
        issue: ReviewIssue,
        exercise: WritingExercise,
        answer: String
    ) async throws -> WritingAssessment {
        let payload: [String: Any] = [
            "context": Self.writingContextPayload(issue: issue, exercise: exercise),
            "answer": answer
        ]
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-exercise-attempts",
            token: session.token,
            payload: payload
        )
        return try Self.parseWritingAssessment(body: body, expectedIssue: issue, sentAnswer: answer, retest: false)
    }

    func requestWritingRetest(
        _ session: Session,
        issue: ReviewIssue,
        exercise: WritingExercise
    ) async throws -> WritingTask {
        let payload = Self.writingContextPayload(issue: issue, exercise: exercise)
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-retests",
            token: session.token,
            payload: payload
        )
        try Self.requireOriginalIssue(body, matches: issue)
        _ = try Self.requireMode(body)
        guard let task = body["task"] as? [String: Any] else {
            throw ApiError.malformed
        }
        return WritingTask(
            instruction: try Self.requiredText(task, "instruction"),
            item: try Self.requiredText(task, "item")
        )
    }

    func attemptWritingRetest(
        _ session: Session,
        issue: ReviewIssue,
        exercise: WritingExercise,
        task: WritingTask,
        answer: String
    ) async throws -> WritingAssessment {
        let payload: [String: Any] = [
            "context": Self.writingContextPayload(issue: issue, exercise: exercise),
            "task": ["instruction": task.instruction, "item": task.item],
            "answer": answer
        ]
        let (body, _) = try await request(
            baseUrl: session.serverUrl,
            method: "POST",
            path: "/writing-retest-attempts",
            token: session.token,
            payload: payload
        )
        return try Self.parseWritingAssessment(body: body, expectedIssue: issue, sentAnswer: answer, retest: true)
    }

    // MARK: - Static Review Parsers (also used for PracticeRecord history)

    static func parseTranslationReview(json: String) throws -> TranslationFeedback {
        guard let data = json.data(using: .utf8),
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ApiError.malformed
        }
        return try parseTranslationReview(body: body, rawJson: json)
    }

    static func parseWritingReview(json: String) throws -> WritingFeedback {
        guard let data = json.data(using: .utf8),
              let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ApiError.malformed
        }
        return try parseWritingReview(body: body, rawJson: json)
    }

    private static func parseTranslationReview(body: [String: Any], rawJson: String) throws -> TranslationFeedback {
        let source = try requiredText(body, "sourceText")
        let draft = try requiredText(body, "studentTranslation")
        let mode = try requireMode(body)
        let overallFeedback = try requiredText(body, "overallFeedback")
        guard let rawIssues = body["issues"] as? [[String: Any]] else {
            throw ApiError.malformed
        }

        var issues: [ReviewIssue] = []
        issues.reserveCapacity(rawIssues.count)
        for (index, item) in rawIssues.enumerated() {
            let rawType = try requiredText(item, "type")
            guard let type = ReviewIssueType(rawValue: rawType),
                  type == .omission || type == .unnatural else {
                throw ApiError.malformed
            }
            let quote = try requiredText(item, "quote")
            let detail = try requiredText(item, "detail")
            let suggestion = try requiredText(item, "suggestion")
            let target: QuoteTarget = type == .omission ? .source : .draft
            let snapshot = target == .source ? source : draft
            let matches = ReviewQuote.findMatches(in: snapshot, quote: quote)
            guard !matches.isEmpty else {
                throw ApiError.malformed
            }
            issues.append(
                ReviewIssue(
                    index: index,
                    type: type,
                    quote: quote,
                    detail: detail,
                    suggestion: suggestion,
                    target: target,
                    matches: matches
                )
            )
        }

        let estimatedScore = parseOptionalScore(body, "estimatedScore")
        let scoreMax = parseOptionalInt(body, "scoreMax") ?? 15
        let scoreBandRaw = optClean(body, "scoreBand")
        let refRaw = optClean(body, "referenceTranslation")

        return TranslationFeedback(
            sourceText: source,
            studentTranslation: draft,
            mode: mode,
            overallFeedback: overallFeedback,
            issues: issues,
            estimatedScore: estimatedScore,
            scoreMax: scoreMax,
            scoreBand: scoreBandRaw.isEmpty ? nil : scoreBandRaw,
            referenceTranslation: refRaw.isEmpty ? nil : refRaw,
            rawJson: rawJson
        )
    }

    private static func parseWritingReview(body: [String: Any], rawJson: String) throws -> WritingFeedback {
        let prompt = try requiredText(body, "prompt")
        let essay = try requiredText(body, "essay")
        let mode = try requireMode(body)
        let overallFeedback = try requiredText(body, "overallFeedback")
        guard let rawIssues = body["issues"] as? [[String: Any]], rawIssues.count <= 3 else {
            throw ApiError.malformed
        }

        var issues: [ReviewIssue] = []
        issues.reserveCapacity(rawIssues.count)
        for (index, item) in rawIssues.enumerated() {
            let rawType = try requiredText(item, "type")
            guard let type = ReviewIssueType(rawValue: rawType),
                  type == .grammar || type == .wordChoice || type == .mechanics || type == .clarity else {
                throw ApiError.malformed
            }
            let quote = try requiredText(item, "quote")
            let detail = try requiredText(item, "detail")
            let suggestion = try requiredText(item, "suggestion")
            let matches = ReviewQuote.findMatches(in: essay, quote: quote)
            guard quote.utf16.count <= 240, !matches.isEmpty else {
                throw ApiError.malformed
            }
            issues.append(
                ReviewIssue(
                    index: index,
                    type: type,
                    quote: quote,
                    detail: detail,
                    suggestion: suggestion,
                    target: .draft,
                    matches: matches
                )
            )
        }

        var exercise: WritingExercise? = nil
        if let rawExercise = body["exercise"], !(rawExercise is NSNull) {
            guard let exDict = rawExercise as? [String: Any],
                  let issueIndex = parseExactInt(exDict["issueIndex"]),
                  issueIndex >= 0, issueIndex < issues.count else {
                throw ApiError.malformed
            }
            exercise = WritingExercise(
                issueIndex: issueIndex,
                instruction: try requiredText(exDict, "instruction"),
                item: try requiredText(exDict, "item")
            )
        }

        let estimatedScore = parseOptionalScore(body, "estimatedScore")
        let scoreMax = parseOptionalInt(body, "scoreMax") ?? 15
        let scoreBandRaw = optClean(body, "scoreBand")

        return WritingFeedback(
            prompt: prompt,
            essay: essay,
            mode: mode,
            overallFeedback: overallFeedback,
            issues: issues,
            exercise: exercise,
            estimatedScore: estimatedScore,
            scoreMax: scoreMax,
            scoreBand: scoreBandRaw.isEmpty ? nil : scoreBandRaw,
            rawJson: rawJson
        )
    }

    private static func parseWritingAssessment(
        body: [String: Any],
        expectedIssue: ReviewIssue,
        sentAnswer: String,
        retest: Bool
    ) throws -> WritingAssessment {
        try requireOriginalIssue(body, matches: expectedIssue)
        _ = try requireMode(body)
        let answer = try requiredText(body, "answer")
        guard answer == sentAnswer else {
            throw ApiError.malformed
        }
        let rawVerdict = try requiredText(body, "verdict")
        guard let verdict = WritingVerdict(rawValue: rawVerdict),
              verdict.isRetest == retest else {
            throw ApiError.malformed
        }
        let feedback = try requiredText(body, "feedback")
        let nextStep = try requiredText(body, "nextStep")

        var quote: String? = nil
        var matches: [TextRange] = []
        if verdict == .targetUsed || verdict == .sameError {
            let q = try requiredText(body, "quote")
            let found = ReviewQuote.findMatches(in: answer, quote: q)
            guard q.utf16.count <= 240, !found.isEmpty else {
                throw ApiError.malformed
            }
            quote = q
            matches = found
        }

        return WritingAssessment(
            verdict: verdict,
            answer: answer,
            feedback: feedback,
            nextStep: nextStep,
            quote: quote,
            matches: matches
        )
    }

}
