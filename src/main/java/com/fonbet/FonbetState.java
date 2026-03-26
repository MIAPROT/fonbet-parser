package com.fonbet;

import com.fonbet.model.FonbetResponse;
import com.fonbet.model.FonbetResponse.*;
import com.fonbet.model.FonbetResponse.Sport;

import java.util.*;

/**
 * Хранит полное состояние всех событий в памяти.
 *
 * API отдаёт delta-пакеты: каждый пакет содержит только изменения
 * относительно предыдущего. Этот класс накапливает изменения в единое состояние.
 *
 * Ключевые структуры:
 *   events       — все события по id (root + sub-events)
 *   children     — parent → [child ids]
 *   liveInfos    — live-данные по eventId
 *   factorsMap   — коэффициенты по eventId
 *   miscMap      — вспомогательные данные (счёт) по eventId
 */
public class FonbetState {

    // id спорта "Баскетбол" у Fonbet = 3
    // Но надёжнее определять по scoreFunction="Basketball" в liveEventInfos
    private static final String BASKETBALL_SCORE_FUNCTION = "Basketball";

    // kindId в subscores для четвертей
    public static final String KIND_Q1 = "100401";
    public static final String KIND_Q2 = "100402";
    public static final String KIND_Q3 = "100403";
    public static final String KIND_Q4 = "100404";

    // kind в events[] для sub-events четвертей
    public static final long EVENT_KIND_Q1 = 100401L;
    public static final long EVENT_KIND_Q2 = 100402L;
    public static final long EVENT_KIND_Q3 = 100403L;
    public static final long EVENT_KIND_Q4 = 100404L;

    // ─── Хранилища ─────────────────────────────────────────────────────────
    public final Map<Long, SportEvent>       events     = new HashMap<>();
    public final Map<Long, Set<Long>>        children   = new HashMap<>();
    public final Map<Long, LiveEventInfo>    liveInfos  = new HashMap<>();
    public final Map<Long, List<Factor>>     factorsMap = new HashMap<>();
    public final Map<Long, EventMisc>        miscMap    = new HashMap<>();
    // Сегменты/лиги: id → name (для фильтрации киберспорта)
    public final Map<Long, String>           sportNames = new HashMap<>();

    // ─── Merge ─────────────────────────────────────────────────────────────

    /**
     * Накладывает delta-пакет на текущее состояние.
     */
    public void merge(FonbetResponse response) {

        // 0. Сегменты/лиги
        if (response.sports != null) {
            for (Sport s : response.sports) {
                if (s.name != null) sportNames.put(s.id, s.name);
            }
        }

        // 1. События
        if (response.events != null) {
            for (SportEvent e : response.events) {
                events.put(e.id, e);
                if (e.parentId != null) {
                    children.computeIfAbsent(e.parentId, k -> new HashSet<>()).add(e.id);
                }
            }
        }

        // 2. LiveEventInfos
        if (response.liveEventInfos != null) {
            for (LiveEventInfo info : response.liveEventInfos) {
                liveInfos.put(info.eventId, info);
            }
        }

        // 3. Коэффициенты
        if (response.customFactors != null) {
            for (CustomFactors cf : response.customFactors) {
                if (cf.clearFactors || cf.countAll == 0) {
                    // Коэффициенты убраны (матч завершён или недоступен)
                    factorsMap.remove(cf.e);
                } else if (cf.factors != null && !cf.factors.isEmpty()) {
                    factorsMap.put(cf.e, cf.factors);
                }
            }
        }

        // 4. EventMiscs
        if (response.eventMiscs != null) {
            for (EventMisc misc : response.eventMiscs) {
                miscMap.put(misc.id, misc);
            }
        }
    }

    // ─── Запросы к состоянию ───────────────────────────────────────────────

    // Ключевые слова в названии сегмента/лиги, однозначно указывающие на киберспорт
    private static final String[] ESPORTS_KEYWORDS = {
        "2k", "esport", "h2h", "virtual", "gg league", "нба 2к",
        "fifa", "fc 2", "fc 26", "fc 24", "nba 2k"
    };

    // Ключевые слова в названии сегмента, указывающие на женский баскетбол
    private static final String[] WOMEN_KEYWORDS = {
        "женщин", "женск", "women", "ladies", "female", "(ж)"
    };

