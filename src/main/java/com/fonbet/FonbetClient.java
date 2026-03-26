package com.fonbet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-клиент для Fonbet API.
 *
 * Механика polling'а (delta-обновления):
 *   1. Первый запрос: version = 0  →  получаем полный снапшот состояния
 *   2. Следующие запросы: version = packetVersion из предыдущего ответа
 *      →  получаем только изменения (delta)
 */
public class FonbetClient {

    // Базовый URL — если перестанет работать, обнови lb61 на актуальный (смотри в DevTools)
    private static final String BASE_URL =
            "https://line-lb61-w.bk6bba-resources.com/ma/events/list";

    private static final String SCOPE_MARKET = "1600";
    private static final String LANG         = "ru";

    private final OkHttpClient httpClient;

    public FonbetClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Выполняет запрос к API и возвращает тело ответа (JSON строка).
     *
     * @param version версия пакета (0 для первого запроса,
     *                затем packetVersion из предыдущего ответа)
     */
    public String fetchEvents(long version) throws IOException {
        String url = BASE_URL
                + "?lang=" + LANG
                + "&version=" + version
                + "&scopeMarket=" + SCOPE_MARKET;

        Request request = new Request.Builder()
                .url(url)
                // Заголовки, имитирующие браузер — помогают избежать блокировки
                .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/124.0.0.0 Safari/537.36")
                .header("Referer",  "https://www.fonbet.ru/")
                .header("Accept",   "application/json, text/plain, */*")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .header("Origin",   "https://www.fonbet.ru")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            String body = response.body() != null ? response.body().string() : "";
            if (body.isEmpty()) {
                throw new IOException("Empty response body");
            }
            return body;
        }
    }
}
