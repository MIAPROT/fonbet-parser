package com.fonbet;

import com.fonbet.model.MatchSnapshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Записывает срезы в CSV-файл.
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

    private final String filePath;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public CsvWriter(String filePath) throws IOException {
        this.filePath = filePath;

        // Создаём файл с заголовком если не существует
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
     */
    public synchronized void writeCompleted(List<MatchSnapshot> snapshots) throws IOException {
        if (snapshots == null || snapshots.isEmpty()) return;

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                    new FileOutputStream(filePath, true), StandardCharsets.UTF_8))) {
            for (MatchSnapshot s : snapshots) {
                pw.println(toCsvLine(s));
            }
            pw.flush();
        }
        System.out.printf("  Written %d snapshots for match %d Q%d (result=%s)%n",
                snapshots.size(),
                snapshots.get(0).matchId,
                snapshots.get(0).quarterNumber,
                snapshots.get(0).quarterResult);
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
