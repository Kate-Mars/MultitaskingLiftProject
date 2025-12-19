package com.multielevator;

/**
 * Идея: выбрать лифт с минимальной стоимостью принятия вызова, учитывая
 * расстояние, направление, загрузку и текущую длину маршрута.
 */
public final class CollectiveControlStrategy {

    public int calculateCost(ElevatorSnapshot s, HallCall call) {
        final int targetFloor = call.floor();
        final Direction reqDir = call.direction();
        final int zonePenalty = Config.zonePenalty(s.id(), targetFloor);
        int etaDistance;

        double directionPenalty;
        if (s.direction() == Direction.IDLE) {
            etaDistance = Math.abs(s.currentFloor() - targetFloor);
            directionPenalty = 1.5;
        } else if (s.direction() == reqDir) {
            if (isOnTheWay(s, call)) {
                etaDistance = Math.abs(s.currentFloor() - targetFloor);
                directionPenalty = 1.0;
            } else {
                // лифт доедет до ближайшей границы своего маршрута и вернётся.
                int end = (s.direction() == Direction.UP)
                        ? (s.furthestUpStop() > 0 ? s.furthestUpStop() : s.currentFloor())
                        : (s.furthestDownStop() > 0 ? s.furthestDownStop() : s.currentFloor());
                etaDistance = Math.abs(s.currentFloor() - end) + Math.abs(end - targetFloor);
                directionPenalty = 6.0;
            }
        } else {
            int end = (s.direction() == Direction.UP)
                    ? (s.furthestUpStop() > 0 ? s.furthestUpStop() : s.currentFloor())
                    : (s.furthestDownStop() > 0 ? s.furthestDownStop() : s.currentFloor());
            etaDistance = Math.abs(s.currentFloor() - end) + Math.abs(end - targetFloor);
            directionPenalty = 8.0;
        }

        double loadFactor;
        double ratio = (s.capacity() <= 0) ? 1.0 : (double) s.load() / (double) s.capacity();
        if (ratio < 0.5) loadFactor = 1.0;
        else if (ratio < 0.8) loadFactor = 1.5;
        else loadFactor = 3.0;

        int stopPenalty = s.plannedStops() * 2;

        double cost = etaDistance * directionPenalty * loadFactor + stopPenalty + zonePenalty;
        return (int) Math.round(cost);
    }

    public boolean isOnTheWay(ElevatorSnapshot s, HallCall call) {
        int target = call.floor();
        Direction d = call.direction();

        if (s.direction() == Direction.UP && d == Direction.UP) {
            return s.currentFloor() <= target;
        }
        if (s.direction() == Direction.DOWN && d == Direction.DOWN) {
            return s.currentFloor() >= target;
        }
        return false;
    }
}
