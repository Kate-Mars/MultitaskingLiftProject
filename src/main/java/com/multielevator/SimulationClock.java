package com.multielevator;

/**
 * Global simulation clock helper.
 *
 * Allows speeding up / slowing down the whole simulation (movement, doors, boarding, request generation)
 * without changing the core logic.
 */
public final class SimulationClock {

    private static final Object PAUSE_LOCK = new Object();

    private static volatile double speed = 1.0;
    private static volatile boolean paused = false;

    private SimulationClock() {}

    public static double getSpeed() {
        return speed;
    }

    public static void setSpeed(double newSpeed) {
        if (Double.isNaN(newSpeed) || Double.isInfinite(newSpeed)) return;
        double clamped = Math.max(0.1, Math.min(30.0, newSpeed));
        speed = clamped;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean p) {
        paused = p;
        if (!p) {
            synchronized (PAUSE_LOCK) {
                PAUSE_LOCK.notifyAll();
            }
        }
    }

    public static void togglePause() {
        setPaused(!paused);
    }

    public static void sleep(long baseMillis) throws InterruptedException {
        if (baseMillis <= 0) return;

        // Pause barrier
        while (paused) {
            synchronized (PAUSE_LOCK) {
                PAUSE_LOCK.wait(50);
            }
        }

        double s = speed;
        long scaled = (long) Math.max(1L, Math.round(baseMillis / s));
        Thread.sleep(scaled);
    }
}
