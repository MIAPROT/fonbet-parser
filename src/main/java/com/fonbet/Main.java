package com.fonbet;

import com.fonbet.model.FonbetResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Точка входа. Запускает непрерывный polling Fonbet API.
 *
 * Механика:
 *  1. Первый запрос: version=0 → получаем полное состояние
 *  2. Используем packetVersion из ответа как следующий version
 *  3. Каждые POLL_INTERVAL_MS мс запрашиваем delta-обновления
 *  4. Накапливаем состояние в FonbetState
 *  5. SnapshotCollector анализирует состояние и собирает срезы
 *
 * Запуск: java -jar fonbet-parser-1.0-jar-with-dependencies.jar
 */
public class Main {

    // Интервал между запросами к API (5 секунд)
    private static final long POLL_INTERVAL_MS = 5_000;

    // Пауза при ошибке (15 секунд)
    private static final long ERROR_PAUSE_MS   = 15_000;

    // Имя выходного файла
    private static final String OUTPUT_FILE    = "basketball_snapshots.csv";

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        System.out.println("=== Fonbet Basketball Parser ===");
        System.out.println("Output file : " + OUTPUT_FILE);
        System.out.println("Poll interval: " + POLL_INTERVAL_MS / 1000 + "s");
        System.out.println("Collecting quarters: Q1 (extend in SnapshotCollector.TARGET_QUARTERS)");
        System.out.println("Press Ctrl+C to stop (pending snapshots will be flushed)");
        System.out.println("=====================================\n");

        FonbetClient      client    = new FonbetClient();
        FonbetState       state     = new FonbetState();
        SnapshotCollector collector = new SnapshotCollector(OUTPUT_FILE);

        long version      = 0;
        int  errorCount   = 0;
        int  successCount = 0;

        while (true) {
            try {
                String ts = LocalDateTime.now().format(DTF);
                System.out.printf("[%s] Polling version=%d ...%n", ts, version);

                String json = client.fetchEvents(version);
                FonbetResponse response = FonbetParser.parse(json);

                state.merge(response);
                collector.processState(state);

                version = response.packetVersion;
                errorCount = 0;
                successCount++;

                if (successCount % 20 == 0) {
                    System.out.printf("  Stats: events=%d live-infos=%d factors-entries=%d%n",
                            state.events.size(),
                            state.liveInfos.size(),
                            state.factorsMap.size());
                }

            } catch (Exception e) {
                errorCount++;
                System.err.printf("[ERROR #%d] %s: %s%n",
                        errorCount, e.getClass().getSimpleName(), e.getMessage());

                if (errorCount >= 5) {
                    System.err.println("Too many consecutive errors, resetting version to 0");
                    version = 0;
                    errorCount = 0;
                }

                Thread.sleep(ERROR_PAUSE_MS);
                continue;
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }
    }
}
