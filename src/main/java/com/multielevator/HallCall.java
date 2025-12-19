package com.multielevator;

import java.util.Objects;

public final class HallCall implements Comparable<HallCall> {
    private final int floor;
    private final Direction direction;

    public HallCall(int floor, Direction direction) {
        this.floor = floor;
        this.direction = Objects.requireNonNull(direction);
    }

    public int floor() {
        return floor;
    }

    public Direction direction() {
        return direction;
    }

    @Override
    public int compareTo(HallCall o) {
        if (o == null) return 1;
        int c = Integer.compare(this.floor, o.floor);
        if (c != 0) return c;
        return this.direction.compareTo(o.direction);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof HallCall)) return false;
        HallCall other = (HallCall) obj;
        return floor == other.floor && direction == other.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(floor, direction);
    }

    @Override
    public String toString() {
        return "HallCall{" + floor + "," + direction + "}";
    }
}
