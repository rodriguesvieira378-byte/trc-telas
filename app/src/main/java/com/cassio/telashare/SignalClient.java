package com.cassio.telashare;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Cliente do servidor público do PeerJS (0.peerjs.com), usado só como "correio" de mensagens.
 * Fala o mesmo protocolo do app.js do site: OFFER / ANSWER / CANDIDATE / LEAVE com payload JSON.
 */
public class SignalClient {

    public interface Listener {
        void onOpen();
        void onIdTaken();
        void onMessage(String type, String src, JSONObject payload);
        void onFailure(String reason);
    }

    private static final String URL = "wss://0.peerjs.com:443/peerjs";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final Listener listener;
    private WebSocket ws;
    private volatile boolean closed = false;

    public SignalClient(Listener listener) { this.listener = listener; }

    public void connect(String id) {
        try {
            String url = URL + "?key=peerjs&id=" + URLEncoder.encode(id, "UTF-8")
                    + "&token=" + UUID.randomUUID().toString().substring(0, 10) + "&version=1.5.4";
            ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onMessage(WebSocket s, String text) {
                    try {
                        JSONObject m = new JSONObject(text);
                        String type = m.optString("type");
                        switch (type) {
                            case "OPEN":
                                heartbeat.scheduleAtFixedRate(() -> {
                                    if (!closed && ws != null) ws.send("{\"type\":\"HEARTBEAT\"}");
                                }, 5, 5, TimeUnit.SECONDS);
                                listener.onOpen();
                                break;
                            case "ID-TAKEN":
                                listener.onIdTaken();
                                break;
                            case "ERROR":
                                JSONObject p = m.optJSONObject("payload");
                                listener.onFailure(p != null ? p.optString("msg") : "erro no servidor");
                                break;
                            default:
                                JSONObject payload = m.optJSONObject("payload");
                                listener.onMessage(type, m.optString("src"), payload != null ? payload : new JSONObject());
                        }
                    } catch (Exception ignored) { }
                }

                @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                    if (!closed) listener.onFailure("Sem conexão com o servidor. Confira a internet.");
                }

                @Override public void onClosed(WebSocket s, int code, String reason) {
                    if (!closed) listener.onFailure("A conexão com o servidor caiu.");
                }
            });
        } catch (Exception e) {
            listener.onFailure("Sem conexão com o servidor.");
        }
    }

    public void send(String type, String dst, JSONObject payload) {
        if (ws == null || closed) return;
        try {
            JSONObject m = new JSONObject();
            m.put("type", type);
            m.put("dst", dst);
            m.put("payload", payload);
            ws.send(m.toString());
        } catch (Exception ignored) { }
    }

    public void close() {
        closed = true;
        heartbeat.shutdownNow();
        if (ws != null) ws.close(1000, "fim");
        http.dispatcher().executorService().shutdown();
    }
}
