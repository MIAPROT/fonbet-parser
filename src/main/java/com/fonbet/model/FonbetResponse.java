package com.fonbet.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// ─────────────────────────────────────────────
// Корневой ответ API (delta-пакет)
// ─────────────────────────────────────────────
@JsonIgnoreProperties(ignoreUnknown = true)
public class FonbetResponse {

    @JsonProperty("packetVersion")
    public long packetVersion;

    @JsonProperty("fromVersion")
    public long fromVersion;

    @JsonProperty("events")
    public List<SportEvent> events;

    @JsonProperty("eventMiscs")
    public List<EventMisc> eventMiscs;

    @JsonProperty("liveEventInfos")
    public List<LiveEventInfo> liveEventInfos;

    @JsonProperty("sports")
    public List<Sport> sports;

    @JsonProperty("customFactors")
    public List<CustomFactors> customFactors;

    // ─────────────────────────────────────────
    // Спортивный сегмент (лига/турнир)
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sport {
        @JsonProperty("id")   public long id;
        @JsonProperty("name") public String name;   // "Испания. LEB Oro", "NBA 2K26. Esportsbattle..."
        @JsonProperty("kind") public String kind;   // "sport" или "segment"
        @JsonProperty("alias") public String alias; // "basketball", null у сегментов
    }

    // ─────────────────────────────────────────
    // Спортивное событие (матч или sub-event)
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SportEvent {
        @JsonProperty("id")       public long id;
        @JsonProperty("parentId") public Long parentId;   // null для root-событий
        @JsonProperty("level")    public int level;       // 1 = root, 2 = sub-event
        @JsonProperty("sportId")  public long sportId;
        @JsonProperty("kind")     public long kind;       // 1=матч, 100401=Q1, 100402=Q2...
        @JsonProperty("rootKind") public long rootKind;
        @JsonProperty("team1")    public String team1;
        @JsonProperty("team2")    public String team2;
        @JsonProperty("name")     public String name;     // для sub-events ("1-я четверть" и т.д.)
        @JsonProperty("place")    public String place;    // "live" / "line" / "notActive"
    }

    // ─────────────────────────────────────────
    // Доп. данные события (таймер, счёт)
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventMisc {
        @JsonProperty("id")      public long id;
        @JsonProperty("score1")  public int score1;
        @JsonProperty("score2")  public int score2;
        // Пример: "(27-22 14-18)" — счёт по завершённым четвертям
        @JsonProperty("comment") public String comment;
    }

    // ─────────────────────────────────────────
    // Live-информация события
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LiveEventInfo {
        @JsonProperty("eventId")        public long eventId;
        @JsonProperty("timer")          public String timer;        // "1:23"
        @JsonProperty("timerSeconds")   public int timerSeconds;
        @JsonProperty("timerDirection") public int timerDirection;  // 0=вниз, 1=вверх
        @JsonProperty("scoreFunction")  public String scoreFunction; // "Basketball"
        @JsonProperty("scores")         public List<List<ScoreEntry>> scores;
        @JsonProperty("subscores")      public List<Subscore> subscores;
    }

    // ─────────────────────────────────────────
    // Счёт (в разбивке по периодам/четвертям)
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScoreEntry {
        @JsonProperty("c1")    public String c1;
        @JsonProperty("c2")    public String c2;
        @JsonProperty("title") public String title;  // "четверть", "период", "сет"
    }

    // ─────────────────────────────────────────
    // Подсчёт по периоду (текущая четверть и т.д.)
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Subscore {
        @JsonProperty("kindId")   public String kindId;    // "100401" = Q1, "100402" = Q2...
        @JsonProperty("kindName") public String kindName;  // "1-я четверть"
        @JsonProperty("c1")       public String c1;
        @JsonProperty("c2")       public String c2;
    }

    // ─────────────────────────────────────────
    // Коэффициенты события
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CustomFactors {
        @JsonProperty("e")            public long e;         // eventId
        @JsonProperty("countAll")     public int countAll;
        @JsonProperty("clearFactors") public boolean clearFactors;
        @JsonProperty("factors")      public List<Factor> factors;
    }

    // ─────────────────────────────────────────
    // Один коэффициент
    // f=930 → Тотал Больше
    // f=931 → Тотал Меньше
    // pt → линия в строковом виде, например "28.5"
    // ─────────────────────────────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Factor {
        @JsonProperty("f")  public int f;
        @JsonProperty("v")  public double v;
        @JsonProperty("p")  public Integer p;   // param * 100 (nullable)
        @JsonProperty("pt") public String pt;   // param string, например "28.5"
    }
}
