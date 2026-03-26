package com.fonbet.model;

/**
 * Один срез состояния матча в конкретный момент времени.
 *
 * Поля 1-7 заполняются сразу при снятии среза.
 * Поле quarterResult (пункт 8) заполняется после завершения четверти.
 */
public class MatchSnapshot {

    // Служебные поля
    public long   matchId;          // id root-события
    public long   capturedAt;       // System.currentTimeMillis()
    public int    quarterNumber;    // 1, 2, 3, 4

    // Поля 1-7 (заполняются сразу)
    public String team1;
    public String team2;
    public int    totalScore1;      // общий счёт команды 1 на момент среза
    public int    totalScore2;
    public String minute;           // строка таймера, например "3:45"
    public double overOdds;         // коэф. тотал больше
    public double underOdds;        // коэф. тотал меньше
    public String totalLine;        // линия тотала, например "28.5"

    // Поле 8 — итог четверти (заполняется позже)
    // Формат: "сумма очков обеих команд", например "32" (17+15)
    // или "N/A" если четверть была прервана / матч пропал из live
    public String quarterResult = null;

    // Счёт именно в текущей четверти на момент среза
    public int quarterScore1;
    public int quarterScore2;

    public boolean isComplete() {
        return quarterResult != null;
    }

    @Override
    public String toString() {
        return String.format("[%s vs %s | Q%d | %s | %.2f/%.2f @%s | result=%s]",
                team1, team2, quarterNumber, minute,
                overOdds, underOdds, totalLine,
                quarterResult != null ? quarterResult : "pending");
    }
}
