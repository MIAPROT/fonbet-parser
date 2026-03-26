package com.fonbet;

import com.fonbet.model.FonbetResponse.LiveEventInfo;
import com.fonbet.model.FonbetResponse.SportEvent;
import com.fonbet.model.MatchSnapshot;
import com.fonbet.FonbetState.*;
import com.fonbet.model.FonbetResponse.EventMisc;

import java.io.IOException;
import java.util.*;

/**
 * Ядро бизнес-логики.
 *
 * За что отвечает:
 *  1. При каждом обновлении состояния ищет активные баскетбольные матчи
 *  2. Для матчей в нужной четверти (по умолчанию Q1) снимает срезы
 *  3. Следит за переходом к следующей четверти — когда Q1 заканчивается,
 *     заполняет quarterResult у всех ожидающих срезов и пишет их в файл
 *
 * Логика дедупликации:
 *  Срез снимается не чаще одного раза в SNAPSHOT_INTERVAL_MS миллисекунд
 *  для каждого матча. Если в течение одной минуты коэффициенты изменились,
 *  оба среза сохраняются (по условию задачи это допустимо).
 */
public class SnapshotCollector {

    // Интервал между срезами для одного матча (30 секунд)
    private static final long SNAPSHOT_INTERVAL_MS = 30_000;

    // Номера четвертей, которые мы собираем.
    // Сейчас Q1, можно добавить 2,3,4 для полного сбора.
    private static final Set<Integer> TARGET_QUARTERS = new HashSet<>(
            Collections.singletonList(1)
    );

    // Хранит pending-срезы: (matchId + "_" + quarterNumber) → список срезов
    private final Map<String, List<MatchSnapshot>> pending = new LinkedHashMap<>();

    // Последнее время снятия среза для каждого матча: matchId → timestamp
    private final Map<Long, Long> lastSnapshotTime = new HashMap<>();

    // Предыдущая четверть для каждого матча: matchId → quarterNumber
    private final Map<Long, Integer> prevQuarter = new HashMap<>();

    // Кеш последнего известного счёта четверти: "matchId_quarter" → int[]{c1, c2}
    // Нужен потому что при смене четверти API иногда уже обнулил subscores
    private final Map<String, int[]> lastKnownQuarterScore = new HashMap<>();

    private final CsvWriter csvWriter;