    /**
     * Возвращает список активных live-баскетбольных матчей.
     * Фильтрует по названию сегмента (sportId → sportNames).
     * Надёжнее фильтра по именам команд.
     */
    public List<BasketballMatch> getActiveBasketballMatches() {
        List<BasketballMatch> result = new ArrayList<>();

        for (Map.Entry<Long, LiveEventInfo> entry : liveInfos.entrySet()) {
            LiveEventInfo info = entry.getValue();

            // Только баскетбол
            if (!BASKETBALL_SCORE_FUNCTION.equals(info.scoreFunction)) continue;

            SportEvent event = events.get(info.eventId);
            if (event == null) continue;

            // Только live root-события с командами
            if (!"live".equals(event.place)) continue;
            if (event.level != 1) continue;
            if (event.team1 == null || event.team1.isEmpty()) continue;

            // Проверяем название сегмента лиги
            String segmentName = sportNames.getOrDefault(event.sportId, "").toLowerCase();

            // Исключаем киберспорт по названию сегмента
            if (isEsports(segmentName)) continue;

            // Исключаем женский баскетбол по названию сегмента
            if (isWomen(segmentName)) continue;

            // Дополнительно: исключаем по именам команд (ник в скобках = кибер)
            String t1 = event.team1.toLowerCase();
            String t2 = event.team2 != null ? event.team2.toLowerCase() : "";
            if (isEsportsTeam(t1) || isEsportsTeam(t2)) continue;

            // Женский баскетбол по имени команды
            if (t1.contains("(ж)") || t2.contains("(ж)")) continue;

            result.add(new BasketballMatch(event, info));
        }

        return result;
    }

    private boolean isEsports(String segmentNameLower) {
        for (String kw : ESPORTS_KEYWORDS) {
            if (segmentNameLower.contains(kw)) return true;
        }
        return false;
    }

