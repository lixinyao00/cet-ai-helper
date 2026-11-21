package com.cethelper.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

final class ApiClient {
    record Student(long id, String username) {}
    record Explanation(String meaning, String detail, String mode) {}

    static final class Failure extends Exception {
        final int status;
        final String code;

        Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    static final class ConnectionFailure extends IOException {
        ConnectionFailure(IOException cause) {
            super(cause);
        }
    }

    private final String baseUrl;

    ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    static String normalizeBaseUrl(String raw) {
        URI uri = URI.create(raw.trim()).normalize();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || host == null || port == 0 || port > 65535 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid base URL");
        }

        scheme = scheme.toLowerCase(Locale.ROOT);
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port)
                + uri.getRawPath().replaceAll("/+$", "");
    }

    void register(String username, String password) throws IOException, JSONException, Failure {
        request("POST", "/auth/register", null, credentials(username, password));
    }

    String login(String username, String password) throws IOException, JSONException, Failure {
        return requiredText(request("POST", "/auth/login", null, credentials(username, password)), "accessToken");
    }

    Student me(String token) throws IOException, JSONException, Failure {
        return readStudent(request("GET", "/me", token, null));
    }

    static Student readStudent(JSONObject body) throws JSONException {
        Object id = body.get("id");
        if ((id instanceof Long || id instanceof Integer) && ((Number) id).longValue() > 0) {
            return new Student(((Number) id).longValue(), requiredText(body, "username"));
        }
        throw new JSONException("Invalid student id");
    }

    void logout(String token) throws IOException, JSONException, Failure {
        request("POST", "/auth/logout", token, null);
    }

    Explanation explain(String token, String selection, String sentence)
            throws IOException, JSONException, Failure {
        JSONObject payload = new JSONObject().put("selection", selection).put("sentence", sentence);
        JSONObject body = request("POST", "/explanations", token, payload);
        return new Explanation(body.getString("meaning"), body.getString("explanation"),
                body.getString("mode"));
    }

    SentenceAnalysis analyze(String token, String sentence) throws IOException, JSONException, Failure {
        JSONObject body = request("POST", "/sentence-analyses", token, new JSONObject().put("sentence", sentence));
        String original = requiredText(body, "sentence");
        String mode = requiredText(body, "mode");
        JSONArray chunks = body.getJSONArray("chunks");
        if (!original.equals(sentence) || (!"demo".equals(mode) && !"live".equals(mode))
                || chunks.length() == 0 || chunks.length() > 20) {
            throw new JSONException("Invalid sentence analysis");
        }
        List<SentenceAnalysis.Chunk> phrases = new ArrayList<>(chunks.length());
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject chunk = chunks.getJSONObject(i);
            phrases.add(new SentenceAnalysis.Chunk(requiredText(chunk, "text"),
                    requiredText(chunk, "role"), requiredText(chunk, "explanation")));
        }
        try {
            return new SentenceAnalysis(original, requiredText(body, "translation"),
                    requiredText(body, "structure"), mode, SentenceAnalysis.locate(original, phrases));
        } catch (IllegalArgumentException invalidChunk) {
            throw new JSONException("Invalid sentence analysis fragment");
        }
    }

    TranslationFeedback.Hint translationHint(String token, String source,
                                               TranslationFeedback.HintType type)
            throws IOException, JSONException, Failure {
        JSONObject payload = new JSONObject().put("sourceText", source).put("hintType", type.apiValue);
        JSONObject body = request("POST", "/translation-hints", token, payload);
        if (!source.equals(requiredText(body, "sourceText"))
                || !type.apiValue.equals(requiredText(body, "hintType"))) {
            throw new JSONException("Unexpected translation hint");
        }
        List<TranslationFeedback.HintItem> items = new ArrayList<>();
        if (type == TranslationFeedback.HintType.STRUCTURE) {
            JSONObject structure = body.getJSONObject(type.field);
            items.add(new TranslationFeedback.HintItem(null, requiredText(structure, "mainClause")));
            JSONArray clauses = structure.getJSONArray("clauses");
            for (int i = 0; i < clauses.length(); i++) {
                JSONObject clause = clauses.getJSONObject(i);
                items.add(new TranslationFeedback.HintItem(
                        requiredText(clause, "role") + " · " + requiredText(clause, "source"),
                        requiredText(clause, "guidance")));
            }
            JSONArray connectives = structure.getJSONArray("connectives");
            for (int i = 0; i < connectives.length(); i++) {
                JSONObject connective = connectives.getJSONObject(i);
                items.add(new TranslationFeedback.HintItem(requiredText(connective, "source"),
                        requiredText(connective, "suggestion")));
            }
            if (items.size() == 1) {
                throw new JSONException("Empty translation structure hint");
            }
        } else {
            JSONArray entries = body.getJSONArray(type.field);
            if (entries.length() == 0) {
                throw new JSONException("Empty translation hint");
            }
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                JSONArray expressions = entry.getJSONArray("expressions");
                if (expressions.length() == 0) {
                    throw new JSONException("Empty translation hint expressions");
                }
                StringJoiner choices = new StringJoiner(" / ");
                for (int j = 0; j < expressions.length(); j++) {
                    if (!(expressions.get(j) instanceof String expression)
                            || ReadingArticle.trimSpace(expression).isEmpty()) {
                        throw new JSONException("Invalid translation hint expression");
                    }
                    choices.add(expression);
                }
                items.add(new TranslationFeedback.HintItem(requiredText(entry, "source"),
                        choices + "\n" + requiredText(entry, "note")));
            }
        }
        return new TranslationFeedback.Hint(isDemo(body), Collections.unmodifiableList(items));
    }

    TranslationFeedback.Review translationReview(String token, String source, String draft)
            throws IOException, JSONException, Failure {
        JSONObject payload = new JSONObject().put("sourceText", source).put("studentTranslation", draft);
        JSONObject body = request("POST", "/translation-reviews", token, payload);
        if (!source.equals(requiredText(body, "sourceText"))
                || !draft.equals(requiredText(body, "studentTranslation"))) {
            throw new JSONException("Unexpected translation review");
        }
        JSONArray issues = body.getJSONArray("issues");
        List<TranslationFeedback.Issue> results = new ArrayList<>(issues.length());
        for (int i = 0; i < issues.length(); i++) {
            JSONObject issue = issues.getJSONObject(i);
            TranslationFeedback.IssueType type;
            try {
                type = TranslationFeedback.IssueType.valueOf(requiredText(issue, "type"));
            } catch (IllegalArgumentException invalidType) {
                throw new JSONException("Invalid translation issue type");
            }
            String quote = requiredText(issue, "quote");
            String snapshot = type == TranslationFeedback.IssueType.OMISSION ? source : draft;
            List<ReviewQuote.Range> matches = ReviewQuote.findMatches(snapshot, quote);
            if (matches.isEmpty()) {
                throw new JSONException("Translation quote is missing from the submission");
            }
            results.add(new TranslationFeedback.Issue(type, quote, requiredText(issue, "detail"),
                    requiredText(issue, "suggestion"), matches));
        }
        return new TranslationFeedback.Review(source, draft, isDemo(body),
                requiredText(body, "overallFeedback"), Collections.unmodifiableList(results));
    }

    WritingFeedback writingReview(String token, String prompt, String essay)
            throws IOException, JSONException, Failure {
        JSONObject payload = new JSONObject().put("prompt", prompt).put("essay", essay);
        JSONObject body = request("POST", "/writing-reviews", token, payload);
        String submittedEssay = requiredText(body, "essay");
        if (!prompt.equals(requiredText(body, "prompt")) || !essay.equals(submittedEssay)) {
            throw new JSONException("Unexpected writing review submission");
        }
        JSONArray issues = body.getJSONArray("issues");
        if (issues.length() > 3) {
            throw new JSONException("Too many writing issues");
        }
        List<WritingFeedback.Issue> results = new ArrayList<>(issues.length());
        for (int i = 0; i < issues.length(); i++) {
            JSONObject issue = issues.getJSONObject(i);
            WritingFeedback.IssueType type;
            try {
                type = WritingFeedback.IssueType.valueOf(requiredText(issue, "type"));
            } catch (IllegalArgumentException invalidType) {
                throw new JSONException("Invalid writing issue type");
            }
            String quote = requiredText(issue, "quote");
            List<ReviewQuote.Range> matches = ReviewQuote.findMatches(submittedEssay, quote);
            if (matches.isEmpty() || quote.length() > 240) {
                throw new JSONException("Invalid writing quote");
            }
            results.add(new WritingFeedback.Issue(type, quote, requiredText(issue, "detail"),
                    requiredText(issue, "suggestion"), matches));
        }
        WritingFeedback.Exercise exercise = null;
        if (body.has("exercise")) {
            JSONObject item = body.getJSONObject("exercise");
            if (!(item.get("issueIndex") instanceof Integer index) || index < 0 || index >= results.size()) {
                throw new JSONException("Invalid writing exercise issue index");
            }
            exercise = new WritingFeedback.Exercise(index, requiredText(item, "instruction"),
                    requiredText(item, "item"));
        }
        return new WritingFeedback(prompt, submittedEssay, isDemo(body), requiredText(body, "overallFeedback"),
                Collections.unmodifiableList(results), exercise);
    }

    WritingFollowUp.Assessment gradeWritingExercise(String token, WritingFollowUp.Context context, String answer)
            throws IOException, JSONException, Failure {
        JSONObject payload = new JSONObject().put("context", writingContext(context)).put("answer", answer);
        JSONObject body = request("POST", "/writing-exercise-attempts", token, payload);
        return writingAssessment(body, context, answer, false);
    }

    WritingFollowUp.Task createWritingRetest(String token, WritingFollowUp.Context context)
            throws IOException, JSONException, Failure {
        JSONObject body = request("POST", "/writing-retests", token, writingContext(context));
        requireOriginalIssue(body, context.issue());
        isDemo(body);
        JSONObject task = body.getJSONObject("task");
        return new WritingFollowUp.Task(requiredText(task, "instruction"), requiredText(task, "item"));
    }

    WritingFollowUp.Assessment gradeWritingRetest(String token, WritingFollowUp.Context context,
                                                   WritingFollowUp.Task task, String answer)
            throws IOException, JSONException, Failure {
        JSONObject payload = new JSONObject().put("context", writingContext(context))
                .put("task", writingTask(task)).put("answer", answer);
        JSONObject body = request("POST", "/writing-retest-attempts", token, payload);
        return writingAssessment(body, context, answer, true);
    }

    private static WritingFollowUp.Assessment writingAssessment(JSONObject body, WritingFollowUp.Context context,
                                                                 String sentAnswer, boolean retest)
            throws JSONException {
        requireOriginalIssue(body, context.issue());
        isDemo(body);
        String answer = requiredText(body, "answer");
        if (!sentAnswer.equals(answer)) {
            throw new JSONException("Unexpected writing answer");
        }
        WritingFollowUp.Verdict verdict;
        try {
            verdict = WritingFollowUp.Verdict.valueOf(requiredText(body, "verdict"));
        } catch (IllegalArgumentException invalidVerdict) {
            throw new JSONException("Invalid writing verdict");
        }
        if (verdict.retest != retest) {
            throw new JSONException("Verdict does not belong to this task");
        }
        List<ReviewQuote.Range> matches = Collections.emptyList();
        if (verdict == WritingFollowUp.Verdict.TARGET_USED || verdict == WritingFollowUp.Verdict.SAME_ERROR) {
            String quote = requiredText(body, "quote");
            matches = ReviewQuote.findMatches(answer, quote);
            if (quote.length() > 240 || matches.isEmpty()) {
                throw new JSONException("Writing evidence is not part of the submitted answer");
            }
        }
        return new WritingFollowUp.Assessment(answer, verdict, requiredText(body, "feedback"),
                requiredText(body, "nextStep"), matches);
    }

    private static JSONObject writingContext(WritingFollowUp.Context context) throws JSONException {
        WritingFeedback.Issue issue = context.issue();
        JSONObject original = new JSONObject().put("type", issue.type().name()).put("quote", issue.quote())
                .put("detail", issue.detail()).put("suggestion", issue.suggestion());
        return new JSONObject().put("issue", original).put("exercise", writingTask(context.exercise()));
    }

    private static JSONObject writingTask(WritingFollowUp.Task task) throws JSONException {
        return new JSONObject().put("instruction", task.instruction()).put("item", task.item());
    }

    private static void requireOriginalIssue(JSONObject body, WritingFeedback.Issue issue) throws JSONException {
        JSONObject original = body.getJSONObject("issue");
        if (!issue.type().name().equals(requiredText(original, "type"))
                || !issue.quote().equals(requiredText(original, "quote"))
                || !issue.detail().equals(requiredText(original, "detail"))
                || !issue.suggestion().equals(requiredText(original, "suggestion"))) {
            throw new JSONException("Unexpected original writing issue");
        }
    }

    private static boolean isDemo(JSONObject body) throws JSONException {
        return switch (requiredText(body, "mode")) {
            case "demo" -> true;
            case "live" -> false;
            default -> throw new JSONException("Invalid review mode");
        };
    }

    private static String requiredText(JSONObject object, String key) throws JSONException {
        if (object.get(key) instanceof String text && !ReadingArticle.trimSpace(text).isEmpty()) {
            return text;
        }
        throw new JSONException("Missing text: " + key);
    }

    private static JSONObject credentials(String username, String password) throws JSONException {
        return new JSONObject().put("username", username).put("password", password);
    }

    private JSONObject request(String method, String path, String token, JSONObject payload)
            throws IOException, JSONException, Failure {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(baseUrl + "/api/v1" + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (payload != null) {
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }
            int status;
            try {
                status = connection.getResponseCode();
            } catch (SocketException | SocketTimeoutException | UnknownHostException failure) {
                if ("/me".equals(path)) {
                    throw new ConnectionFailure(failure);
                }
                throw failure;
            }
            if (status == -1) {
                throw new IOException("Missing HTTP response status");
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT) {
                return new JSONObject();
            }
            if (status < 200 || status >= 300) {
                JSONObject error = new JSONObject();
                try {
                    error = readJson(connection.getErrorStream());
                } catch (IOException | JSONException ignored) {
                    // Preserve the HTTP status when the error body is missing or malformed.
                }
                throw new Failure(status, error.optString("code"),
                        error.optString("message", "服务请求失败（HTTP " + status + "）。"));
            }
            return readJson(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream stream) throws IOException, JSONException {
        if (stream == null) {
            return new JSONObject();
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
}
