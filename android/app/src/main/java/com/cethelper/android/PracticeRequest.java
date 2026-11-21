package com.cethelper.android;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Keeps request state on the main thread across Activity recreation. */
final class PracticeRequest<T> {
    interface Fetch<T> {
        T run() throws IOException, JSONException, ApiClient.Failure;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor;
    private final Consumer<PracticeRequest<T>> onComplete;
    private int revision;
    private Future<?> future;
    T value;
    Exception error;

    PracticeRequest(ExecutorService executor, Consumer<PracticeRequest<T>> onComplete) {
        this.executor = executor;
        this.onComplete = onComplete;
    }

    boolean pending() {
        return future != null;
    }

    void start(Fetch<T> fetch) {
        reset();
        int current = revision;
        future = executor.submit(() -> {
            try {
                T result = fetch.run();
                main.post(() -> complete(current, result, null));
            } catch (IOException | JSONException | ApiClient.Failure failure) {
                main.post(() -> complete(current, null, failure));
            }
        });
    }

    private void complete(int current, T result, Exception failure) {
        if (current != revision) {
            return;
        }
        future = null;
        value = result;
        error = failure;
        onComplete.accept(this);
    }

    void reset() {
        revision++;
        if (future != null) {
            future.cancel(true);
            future = null;
        }
        value = null;
        error = null;
    }
}
