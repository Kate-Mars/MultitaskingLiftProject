package com.multielevator;

public final class ElevatorSnapshot {
    private final int id;
    private final int currentFloor;
    private final Direction direction;
    private final ElevatorStatus status;
    private final int load;
    private final int capacity;
    private final int plannedStops;

    // 0 означает "нет целей в эту сторону"
    private final int furthestUpStop;
    private final int furthestDownStop;

    public ElevatorSnapshot(int id,
                           int currentFloor,
                           Direction direction,
                           ElevatorStatus status,
                           int load,
                           int capacity,
                           int plannedStops,
                           int furthestUpStop,
                           int furthestDownStop) {
        this.id = id;
        this.currentFloor = currentFloor;
        this.direction = direction;
        this.status = status;
        this.load = load;
        this.capacity = capacity;
        this.plannedStops = plannedStops;
        this.furthestUpStop = furthestUpStop;
        this.furthestDownStop = furthestDownStop;
    }

    public int id() { return id; }
    public int currentFloor() { return currentFloor; }
    public Direction direction() { return direction; }
    public ElevatorStatus status() { return status; }
    public int load() { return load; }
    public int capacity() { return capacity; }
    public int plannedStops() { return plannedStops; }

    public int furthestUpStop() { return furthestUpStop; }
    public int furthestDownStop() { return furthestDownStop; }

    public boolean hasUpWork() { return furthestUpStop > 0; }
    public boolean hasDownWork() { return furthestDownStop > 0; }
}
