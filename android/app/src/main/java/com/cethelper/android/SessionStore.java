package com.cethelper.android;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

final class SessionStore {
    private static final String SERVER_URL = "server_url";
    private static final String TOKEN = "token";
    private static final String VERIFIED = "verified_session";
    private static final String DEFAULT_URL = "http://10.0.2.2:8080";

    record Session(String serverUrl, String token, ApiClient.Student student) {
        ArticleOwner owner() {
            return new ArticleOwner(serverUrl, student.id());
        }
    }

    private final SharedPreferences preferences;

    SessionStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    String serverUrl() {
        return preferences.getString(SERVER_URL, DEFAULT_URL);
    }

    String token() {
        return preferences.getString(TOKEN, null);
    }

    boolean matches(String server, String credential) {
        return server.equals(serverUrl()) && credential.equals(token());
    }

    Session verified(String server, String credential) {
        String cached = preferences.getString(VERIFIED, null);
        if (cached == null || ReadingArticle.trimSpace(credential).isEmpty() || !matches(server, credential)) {
            return null;
        }
        try {
            JSONObject entry = new JSONObject(cached);
            if (server.equals(entry.get("server")) && fingerprint(credential).equals(entry.get("credential"))) {
                return new Session(server, credential, ApiClient.readStudent(entry));
            }
        } catch (JSONException invalidEntry) {
            // A partial or corrupt cache cannot authorize local access.
        }
        return null;
    }

    void save(Session session) {
        try {
            JSONObject entry = new JSONObject()
                    .put("server", session.serverUrl())
                    .put("credential", fingerprint(session.token()))
                    .put("id", session.student().id())
                    .put("username", session.student().username());
            preferences.edit().putString(TOKEN, session.token()).putString(VERIFIED, entry.toString()).apply();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void clear() {
        preferences.edit().remove(TOKEN).remove(VERIFIED).commit();
    }

    void changeServer(String server) {
        preferences.edit().putString(SERVER_URL, server).remove(TOKEN).remove(VERIFIED).commit();
    }

    private static String fingerprint(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
