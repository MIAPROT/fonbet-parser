package com.fonbet;

import com.fonbet.model.MatchSnapshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Записывает срезы в CSV-файлы по четвертям (basketball_q1.csv … basketball_q4.csv).
 *
 * Формат строки:
 *   timestamp, match_id, team1, team2, total_score1, total_score2,
 *   quarter_score1, quarter_score2, minute, over_odds, under_odds, line, quarter_result
 *
 * Стратегия записи:
 *   - pending-срезы (quarterResult == null) НЕ записываются немедленно,
 *     они хранятся в SnapshotCollector
 *   - Как только четверть завершается, SnapshotCollector вызывает writeCompleted()
 *     и все срезы этого матча за эту четверть записываются разом с итогом
 */
public class CsvWriter {

    private static final String HEADER =
            "timestamp,match_id,team1,team2," +
            "total_score1,total_score2," +
            "quarter_score1,quarter_score2," +
            "quarter_number,minute," +
            "over_odds,under_odds,line,quarter_result";

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public CsvWriter() throws IOException {
        for (int q = 1; q <= 4; q++) {
            ensureFileWithHeader(pathForQuarter(q));
        }
    }

    private static String pathForQuarter(int quarterNumber) {
        return "basketball_q" + quarterNumber + ".csv";
    }

    private void ensureFileWithHeader(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                        new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
                pw.println(HEADER);
            }
            System.out.println("Created output file: " + file.getAbsolutePath());
        } else {
            System.out.println("Appending to existing file: " + file.getAbsolutePath());
        }
    }

    /**
     * Записывает список завершённых срезов (с известным quarterResult).
     * Каждая строка попадает в файл четверти по полю {@link MatchSnapshot#quarterNumber}.
     */
    public synchronized void writeCompleted(List<MatchSnapshot> snapshots) throws IOException {
        if (snapshots == null || snapshots.isEmpty()) return;

        Map<Integer, List<MatchSnapshot>> byQuarter = new LinkedHashMap<>();
        for (MatchSnapshot s : snapshots) {
            int q = s.quarterNumber;
            if (q < 1 || q > 4) continue;
            byQuarter.computeIfAbsent(q, k -> new ArrayList<>()).add(s);
        }

        for (Map.Entry<Integer, List<MatchSnapshot>> e : byQuarter.entrySet()) {
            int quarter = e.getKey();
            List<MatchSnapshot> list = e.getValue();
            String filePath = pathForQuarter(quarter);
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                        new FileOutputStream(filePath, true), StandardCharsets.UTF_8))) {
                for (MatchSnapshot s : list) {
                    pw.println(toCsvLine(s));
                }
                pw.flush();
            }
            MatchSnapshot first = list.get(0);
            System.out.printf("  Written %d snapshots for match %d Q%d (result=%s) → %s%n",
                    list.size(),
                    first.matchId,
                    first.quarterNumber,
                    first.quarterResult,
                    new File(filePath).getAbsolutePath());
        }
    }

    /**
     * Принудительная запись pending-срезов (при завершении программы).
     * quarterResult будет "N/A" для незавершённых четвертей.
     */
    public synchronized void flushPending(List<MatchSnapshot> snapshots) throws IOException {
        for (MatchSnapshot s : snapshots) {
            if (!s.isComplete()) s.quarterResult = "N/A";
        }
        writeCompleted(snapshots);
    }

    private String toCsvLine(MatchSnapshot s) {
        String ts = sdf.format(new Date(s.capturedAt));
        return String.join(",",
                escape(ts),
                String.valueOf(s.matchId),
                escape(s.team1),
                escape(s.team2),
                String.valueOf(s.totalScore1),
                String.valueOf(s.totalScore2),
                String.valueOf(s.quarterScore1),
                String.valueOf(s.quarterScore2),
                String.valueOf(s.quarterNumber),
                escape(s.minute),
                fmt(s.overOdds),
                fmt(s.underOdds),
                escape(s.totalLine != null ? s.totalLine : ""),
                escape(s.quarterResult != null ? s.quarterResult : "")
        );
    }

    private String fmt(double d) {
        return String.format("%.2f", d).replace(",", ".");
    }

    private String escape(String s) {
        if (s == null) return "";
        // Если строка содержит запятую или кавычки — оборачиваем в кавычки
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
