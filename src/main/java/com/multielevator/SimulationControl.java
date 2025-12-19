package com.multielevator;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared simulation settings that can be changed live from the GUI.
 */
public final class SimulationControl {

    private final AtomicInteger generated = new AtomicInteger(0);

    // How many passengers to generate in total.
    private volatile int passengerLimit;
    private volatile int intervalMinMs;
    private volatile int intervalMaxMs;

    public SimulationControl(int passengerLimit, int intervalMinMs, int intervalMaxMs) {
        this.passengerLimit = Math.max(0, passengerLimit);
        this.intervalMinMs = Math.max(0, intervalMinMs);
        this.intervalMaxMs = Math.max(this.intervalMinMs, intervalMaxMs);
    }

    public int getPassengerLimit() {
        return passengerLimit;
    }
    public void setPassengerLimit(int newLimit) {
        int g = generated.get();
        if (newLimit <= g) return;
        passengerLimit = newLimit;
    }

    public int getGeneratedCount() {
        return generated.get();
    }

    public int nextPassengerId() {
        return generated.incrementAndGet();
    }

    public boolean shouldGenerateMore() {
        return generated.get() < passengerLimit;
    }

    public int getIntervalMinMs() {
        return intervalMinMs;
    }

    public int getIntervalMaxMs() {
        return intervalMaxMs;
    }

    public void setIntervals(int minMs, int maxMs) {
        int mn = Math.max(0, minMs);
        int mx = Math.max(mn, maxMs);
        intervalMinMs = mn;
        intervalMaxMs = mx;
    }
}
