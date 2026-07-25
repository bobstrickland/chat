package dev.rstrickland.chat.realtime;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

import dev.rstrickland.chat.net.ApiConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The single app WebSocket to the API Gateway stand-in (ws-shim) — the Android
 * counterpart to the web's RealtimeService. Presence + messaging both ride this
 * one socket; features subscribe by frame {@code type} (e.g. "message").
 *
 * The JWT rides in the query string because browsers/apps can't set WS headers
 * on the handshake — the same reason the web appends {@code ?token=}.
 */
public final class RealtimeClient {
    private static final String TAG = "RealtimeClient";
    private static RealtimeClient instance;

    private final OkHttpClient http = new OkHttpClient();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<FrameListener> listeners = new CopyOnWriteArrayList<>();
    private WebSocket socket;

    public interface FrameListener {
        /** Called on the main thread with a parsed frame; check frame.optString("type"). */
        void onFrame(JSONObject frame);
    }

    public static synchronized RealtimeClient get() {
        if (instance == null) instance = new RealtimeClient();
        return instance;
    }

    public void addListener(FrameListener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(FrameListener l) {
        listeners.remove(l);
    }

    /** (Re)connect with the current access token. Safe to call on login. */
    public synchronized void start(String accessToken) {
        stop();
        if (accessToken == null) return;
        Request request = new Request.Builder()
                .url(ApiConfig.WS + "?token=" + accessToken + "&device=android")
                .build();
        socket = http.newWebSocket(request, new Listener());
    }

    public synchronized void stop() {
        if (socket != null) {
            socket.close(1000, "bye");
            socket = null;
        }
    }

    private void dispatch(String text) {
        try {
            JSONObject frame = new JSONObject(text);
            main.post(() -> {
                for (FrameListener l : listeners) l.onFrame(frame);
            });
        } catch (Exception e) {
            Log.w(TAG, "bad frame: " + text);
        }
    }

    private final class Listener extends WebSocketListener {
        @Override
        public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
            dispatch(text);
        }

        @Override
        public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
            Log.w(TAG, "socket failure: " + t.getMessage());
        }
    }
}