    public SnapshotCollector(String outputFile) throws IOException {
        this.csvWriter = new CsvWriter(outputFile);

        // Shutdown hook — записываем незавершённые срезы при остановке
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown: flushing pending snapshots...");
            List<MatchSnapshot> allPending = new ArrayList<>();
            for (List<MatchSnapshot> list : pending.values()) {
                allPending.addAll(list);
            }
            if (!allPending.isEmpty()) {
                try {
                    csvWriter.flushPending(allPending);
                } catch (IOException e) {
                    System.err.println("Error flushing pending: " + e.getMessage());
                }
            }
        }));
    }

    /**
     * Основной метод — вызывается после каждого merge() состояния.
     */
    public void processState(FonbetState state) {
        List<BasketballMatch> matches = state.getActiveBasketballMatches();

        for (BasketballMatch bm : matches) {
            try {
                processMatch(state, bm);
            } catch (Exception e) {
                System.err.printf("Error processing match %d (%s vs %s): %s%n",
                        bm.event.id, bm.event.team1, bm.event.team2, e.getMessage());
            }
        }

        // Очищаем matchi, которых больше нет в live
        cleanupFinishedMatches(state, matches);
    }

    private void processMatch(FonbetState state, BasketballMatch bm) throws IOException {
        long matchId = bm.event.id;
        int activeQuarter = state.getActiveQuarter(bm.info);

        // Проверяем смену четверти (Q1 → Q2 и т.д.)
        Integer prev = prevQuarter.get(matchId);
        if (prev != null && prev != activeQuarter && activeQuarter > prev) {
            // Четверть завершилась — заполняем итоги
            onQuarterFinished(state, bm, prev, activeQuarter);
        }
        prevQuarter.put(matchId, activeQuarter);

        // Снимаем срез только для целевых четвертей
        if (!TARGET_QUARTERS.contains(activeQuarter)) return;

        // Пропускаем срезы без таймера (матч ещё не начался)
        if (bm.info.timer == null || bm.info.timer.trim().isEmpty()) return;

        // Дедупликация по времени
        long now = System.currentTimeMillis();
        Long lastTime = lastSnapshotTime.get(matchId);
        if (lastTime != null && (now - lastTime) < SNAPSHOT_INTERVAL_MS) return;

        // Ищем коэффициенты тотала четверти
        QuarterOdds odds = state.findQuarterOdds(matchId, activeQuarter);
        if (odds == null) {
            // Коэффициентов нет (матч ещё не открыт для ставок или блокировка)
            return;
        }

        // Получаем счёт текущей четверти
        int[] qScore = state.getQuarterScore(bm.info, activeQuarter);
        if (qScore == null) qScore = new int[]{0, 0};

        // Кешируем последний известный счёт четверти (для восстановления итога)
        String scoreKey = matchId + "_" + activeQuarter;
        if (qScore[0] > 0 || qScore[1] > 0) {
            lastKnownQuarterScore.put(scoreKey, new int[]{qScore[0], qScore[1]});
        }

        // Общий счёт матча
        EventMiscScore totalScore = getTotalScore(state, bm);

        // Формируем срез
        MatchSnapshot snapshot = new MatchSnapshot();
        snapshot.matchId      = matchId;
        snapshot.capturedAt   = now;
        snapshot.quarterNumber = activeQuarter;
        snapshot.team1        = bm.event.team1;
        snapshot.team2        = bm.event.team2;
        snapshot.totalScore1  = totalScore.s1;
        snapshot.totalScore2  = totalScore.s2;
        snapshot.quarterScore1 = qScore[0];
        snapshot.quarterScore2 = qScore[1];
        snapshot.minute       = bm.info.timer != null ? bm.info.timer : "?";
        snapshot.overOdds     = odds.overOdds;
        snapshot.underOdds    = odds.underOdds;
        snapshot.totalLine    = odds.line;

        // Сохраняем в pending
        String key = matchId + "_" + activeQuarter;
        pending.computeIfAbsent(key, k -> new ArrayList<>()).add(snapshot);

        lastSnapshotTime.put(matchId, now);

        System.out.printf("[SNAP] %s vs %s | Q%d %s | score %d-%d (q: %d-%d) | %.2f/%.2f @%s%n",
                snapshot.team1, snapshot.team2,
                snapshot.quarterNumber, snapshot.minute,
                snapshot.totalScore1, snapshot.totalScore2,
                snapshot.quarterScore1, snapshot.quarterScore2,
                snapshot.overOdds, snapshot.underOdds, snapshot.totalLine);
    }

    /**
     * Вызывается когда четверть завершилась (activeQuarter > prevQuarter).
     */
    private void onQuarterFinished(FonbetState state, BasketballMatch bm,
                                   int finishedQuarter, int newQuarter) throws IOException {
        String key = bm.event.id + "_" + finishedQuarter;
        List<MatchSnapshot> snaps = pending.remove(key);
        if (snaps == null || snaps.isEmpty()) return;

        // Стратегия 1: из scores[1] (четверти по порядку)
        int[] finalScore = getFinalQuarterScore(state, bm, finishedQuarter);

        // Стратегия 2: из кеша последнего известного счёта
        if (finalScore == null) {
            finalScore = lastKnownQuarterScore.get(key);
        }

        // Стратегия 3: парсим comment в EventMisc, например "(27-22 14-18 5-6)"
        if (finalScore == null) {
            finalScore = parseQuarterFromComment(state, bm.event.id, finishedQuarter);
        }

        lastKnownQuarterScore.remove(key);

        String result;
        if (finalScore != null) {
            int total = finalScore[0] + finalScore[1];
            result = String.valueOf(total) + " (" + finalScore[0] + "-" + finalScore[1] + ")";
        } else {
            result = "N/A";
        }

        System.out.printf("[QUARTER %d ENDED] %s vs %s | result=%s | writing %d snapshots%n",
                finishedQuarter, bm.event.team1, bm.event.team2, result, snaps.size());

        for (MatchSnapshot s : snaps) {
            s.quarterResult = result;
        }

        csvWriter.writeCompleted(snaps);
    }

    /**
     * Получает финальный счёт завершённой четверти.
     * После окончания Q1 её счёт остаётся в scores[1][0].
     */
    private int[] getFinalQuarterScore(FonbetState state, BasketballMatch bm, int quarter) {
        LiveEventInfo info = bm.info;
        if (info.scores != null && info.scores.size() > 1) {
            var byQuarters = info.scores.get(1);
            if (byQuarters != null && quarter <= byQuarters.size()) {
                var entry = byQuarters.get(quarter - 1);
                if ("четверть".equals(entry.title)) {
                    return new int[]{FonbetState.parseInt(entry.c1), FonbetState.parseInt(entry.c2)};
                }
            }
        }
        return null;
    }

    /**
     * Парсит счёт четверти из поля comment EventMisc.
     * Формат comment: "(Q1c1-Q1c2 Q2c1-Q2c2 Q3c1-Q3c2)"
     * Например: "(27-22 14-18 5-6)" — здесь 3 завершённые четверти.
     * Берём (finishedQuarter - 1)-й элемент (0-based).
     */
    private int[] parseQuarterFromComment(FonbetState state, long matchId, int quarter) {
        EventMisc misc = state.miscMap.get(matchId);
        if (misc == null || misc.comment == null || misc.comment.isEmpty()) return null;

        String c = misc.comment.trim();
        // Убираем скобки если есть
        if (c.startsWith("(")) c = c.substring(1);
        if (c.endsWith(")"))   c = c.substring(0, c.length() - 1);

        // Разбиваем по пробелу — каждый элемент это "c1-c2" одной четверти
        String[] parts = c.split("\s+");
        int idx = quarter - 1;
        if (idx < 0 || idx >= parts.length) return null;

        String part = parts[idx].trim();
        String[] scores = part.split("-");
        if (scores.length != 2) return null;

        try {
            return new int[]{Integer.parseInt(scores[0].trim()),
                             Integer.parseInt(scores[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Матч покинул live — записываем все оставшиеся pending срезы.
     */
    private void cleanupFinishedMatches(FonbetState state,
                                        List<BasketballMatch> activeMatches) {
        Set<Long> activeIds = new HashSet<>();
        for (BasketballMatch bm : activeMatches) activeIds.add(bm.event.id);

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, List<MatchSnapshot>> entry : pending.entrySet()) {
            String key = entry.getKey();
            long matchId = Long.parseLong(key.split("_")[0]);
            if (!activeIds.contains(matchId)) {
                toRemove.add(key);
            }
        }

        for (String key : toRemove) {
            List<MatchSnapshot> snaps = pending.remove(key);
            if (snaps != null && !snaps.isEmpty()) {
                System.out.printf("[MATCH GONE] Writing %d orphan snapshots for key=%s%n",
                        snaps.size(), key);
                for (MatchSnapshot s : snaps) {
                    if (!s.isComplete()) s.quarterResult = "N/A";
                }
                try {
                    csvWriter.writeCompleted(snaps);
                } catch (IOException e) {
                    System.err.println("Error writing orphan snapshots: " + e.getMessage());
                }
            }
            prevQuarter.remove(Long.parseLong(key.split("_")[0]));
            lastSnapshotTime.remove(Long.parseLong(key.split("_")[0]));
        }
    }

    private EventMiscScore getTotalScore(FonbetState state, BasketballMatch bm) {
        var misc = state.miscMap.get(bm.event.id);
        if (misc != null) return new EventMiscScore(misc.score1, misc.score2);

        // Fallback: из scores[0]
        if (bm.info.scores != null && !bm.info.scores.isEmpty()) {
            var top = bm.info.scores.get(0);
            if (top != null && !top.isEmpty()) {
                return new EventMiscScore(
                        FonbetState.parseInt(top.get(0).c1),
                        FonbetState.parseInt(top.get(0).c2));
            }
        }
        return new EventMiscScore(0, 0);
    }

    private static class EventMiscScore {
        int s1, s2;
        EventMiscScore(int s1, int s2) { this.s1 = s1; this.s2 = s2; }
    }
}
