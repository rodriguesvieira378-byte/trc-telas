package com.cassio.telashare;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Cliente dos canais Realtime do Supabase (protocolo Phoenix), usado só como "correio" de mensagens.
 * Cada sala (o código de 6 letras) é um canal. Fala o mesmo protocolo do app.js do site:
 * OFFER / ANSWER / CANDIDATE / LEAVE com payload JSON, filtrando pelo destinatário (dst).
 */
public class SignalClient {

    public interface Listener {
        void onOpen();
        void onIdTaken();
        void onMessage(String type, String src, JSONObject payload);
        void onFailure(String reason);
    }

    private static final String URL = "wss://iptwnahbgwnollnwrqdx.supabase.co/realtime/v1/websocket";
    private static final String KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlwdHduYWhiZ3dub2xsbndycWR4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTU3MTUzNzYsImV4cCI6MjExMTI5MTM3Nn0.ymrqyXGTxgbTaTc6V_MDX7wEiEF-PXA0q5VYhJax6Dw";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger ref = new AtomicInteger();
    private final Listener listener;
    private WebSocket ws;
    private String myId, topic, joinRef;
    private volatile boolean closed = false, opened = false;

    public SignalClient(Listener listener) { this.listener = listener; }

    /** id = "trctelas-ABC123"; a sala é o código depois do prefixo. */
    public void connect(String id) {
        myId = id;
        String room = id.startsWith("trctelas-") ? id.substring(9) : id;
        topic = "realtime:trctelas-" + room;
        try {
            ws = http.newWebSocket(new Request.Builder().url(URL + "?apikey=" + KEY + "&vsn=1.0.0").build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket s, Response r) {
                    try {
                        joinRef = String.valueOf(ref.incrementAndGet());
                        JSONObject cfg = new JSONObject()
                                .put("broadcast", new JSONObject().put("self", false).put("ack", false))
                                .put("presence", new JSONObject().put("key", ""))
                                .put("postgres_changes", new JSONArray())
                                .put("private", false);
                        JSONObject m = new JSONObject().put("topic", topic).put("event", "phx_join")
                                .put("ref", joinRef).put("join_ref", joinRef)
                                .put("payload", new JSONObject().put("config", cfg).put("access_token", KEY));
                        s.send(m.toString());
                        heartbeat.scheduleAtFixedRate(() -> {
                            if (closed || ws == null) return;
                            try {
                                ws.send(new JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                                        .put("payload", new JSONObject()).put("ref", String.valueOf(ref.incrementAndGet())).toString());
                            } catch (Exception ignored) { }
                        }, 25, 25, TimeUnit.SECONDS);
                    } catch (Exception e) { listener.onFailure("Sem conexão com o servidor."); }
                }

                @Override public void onMessage(WebSocket s, String text) {
                    try {
                        JSONObject m = new JSONObject(text);
                        String event = m.optString("event");
                        if ("phx_reply".equals(event) && joinRef != null && joinRef.equals(m.optString("ref"))) {
                            JSONObject p = m.optJSONObject("payload");
                            if (p != null && "ok".equals(p.optString("status"))) { opened = true; listener.onOpen(); }
                            else listener.onFailure("Erro no servidor.");
                        } else if ("broadcast".equals(event)) {
                            JSONObject p = m.optJSONObject("payload");
                            if (p == null || !"sig".equals(p.optString("event"))) return;
                            JSONObject msg = p.optJSONObject("payload");
                            if (msg == null || !myId.equals(msg.optString("dst"))) return;
                            JSONObject payload = msg.optJSONObject("payload");
                            listener.onMessage(msg.optString("type"), msg.optString("src"), payload != null ? payload : new JSONObject());
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
            JSONObject msg = new JSONObject().put("type", type).put("src", myId).put("dst", dst).put("payload", payload);
            JSONObject m = new JSONObject().put("topic", topic).put("event", "broadcast")
                    .put("ref", String.valueOf(ref.incrementAndGet())).put("join_ref", joinRef)
                    .put("payload", new JSONObject().put("type", "broadcast").put("event", "sig").put("payload", msg));
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
