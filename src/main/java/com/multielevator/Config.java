package com.multielevator;

public class Config {
    // Насколько «скоро» лифт должен закончить текущий маршрут,
    // чтобы мы разрешили предварительно назначить вызов противоположного направления.
    public static final int RESERVE_REVERSE_SOON_FLOORS = 3;

    // Настройки диспетчеризации (для событийной модели)
    public static final int DISPATCHER_EVENT_BATCH = 64;
    public static final long NO_ELEVATOR_LOG_COOLDOWN_MS = 1500;

    // Если hall-call уже назначен одному лифту, но другой лифт становится заметно лучше
    // (например, проезжает мимо и может подобрать людей "по пути"), допускаем
    // переназначение, но с гистерезисом, чтобы не было "дёрганья" назначения.
    public static final int CALL_REASSIGN_MIN_IMPROVEMENT = 12;
    public static final long CALL_REASSIGN_COOLDOWN_MS = 1500;

    // Параметры здания
    public static final int FLOORS = 15;
    public static final int ELEVATORS_COUNT = 3;
    public static final int ELEVATOR_CAPACITY = 5;

    // ===== Реалистичное поведение (как в «настоящем» доме) =====

    /**
     * Мягкое зонирование (high/low + один «swing car»).
     * Не запрещает обслуживать вызовы вне зоны, но делает это менее вероятным,
     * чтобы лифты распределялись естественно.
     */
    public static final boolean ZONING_ENABLED = true;

    /**
     * Id лифта, который обслуживает весь дом без штрафа (обычно «третий» лифт).
     * Если лифтов меньше 3 — swing отсутствует.
     */
    public static final int SWING_ELEVATOR_ID = (ELEVATORS_COUNT >= 3) ? ELEVATORS_COUNT : -1;

    /** Штраф в стоимости, если вызов вне зоны лифта. */
    public static final int ZONE_SOFT_PENALTY = 10;

    /**
     * Верхняя граница «нижней зоны». Для 15 этажей это будет 8.
     * Нижняя граница «верхней зоны» совпадает (перекрытие на 1 этаж).
     */
    public static final int ZONE_SPLIT_FLOOR = (FLOORS + 1) / 2;

    /** Предпочтительная минимальная граница зоны для лифта. */
    public static int zoneMinFloor(int elevatorId) {
        if (!ZONING_ENABLED) return 1;
        if (elevatorId == SWING_ELEVATOR_ID) return 1;
        if (ELEVATORS_COUNT >= 2) {
            // Elevator-1: низ, Elevator-2: верх
            if (elevatorId == 1) return 1;
            if (elevatorId == 2) return ZONE_SPLIT_FLOOR;
        }
        return 1;
    }

    /** Предпочтительная максимальная граница зоны для лифта. */
    public static int zoneMaxFloor(int elevatorId) {
        if (!ZONING_ENABLED) return FLOORS;
        if (elevatorId == SWING_ELEVATOR_ID) return FLOORS;
        if (ELEVATORS_COUNT >= 2) {
            if (elevatorId == 1) return ZONE_SPLIT_FLOOR;
            if (elevatorId == 2) return FLOORS;
        }
        return FLOORS;
    }

    /** Штраф, если этаж вызова вне зоны лифта. */
    public static int zonePenalty(int elevatorId, int callFloor) {
        if (!ZONING_ENABLED) return 0;
        int min = zoneMinFloor(elevatorId);
        int max = zoneMaxFloor(elevatorId);
        return (callFloor < min || callFloor > max) ? ZONE_SOFT_PENALTY : 0;
    }

    /**
     * Реалистичное правило: движущийся лифт обслуживает внешние вызовы только
     * своего направления. Противонаправленные — только как «резерв» после разворота/idle.
     */
    public static final boolean STRICT_HALL_DIRECTION = true;

    /**
     * Разрешать лифту делать остановку "по пути" ради ожидающих на этаже в том же направлении.
     * Это делает поведение более реалистичным и убирает ощущение, что лифт "пропускает" людей.
     */
    public static final boolean ENROUTE_PICKUP_ENABLED = true;

    /**
     * Если hall-call уже назначен другому лифту, мы "перехватываем" его по месту (на этаже)
     * только если назначенный лифт заметно дальше, иначе избегаем лишних остановок/дубликатов.
     */
    public static final int ENROUTE_STEAL_MIN_ASSIGNED_DISTANCE = 3;

    // Ограничения маршрута
    // (защита от «раздувания» очереди остановок и бесконечных переназначений)
    public static final int MAX_PLANNED_STOPS = 20;

    // Тайминги (в миллисекундах)
    public static final int TIME_MOVE_ONE_FLOOR = 800;  // Время проезда одного этажа
    public static final int TIME_DOORS = 500;           // Время открытия/закрытия дверей
    public static final int TIME_BOARDING = 200;        // Время посадки одного человека

    // ===== Управление длительностью симуляции =====
    /** Сколько пассажиров сгенерировать в одной симуляции. */
    public static final int PASSENGER_LIMIT = 30;

    /** Интервал генерации заявок (мс). */
    public static final int REQUEST_INTERVAL_MIN = 500;
    public static final int REQUEST_INTERVAL_MAX = 1200;

    /**
     * Максимальное время «доигрывания» после генерации последнего пассажира.
     * Нужно, чтобы программа гарантированно завершалась даже при редких
     * патологических сценариях.
     */
    public static final long DRAIN_TIMEOUT_MS = 180_000; // 3 минуты
}