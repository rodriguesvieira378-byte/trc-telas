package com.cassio.telashare;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Registro de erros do app na tabela "logs" do Supabase (só gravação; ninguém de fora lê).
 * Também guarda os travamentos (crash) num arquivo e manda na próxima vez que o app abrir.
 */
public final class Relatorio {
    private static final String URL = "https://iptwnahbgwnollnwrqdx.supabase.co/rest/v1/logs";
    private static final String KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlwdHduYWhiZ3dub2xsbndycWR4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTU3MTUzNzYsImV4cCI6MjExMTI5MTM3Nn0.ymrqyXGTxgbTaTc6V_MDX7wEiEF-PXA0q5VYhJax6Dw";
    private static final String CRASH_FILE = "ultimo-travamento.txt";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS).build();
    private static volatile boolean instalado = false;
    private static volatile String versao = "?";

    private Relatorio() { }

    /** Instala o "pegador" de travamentos e envia o travamento da última vez, se houver. */
    public static synchronized void instalar(Context ctx) {
        final Context app = ctx.getApplicationContext();
        try { versao = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        if (instalado) return;
        instalado = true;
        final Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String txt = "thread " + t.getName() + "\n" + sw;
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), CRASH_FILE))) {
                    out.write(txt.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            if (anterior != null) anterior.uncaughtException(t, e);
        });
        try {
            File f = new File(app.getFilesDir(), CRASH_FILE);
            if (f.exists()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[4096]; int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                }
                String txt = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                enviar("app-travou", txt, null);
            }
        } catch (Exception ignored) { }
    }

    public static void enviar(String evento, String detalhe, String sala) {
        try {
            JSONObject j = new JSONObject();
            j.put("origem", "apk-nativo");
            j.put("evento", cortar(evento, 40));
            if (detalhe != null) j.put("detalhe", cortar(detalhe, 4000));
            j.put("aparelho", cortar(Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE, 200));
            j.put("versao", cortar(versao, 20));
            if (sala != null) j.put("sala", cortar(sala, 10));
            Request req = new Request.Builder().url(URL)
                    .addHeader("apikey", KEY)
                    .addHeader("Authorization", "Bearer " + KEY)
                    .addHeader("Prefer", "return=minimal")
                    .post(RequestBody.create(j.toString(), MediaType.get("application/json")))
                    .build();
            HTTP.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call c, java.io.IOException e) { }
                @Override public void onResponse(Call c, Response r) { r.close(); }
            });
        } catch (Exception ignored) { }
    }

    private static String cortar(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
}
