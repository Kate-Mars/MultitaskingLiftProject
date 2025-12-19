package com.multielevator;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Main {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== SYSTEM BOOT ===\n");
        System.out.println("--- SIMULATION STARTED ---\n");

        boolean noGui = false;
        for (String a : args) {
            if (a != null && (a.equalsIgnoreCase("--nogui") || a.equalsIgnoreCase("-nogui"))) {
                noGui = true;
            }
        }

        // Dispatcher
        Dispatcher dispatcher = new Dispatcher(Config.FLOORS);
        Thread dispatcherThread = new Thread(dispatcher, "Dispatcher");
        dispatcherThread.start();

        // Elevators
        List<Elevator> elevators = new ArrayList<>();
        List<Thread> elevatorThreads = new ArrayList<>();
        for (int i = 1; i <= Config.ELEVATORS_COUNT; i++) {
            Elevator e = new Elevator(i, 1, Config.ELEVATOR_CAPACITY, dispatcher);
            elevators.add(e);
            dispatcher.registerElevator(e);
            Thread t = new Thread(e, "Elevator-" + i);
            elevatorThreads.add(t);
            t.start();
        }
        SimulationControl control = new SimulationControl(
                Config.PASSENGER_LIMIT,
                Config.REQUEST_INTERVAL_MIN,
                Config.REQUEST_INTERVAL_MAX
        );

        SimulationVisualizer visualizer = null;
        if (!noGui) {
            visualizer = new SimulationVisualizer(Config.FLOORS, elevators, dispatcher, control);
            visualizer.start();
        }

        Thread generator = startPassengerSimulation(dispatcher, control);
        generator.join();

        drainAndShutdown(dispatcher, dispatcherThread, elevators, elevatorThreads);

        if (visualizer != null) {
            visualizer.onSimulationFinished();
        }

        System.out.println("\n--- SIMULATION FINISHED ---");
    }

    private static Thread startPassengerSimulation(Dispatcher dispatcher, SimulationControl control) {
        Thread t = new Thread(() -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            while (!Thread.currentThread().isInterrupted() && control.shouldGenerateMore()) {
                int id = control.nextPassengerId();

                int from = rnd.nextInt(1, Config.FLOORS + 1);
                int to;
                do {
                    to = rnd.nextInt(1, Config.FLOORS + 1);
                } while (to == from);

                dispatcher.submitRequest(new Passenger(id, from, to));

                try {
                    int sleep = rnd.nextInt(control.getIntervalMinMs(), control.getIntervalMaxMs() + 1);
                    SimulationClock.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log("SYSTEM", "GENERATOR", "Generated " + control.getGeneratedCount() + " passengers. No more new requests.");
        }, "Passenger-Generator");

        t.start();
        return t;
    }

    private static void drainAndShutdown(
            Dispatcher dispatcher,
            Thread dispatcherThread,
            List<Elevator> elevators,
            List<Thread> elevatorThreads
    ) throws InterruptedException {

        long start = System.currentTimeMillis();
        while (true) {
            boolean allElevatorsIdle = true;
            for (Elevator e : elevators) {
                if (!e.isTrulyIdle()) {
                    allElevatorsIdle = false;
                    break;
                }
            }

            boolean dispatcherIdle = dispatcher.isIdle();
            boolean nobodyWaiting = dispatcher.getTotalWaiting() == 0;

            if (allElevatorsIdle && dispatcherIdle && nobodyWaiting) {
                break;
            }

            if (System.currentTimeMillis() - start > Config.DRAIN_TIMEOUT_MS) {
                log("SYSTEM", "SHUTDOWN", "Drain timeout reached (" + Config.DRAIN_TIMEOUT_MS + " ms). Forcing shutdown.");
                break;
            }

            Thread.sleep(200);
        }

        dispatcher.shutdown();
        dispatcherThread.interrupt();

        for (Elevator e : elevators) {
            e.shutdown();
        }
        for (Thread t : elevatorThreads) {
            t.interrupt();
        }

        dispatcherThread.join();
        for (Thread t : elevatorThreads) {
            t.join();
        }
    }

    private static void log(String actor, String tag, String message) {
        String time = LocalTime.now().format(TS);
        System.out.println("[" + time + "][" + actor + "][" + tag + "] " + message);
    }
}
