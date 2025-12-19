package com.multielevator;

public class Passenger {
    private final int id;
    private final int startFloor;
    private final int targetFloor;
    private final Direction direction;

    public Passenger(int id, int startFloor, int targetFloor) {
        this.id = id;
        this.startFloor = startFloor;
        this.targetFloor = targetFloor;
        this.direction = (targetFloor > startFloor) ? Direction.UP : Direction.DOWN;
    }

    public int getStartFloor() { return startFloor; }
    public int getTargetFloor() { return targetFloor; }
    public Direction getDirection() { return direction; }
    public int getId() { return id; }

    @Override
    public String toString() {
        return String.format("Passenger-%d [%d -> %d]", id, startFloor, targetFloor);
    }
}