    private boolean isWomen(String segmentNameLower) {
        for (String kw : WOMEN_KEYWORDS) {
            if (segmentNameLower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Запасной фильтр по имени команды: ник игрока в скобках → кибер.
     * Например: "торонто (carnage)", "металлург (kurts_2kgrind---)"
     */
    private boolean isEsportsTeam(String teamNameLower) {
        int open  = teamNameLower.lastIndexOf('(');
        int close = teamNameLower.lastIndexOf(')');
        if (open < 0 || close <= open) return false;
        String inside = teamNameLower.substring(open + 1, close).trim();
        if (inside.equals("ж")) return false;
        // Латинские буквы или цифры внутри скобок = ник игрока
        return inside.matches(".*[a-z0-9_-].*");
    }

    /**
     * Определяет активную четверть для матча.
     * Возвращает 1, 2, 3, 4 или 0 если не определить.
     */
    public int getActiveQuarter(LiveEventInfo info) {
        if (info.subscores == null || info.subscores.isEmpty()) {
            // Попробуем определить через scores[1] (массив по четвертям)
            return getQuarterFromScores(info);
        }

        // В subscores хранятся только АКТИВНЫЕ периоды
        // Ищем самую позднюю четверть
        int maxQuarter = 0;
        for (Subscore s : info.subscores) {
            int q = kindIdToQuarterNumber(s.kindId);
            if (q > 0 && q > maxQuarter) maxQuarter = q;
        }
        if (maxQuarter > 0) return maxQuarter;

        return getQuarterFromScores(info);
    }

    private int getQuarterFromScores(LiveEventInfo info) {
        if (info.scores == null || info.scores.size() < 2) return 1;
        List<ScoreEntry> byQuarters = info.scores.get(1);
        if (byQuarters == null) return 1;
        long count = byQuarters.stream()
                .filter(s -> "четверть".equals(s.title))
                .count();
        return count > 0 ? (int) count : 1;
    }

    /**
     * Получает счёт в четверти из subscores.
     * Возвращает int[2] = {c1, c2} или null если не найдено.
     */
    public int[] getQuarterScore(LiveEventInfo info, int quarterNumber) {
        String kindId = quarterNumberToKindId(quarterNumber);
        if (kindId == null) return null;

        if (info.subscores != null) {
            for (Subscore s : info.subscores) {
                if (kindId.equals(s.kindId)) {
                    return new int[]{parseInt(s.c1), parseInt(s.c2)};
                }
            }
        }

        // Попробуем из scores[1]
        if (info.scores != null && info.scores.size() > 1) {
            List<ScoreEntry> byQuarters = info.scores.get(1);
            if (byQuarters != null && quarterNumber <= byQuarters.size()) {
                ScoreEntry se = byQuarters.get(quarterNumber - 1);
                if ("четверть".equals(se.title)) {
                    return new int[]{parseInt(se.c1), parseInt(se.c2)};
                }
            }
        }

        return null;
    }

    /**
     * Ищет коэффициенты тотала для четверти.
     *
     * Стратегия:
     *  1. Ищем sub-event с kind=100401 (Q1) у данного root-события
     *     и берём его customFactors f930/f931
     *  2. Если sub-event не найден в events[], перебираем дочерние события
     *     и ищем те, у которых f930/f931 с линией < 100 (признак четвертного тотала)
     */
    public QuarterOdds findQuarterOdds(long rootEventId, int quarterNumber) {
        long targetKind = quarterNumberToKind(quarterNumber);

        Set<Long> childIds = children.getOrDefault(rootEventId, Collections.emptySet());

        // Стратегия 1: ищем sub-event с нужным kind
        for (Long childId : childIds) {
            SportEvent child = events.get(childId);
            if (child != null && child.kind == targetKind) {
                QuarterOdds odds = extractQuarterOdds(childId);
                if (odds != null) return odds;
            }
        }

        // Стратегия 2: перебираем дочерние и ищем маленькие тоталы (< 100)
        for (Long childId : childIds) {
            QuarterOdds odds = extractQuarterOdds(childId);
            if (odds != null) return odds;
        }

        return null;
    }

    private QuarterOdds extractQuarterOdds(long eventId) {
        List<Factor> factors = factorsMap.get(eventId);
        if (factors == null) return null;

        Factor over = null, under = null;
        for (Factor f : factors) {
            if (f.f == 930 && f.v > 1.0) over  = f;
            if (f.f == 931 && f.v > 1.0) under = f;
        }

        if (over == null || under == null) return null;

        // Проверяем что линия соответствует четверти (< 100 очков)
        double line = parsePt(over.pt);
        if (line <= 0 || line >= 100) return null;

        return new QuarterOdds(over.v, under.v, over.pt, line);
    }

    // ─── Вспомогательные методы ────────────────────────────────────────────

    public static int kindIdToQuarterNumber(String kindId) {
        switch (kindId) {
            case KIND_Q1: return 1;
            case KIND_Q2: return 2;
            case KIND_Q3: return 3;
            case KIND_Q4: return 4;
            default:      return 0;
        }
    }

    public static String quarterNumberToKindId(int q) {
        switch (q) {
            case 1: return KIND_Q1;
            case 2: return KIND_Q2;
            case 3: return KIND_Q3;
            case 4: return KIND_Q4;
            default: return null;
        }
    }

    public static long quarterNumberToKind(int q) {
        switch (q) {
            case 1: return EVENT_KIND_Q1;
            case 2: return EVENT_KIND_Q2;
            case 3: return EVENT_KIND_Q3;
            case 4: return EVENT_KIND_Q4;
            default: return 0;
        }
    }

    public static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    public static double parsePt(String pt) {
        if (pt == null || pt.isEmpty()) return 0;
        try { return Double.parseDouble(pt.trim()); }
        catch (Exception e) { return 0; }
    }

    // ─── Вложенные структуры ──────────────────────────────────────────────

    /** Обёртка: root-событие + его live-информация */
    public static class BasketballMatch {
        public final SportEvent    event;
        public final LiveEventInfo info;

        public BasketballMatch(SportEvent event, LiveEventInfo info) {
            this.event = event;
            this.info  = info;
        }
    }

    /** Найденные коэффициенты тотала четверти */
    public static class QuarterOdds {
        public final double overOdds;
        public final double underOdds;
        public final String line;       // строка, например "28.5"
        public final double lineValue;  // число, например 28.5

        public QuarterOdds(double overOdds, double underOdds, String line, double lineValue) {
            this.overOdds  = overOdds;
            this.underOdds = underOdds;
            this.line      = line;
            this.lineValue = lineValue;
        }
    }
}
