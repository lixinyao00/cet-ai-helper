package com.cethelper.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TutorState {
    private static final long DELTA_THROTTLE_MS = 50L;
    private static final int MAX_HISTORY_MESSAGES = 20;

    TutorContext context = TutorContext.free();
    final List<TutorMessage> messages = new ArrayList<>();
    @Nullable StringBuilder streaming;
    List<String> followUps = new ArrayList<>();
    boolean generating;
    @Nullable String mode;
    @Nullable String error;

    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private Runnable listener;
    @Nullable private ApiClient.StreamHandle activeHandle;
    @Nullable private StudyApplication lastApp;
    @Nullable private SessionStore.Session lastSession;
    private int loadRevision;
    private int streamRevision;
    private long lastNotifyUptimeMs;
    private boolean notifyScheduled;
    private final Runnable throttledNotify = () -> {
        notifyScheduled = false;
        lastNotifyUptimeMs = SystemClock.uptimeMillis();
        notifyChanged();
    };

    void setListener(@Nullable Runnable onChanged) {
        this.listener = onChanged;
    }

    void attach(MainActivity host, TutorContext ctx) {
        TutorContext target = ctx == null ? TutorContext.free() : ctx;
        SessionStore.Session account = host.session();
        this.lastApp = host.app();
        this.lastSession = account;
        if (account == null) {
            return;
        }

        if (this.context.equals(target) && (generating || !messages.isEmpty())) {
            notifyChanged();
            return;
        }

        if (generating) {
            stop();
        }
        this.context = target;
        this.messages.clear();
        this.streaming = null;
        this.followUps = new ArrayList<>();
        this.mode = null;
        this.error = null;
        notifyChanged();

        int rev = ++loadRevision;
        String sessionKey = target.sessionKey();
        host.app().storage.execute(() -> {
            List<TutorMessage> rows;
            try {
                rows = host.app().tutorMessages().session(
                        account.serverUrl(), account.student().id(), sessionKey);
            } catch (RuntimeException ignored) {
                rows = Collections.emptyList();
            }
            List<TutorMessage> loaded = new ArrayList<>(rows);
            host.runOnUiThread(() -> {
                if (rev != loadRevision || host.session() != account) {
                    return;
                }
                messages.clear();
                messages.addAll(loaded);
                notifyChanged();
            });
        });
    }

    void send(MainActivity host, String text) {
        String trimmed = text == null ? "" : ReadingArticle.trimSpace(text);
        SessionStore.Session account = host.session();
        if (trimmed.isEmpty() || generating || account == null) {
            return;
        }
        this.lastApp = host.app();
        this.lastSession = account;

        TutorContext activeContext = this.context;
        long now = System.currentTimeMillis();
        TutorMessage userMessage = new TutorMessage(
                account.serverUrl(),
                account.student().id(),
                activeContext.sessionKey(),
                TutorMessage.ROLE_USER,
                trimmed,
                activeContext.type,
                activeContext.summary(),
                "",
                now
        );
        messages.add(userMessage);
        persistMessage(host.app(), userMessage);

        JSONArray payloadMessages = buildPayloadMessages();
        generating = true;
        streaming = new StringBuilder();
        followUps = new ArrayList<>();
        mode = null;
        error = null;
        notifyChanged();

        int rev = ++streamRevision;
        activeHandle = new ApiClient(account.serverUrl()).streamTutorChat(
                account.token(),
                payloadMessages,
                activeContext,
                host.app().tutorStreams,
                new ApiClient.TutorStreamListener() {
                    @Override
                    public void onMeta(String streamMode, String contextType) {
                        main.post(() -> {
                            if (rev != streamRevision || !generating) {
                                return;
                            }
                            mode = streamMode;
                            scheduleThrottledNotify();
                        });
                    }

                    @Override
                    public void onDelta(String deltaText) {
                        main.post(() -> {
                            if (rev != streamRevision || !generating) {
                                return;
                            }
                            if (streaming == null) {
                                streaming = new StringBuilder();
                            }
                            streaming.append(deltaText);
                            scheduleThrottledNotify();
                        });
                    }

                    @Override
                    public void onDone(List<String> doneFollowUps) {
                        main.post(() -> {
                            if (rev != streamRevision) {
                                return;
                            }
                            cancelPendingThrottle();
                            generating = false;
                            activeHandle = null;
                            if (streaming != null && streaming.length() > 0) {
                                TutorMessage assistantMessage = new TutorMessage(
                                        account.serverUrl(),
                                        account.student().id(),
                                        activeContext.sessionKey(),
                                        TutorMessage.ROLE_ASSISTANT,
                                        streaming.toString(),
                                        activeContext.type,
                                        activeContext.summary(),
                                        mode == null ? "" : mode,
                                        System.currentTimeMillis()
                                );
                                messages.add(assistantMessage);
                                persistMessage(host.app(), assistantMessage);
                            }
                            streaming = null;
                            followUps = doneFollowUps == null
                                    ? new ArrayList<>()
                                    : new ArrayList<>(doneFollowUps);
                            notifyChanged();
                        });
                    }

                    @Override
                    public void onError(Exception failure) {
                        main.post(() -> {
                            if (rev != streamRevision) {
                                return;
                            }
                            cancelPendingThrottle();
                            generating = false;
                            activeHandle = null;
                            if (host.handleUnauthorized(failure)) {
                                streaming = null;
                                notifyChanged();
                                return;
                            }
                            if (streaming != null && streaming.length() > 0) {
                                TutorMessage partial = new TutorMessage(
                                        account.serverUrl(),
                                        account.student().id(),
                                        activeContext.sessionKey(),
                                        TutorMessage.ROLE_ASSISTANT,
                                        streaming.toString(),
                                        activeContext.type,
                                        activeContext.summary(),
                                        mode == null ? "" : mode,
                                        System.currentTimeMillis()
                                );
                                messages.add(partial);
                                persistMessage(host.app(), partial);
                            }
                            streaming = null;
                            error = MainActivity.failureMessage(failure);
                            notifyChanged();
                        });
                    }
                }
        );
    }

    void stop() {
        streamRevision++;
        cancelPendingThrottle();
        if (activeHandle != null) {
            activeHandle.cancel();
            activeHandle = null;
        }
        boolean wasGenerating = generating;
        generating = false;
        if (streaming != null && streaming.length() > 0) {
            String partialText = streaming.toString();
            SessionStore.Session account = lastSession;
            StudyApplication app = lastApp;
            if (account != null) {
                TutorMessage partial = new TutorMessage(
                        account.serverUrl(),
                        account.student().id(),
                        context.sessionKey(),
                        TutorMessage.ROLE_ASSISTANT,
                        partialText,
                        context.type,
                        context.summary(),
                        mode == null ? "" : mode,
                        System.currentTimeMillis()
                );
                messages.add(partial);
                if (app != null) {
                    persistMessage(app, partial);
                }
            }
        }
        streaming = null;
        if (wasGenerating) {
            notifyChanged();
        }
    }

    void clear(MainActivity host) {
        streamRevision++;
        loadRevision++;
        cancelPendingThrottle();
        if (activeHandle != null) {
            activeHandle.cancel();
            activeHandle = null;
        }
        generating = false;
        streaming = null;
        messages.clear();
        followUps = new ArrayList<>();
        mode = null;
        error = null;
        notifyChanged();

        SessionStore.Session account = host.session();
        if (account == null) {
            return;
        }
        String sessionKey = context.sessionKey();
        host.app().storage.execute(() -> {
            try {
                host.app().tutorMessages().clearSession(
                        account.serverUrl(), account.student().id(), sessionKey);
            } catch (RuntimeException ignored) {
                // Best-effort clear.
            }
        });
    }

    void reset() {
        streamRevision++;
        loadRevision++;
        cancelPendingThrottle();
        if (activeHandle != null) {
            activeHandle.cancel();
            activeHandle = null;
        }
        generating = false;
        context = TutorContext.free();
        messages.clear();
        streaming = null;
        followUps = new ArrayList<>();
        mode = null;
        error = null;
        listener = null;
        lastSession = null;
    }

    private JSONArray buildPayloadMessages() {
        JSONArray array = new JSONArray();
        int start = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < messages.size(); i++) {
            TutorMessage m = messages.get(i);
            try {
                array.put(new JSONObject().put("role", m.role).put("content", m.content));
            } catch (JSONException ignored) {
                // Standard string keys/values cannot fail JSON encoding.
            }
        }
        return array;
    }

    private void persistMessage(StudyApplication app, TutorMessage message) {
        app.storage.execute(() -> {
            try {
                app.tutorMessages().insert(message);
            } catch (RuntimeException ignored) {
                // Non-fatal persistence error.
            }
        });
    }

    private void scheduleThrottledNotify() {
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastNotifyUptimeMs;
        if (elapsed >= DELTA_THROTTLE_MS) {
            cancelPendingThrottle();
            lastNotifyUptimeMs = now;
            notifyChanged();
        } else if (!notifyScheduled) {
            notifyScheduled = true;
            main.postDelayed(throttledNotify, DELTA_THROTTLE_MS - elapsed);
        }
    }

    private void cancelPendingThrottle() {
        if (notifyScheduled) {
            main.removeCallbacks(throttledNotify);
            notifyScheduled = false;
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.run();
        }
    }
}
