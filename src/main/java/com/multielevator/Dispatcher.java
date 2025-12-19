package com.multielevator;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Диспетчер: принимает запросы пассажиров, хранит очереди ожидания и распределяет
 * вызовы (этаж + направление) между лифтами.
 *
 * Реализован как отдельный поток (Runnable).
 */
public class Dispatcher implements Runnable {

    private final int totalFloors;
    private final List<Elevator> elevators = new ArrayList<>();
    private final BlockingQueue<Passenger> incoming = new LinkedBlockingQueue<>();
    private final BlockingQueue<DispatcherEvent> events = new LinkedBlockingQueue<>();

    private static final class DispatcherEvent {
        enum Type { PASSENGER_REQUEST, ELEVATOR_UPDATE, SHUTDOWN }
        final Type type;
        final Passenger passenger;
        final Elevator elevator;

        DispatcherEvent(Type type, Passenger passenger, Elevator elevator) {
            this.type = type;
            this.passenger = passenger;
            this.elevator = elevator;
        }
    }

    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Passenger>[] waitingUp;
    @SuppressWarnings("unchecked")
    private final ConcurrentLinkedQueue<Passenger>[] waitingDown;
    private final AtomicIntegerArray waitingUpCount;
    private final AtomicIntegerArray waitingDownCount;
    private final Set<HallCall> pendingCalls = new ConcurrentSkipListSet<>();
    private final ConcurrentHashMap<HallCall, Elevator> assignedElevator = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HallCall, Long> lastNoElevatorLogMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HallCall, Long> lastReassignMs = new ConcurrentHashMap<>();
    private static final long NO_ELEVATOR_LOG_COOLDOWN_MS = Config.NO_ELEVATOR_LOG_COOLDOWN_MS;
    private final CollectiveControlStrategy strategy = new CollectiveControlStrategy();

    private volatile boolean running = true;

    public Dispatcher(int totalFloors) {
        this.totalFloors = totalFloors;

        this.waitingUp = (ConcurrentLinkedQueue<Passenger>[]) new ConcurrentLinkedQueue[totalFloors + 1];
        this.waitingDown = (ConcurrentLinkedQueue<Passenger>[]) new ConcurrentLinkedQueue[totalFloors + 1];
        for (int f = 1; f <= totalFloors; f++) {
            waitingUp[f] = new ConcurrentLinkedQueue<>();
            waitingDown[f] = new ConcurrentLinkedQueue<>();
        }
        this.waitingUpCount = new AtomicIntegerArray(totalFloors + 1);
        this.waitingDownCount = new AtomicIntegerArray(totalFloors + 1);
    }
    public int getTotalFloors() {
        return totalFloors;
    }
    public List<Passenger> peekWaitingPassengers(int floor, Direction dir, int limit) {
        if (limit <= 0) return List.of();
        if (floor < 1 || floor > totalFloors) return List.of();

        ConcurrentLinkedQueue<Passenger> q = queueFor(floor, dir);
        if (q == null || q.isEmpty()) return List.of();

        ArrayList<Passenger> out = new ArrayList<>(Math.min(limit, 32));
        int i = 0;
        for (Passenger p : q) {
            if (p == null) continue;
            out.add(p);
            i++;
            if (i >= limit) break;
        }
        return out;
    }

    public void registerElevator(Elevator e) {
        elevators.add(Objects.requireNonNull(e));
    }
    public void notifyElevatorUpdate(Elevator e) {
        if (e == null) return;
        events.offer(new DispatcherEvent(DispatcherEvent.Type.ELEVATOR_UPDATE, null, e));
    }
    public void submitRequest(Passenger p) {
        log("REQUEST", p + " waiting at floor " + p.getStartFloor() + " dir=" + p.getDirection());
        events.offer(new DispatcherEvent(DispatcherEvent.Type.PASSENGER_REQUEST, p, null));
        incoming.offer(p);
    }
    public List<Passenger> boardPassengers(int floor, Direction dir, int spaceAvailable) {
        if (spaceAvailable <= 0) return List.of();
        if (floor < 1 || floor > totalFloors) return List.of();

        ConcurrentLinkedQueue<Passenger> q = queueFor(floor, dir);
        AtomicIntegerArray c = countFor(dir);

        List<Passenger> result = new ArrayList<>();
        while (spaceAvailable > 0) {
            Passenger p = q.poll();
            if (p == null) break;
            c.decrementAndGet(floor);
            result.add(p);
            spaceAvailable--;
        }
        if (getWaitingCount(floor, dir) == 0) {
            HallCall key = new HallCall(floor, dir);
            pendingCalls.remove(key);
            Elevator assigned = assignedElevator.remove(key);
            lastNoElevatorLogMs.remove(key);
            if (assigned != null) {
                assigned.cancelHallCall(floor, dir);
            }
        }
        return result;
    }

    public int getWaitingCount(int floor, Direction dir) {
        if (floor < 1 || floor > totalFloors) return 0;
        return countFor(dir).get(floor);
    }

    public boolean hasWaiting(int floor, Direction dir) {
        return getWaitingCount(floor, dir) > 0;
    }

    public void shutdown() {
        running = false;
    }
    public boolean claimHallCallAtFloor(int floor, Direction dir, Elevator claimer) {
        if (claimer == null) return false;
        if (floor < 1 || floor > totalFloors) return false;
        if (!hasWaiting(floor, dir)) return false;

        HallCall call = new HallCall(floor, dir);
        pendingCalls.add(call);

        // мы здесь намеренно "крадём" назначение, потому что лифт уже НА ЭТАЖЕ.
        Elevator prev = assignedElevator.put(call, claimer);
        if (prev != null && prev != claimer) {
            prev.cancelHallCall(floor, dir);
            lastReassignMs.put(call, System.currentTimeMillis());
        }
        lastNoElevatorLogMs.remove(call);
        return true;
    }
    public Elevator getAssignedElevator(int floor, Direction dir) {
        if (floor < 1 || floor > totalFloors) return null;
        return assignedElevator.get(new HallCall(floor, dir));
    }
    public boolean isIdle() {
        return getTotalWaiting() == 0 && pendingCalls.isEmpty() && assignedElevator.isEmpty() && events.isEmpty();
    }

    /** Общее число ожидающих пассажиров по всем этажам и направлениям. */
    public int getTotalWaiting() {
        int sum = 0;
        for (int f = 1; f <= totalFloors; f++) {
            sum += waitingUpCount.get(f);
            sum += waitingDownCount.get(f);
        }
        return sum;
    }

    @Override
    public void run() {
        log("SYSTEM", "Dispatcher started");

        while (running) {
            try {
                DispatcherEvent ev = events.poll(1, TimeUnit.SECONDS);

                if (ev != null) {
                    handleEvent(ev);

                    for (int i = 0; i < Config.DISPATCHER_EVENT_BATCH; i++) {
                        DispatcherEvent next = events.poll();
                        if (next == null) break;
                        handleEvent(next);
                    }

                    dispatchPendingCalls();
                } else {
                    dispatchPendingCalls();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log("SYSTEM", "Dispatcher stopped");
    }

    private void handleEvent(DispatcherEvent ev) {
        if (ev == null) return;

        if (ev.type == DispatcherEvent.Type.SHUTDOWN) {
            running = false;
            return;
        }

        if (ev.type == DispatcherEvent.Type.PASSENGER_REQUEST && ev.passenger != null) {
            enqueueWaiting(ev.passenger);
            return;
        }
    }


    private void enqueueWaiting(Passenger p) {
        int floor = p.getStartFloor();
        Direction dir = p.getDirection();

        if (dir == Direction.UP) {
            waitingUp[floor].offer(p);
            waitingUpCount.incrementAndGet(floor);
        } else if (dir == Direction.DOWN) {
            waitingDown[floor].offer(p);
            waitingDownCount.incrementAndGet(floor);
        } else {
            // IDLE у пассажира быть не должен, но на всякий
            waitingUp[floor].offer(p);
            waitingUpCount.incrementAndGet(floor);
        }

        pendingCalls.add(new HallCall(floor, dir));
    }

    private void dispatchPendingCalls() {
        List<HallCall> snapshot = new ArrayList<>(pendingCalls);

        for (HallCall call : snapshot) {
            if (call == null) continue;
            if (!hasWaiting(call.floor(), call.direction())) {
                pendingCalls.remove(call);
                assignedElevator.remove(call);
                lastNoElevatorLogMs.remove(call);
                continue;
            }
            Elevator assigned = assignedElevator.get(call);
            if (assigned != null) {
                if (assigned.canContinueServingAssignedCall(call)) {
                    if (shouldReassign(call, assigned)) {
                        assignedElevator.remove(call);
                        assigned.cancelHallCall(call.floor(), call.direction());
                        lastReassignMs.put(call, System.currentTimeMillis());
                    } else {
                        continue;
                    }
                } else {
                    assignedElevator.remove(call);
                    assigned.cancelHallCall(call.floor(), call.direction());
                }
            }

            AssignResult pick = findBestElevator(call);
            if (pick.elevator == null) {
                long now = System.currentTimeMillis();
                Long last = lastNoElevatorLogMs.get(call);
                if (last == null || (now - last) >= NO_ELEVATOR_LOG_COOLDOWN_MS) {
                    lastNoElevatorLogMs.put(call, now);
                    log("ASSIGN", call + " - NO_ELEVATOR " + pick.reasonSummary());
                }
                continue;
            }

            ElevatorSnapshot sBefore = pick.elevator.snapshot();

            boolean acceptedNow = (pick.mode == PickMode.RESERVED_REVERSE_SOON)
                    ? pick.elevator.tryReserveHallCall(call)
                    : pick.elevator.tryAddHallCall(call.floor(), call.direction());
            if (!acceptedNow) {
                log("ASSIGN", call + " -> Elevator-" + pick.elevator.getId()
                        + " (at " + sBefore.currentFloor()
                        + ", going " + sBefore.direction()
                        + ", load=" + sBefore.load() + "/" + sBefore.capacity()
                        + ", stops=" + sBefore.plannedStops()
                        + ") - REJECTED: " + HallCallRejectReason.FULL_CAPACITY);
                continue;
            }
            assignedElevator.put(call, pick.elevator);
            lastNoElevatorLogMs.remove(call);

            ElevatorSnapshot s = pick.elevator.snapshot();
            log("ASSIGN", call + " -> Elevator-" + s.id()
                    + " (at " + s.currentFloor()
                    + ", going " + s.direction()
                    + ", load=" + s.load() + "/" + s.capacity()
                    + ", stops=" + s.plannedStops()
                    + ", pick=" + pick.mode + ")");
        }
    }

    private AssignResult findBestElevator(HallCall call) {
        Elevator best = null;
        int minCost = Integer.MAX_VALUE;

        int full = 0;
        int wrongDir = 0;
        int outOfRoute = 0;
        int stopLimit = 0;
        int doorsBusy = 0;

        for (Elevator e : elevators) {
            HallCallRejectReason reason = e.canAcceptHallCallReason(call);
            if (reason == HallCallRejectReason.ACCEPTED_RESERVED) {
                continue;
            }
            if (reason != HallCallRejectReason.ACCEPTED) {
                switch (reason) {
                    case FULL_CAPACITY -> full++;
                    case WRONG_DIRECTION -> wrongDir++;
                    case OUT_OF_ROUTE -> outOfRoute++;
                    case TOO_MANY_STOPS -> stopLimit++;
                    case DOORS_BUSY -> doorsBusy++;
                    default -> { }
                }
                continue;
            }

            ElevatorSnapshot s = e.snapshot();
            int cost = strategy.calculateCost(s, call);

            int assigned = assignedCountFor(e);
            cost += assigned * 6;
            if (strategy.isOnTheWay(s, call)) {
                cost -= 3;
            }

            if (cost < minCost) {
                minCost = cost;
                best = e;
            } else if (cost == minCost && best != null) {
                int aBest = assignedCountFor(best);
                if (assigned < aBest) {
                    best = e;
                } else if (assigned == aBest) {
                    ElevatorSnapshot sb = best.snapshot();
                    if (s.plannedStops() < sb.plannedStops()) {
                        best = e;
                    } else if (s.plannedStops() == sb.plannedStops() && s.load() < sb.load()) {
                        best = e;
                    }
                }
            }
        }

        if (best != null) {
            return new AssignResult(best, PickMode.NORMAL, full, wrongDir, outOfRoute, stopLimit, doorsBusy);
        }

        Elevator bestReserved = null;
        int minReservedCost = Integer.MAX_VALUE;
        for (Elevator e : elevators) {
            HallCallRejectReason reason = e.canAcceptHallCallReason(call);
            if (reason != HallCallRejectReason.ACCEPTED_RESERVED) continue;

            ElevatorSnapshot s = e.snapshot();
            if (s.load() >= s.capacity()) continue;
            if (s.plannedStops() >= Config.MAX_PLANNED_STOPS) continue;
            if (s.status() == ElevatorStatus.DOORS_OPEN) { doorsBusy++; continue; }

            int cost = strategy.calculateCost(s, call) + 25 + assignedCountFor(e) * 6;
            if (cost < minReservedCost) {
                minReservedCost = cost;
                bestReserved = e;
            }
        }
        if (bestReserved != null) {
            return new AssignResult(bestReserved, PickMode.RESERVED_REVERSE_SOON, full, wrongDir, outOfRoute, stopLimit, doorsBusy);
        }


        Elevator bestReserve = null;
        int minReserveCost = Integer.MAX_VALUE;
        for (Elevator e : elevators) {
            ElevatorSnapshot s = e.snapshot();
            if (s.load() != 0) continue;
            if (s.plannedStops() != 0) continue;
            if (s.status() == ElevatorStatus.DOORS_OPEN) continue;

            int distance = Math.abs(s.currentFloor() - call.floor());
            int cost = distance * 6 + assignedCountFor(e) * 6; // резерв считаем хуже обычного, но лучше чем бесконечное ожидание
            if (cost < minReserveCost) {
                minReserveCost = cost;
                bestReserve = e;
            }
        }
        if (bestReserve != null) {
            return new AssignResult(bestReserve, PickMode.RESERVE, full, wrongDir, outOfRoute, stopLimit, doorsBusy);
        }

        return new AssignResult(null, PickMode.NONE, full, wrongDir, outOfRoute, stopLimit, doorsBusy);
    }

    private enum PickMode { NORMAL, DOORS_BUSY, RESERVED_REVERSE_SOON, RESERVE, NONE }

    private static final class AssignResult {
        final Elevator elevator;
        final PickMode mode;
        final int full;
        final int wrongDir;
        final int outOfRoute;
        final int stopLimit;
        final int doorsBusy;

        AssignResult(Elevator elevator, PickMode mode, int full, int wrongDir, int outOfRoute, int stopLimit, int doorsBusy) {
            this.elevator = elevator;
            this.mode = mode;
            this.full = full;
            this.wrongDir = wrongDir;
            this.outOfRoute = outOfRoute;
            this.stopLimit = stopLimit;
            this.doorsBusy = doorsBusy;
        }

        String reasonSummary() {
            return "(full=" + full + ", wrongDir=" + wrongDir + ", outOfRoute=" + outOfRoute
                    + ", stopLimit=" + stopLimit + ", doorsBusy=" + doorsBusy + ")";
        }
    }

    private ConcurrentLinkedQueue<Passenger> queueFor(int floor, Direction dir) {
        return (dir == Direction.DOWN) ? waitingDown[floor] : waitingUp[floor];
    }

    private AtomicIntegerArray countFor(Direction dir) {
        return (dir == Direction.DOWN) ? waitingDownCount : waitingUpCount;
    }

    private int assignedCountFor(Elevator e) {
        if (e == null) return 0;
        int cnt = 0;
        for (Elevator v : assignedElevator.values()) {
            if (v == e) cnt++;
        }
        return cnt;
    }

    private boolean shouldReassign(HallCall call, Elevator currentlyAssigned) {
        if (call == null || currentlyAssigned == null) return false;

        long now = System.currentTimeMillis();
        Long last = lastReassignMs.get(call);
        if (last != null && (now - last) < Config.CALL_REASSIGN_COOLDOWN_MS) {
            return false;
        }

        if (currentlyAssigned.isCommittedToHallCall(call)) return false;

        ElevatorSnapshot sa = currentlyAssigned.snapshot();
        if (Math.abs(sa.currentFloor() - call.floor()) <= 1) return false;

        AssignResult best = findBestElevator(call);
        if (best.elevator == null) return false;
        if (best.elevator == currentlyAssigned) return false;

        ElevatorSnapshot sb = best.elevator.snapshot();
        boolean bestOk = (sb.direction() == Direction.IDLE) || strategy.isOnTheWay(sb, call);
        if (!bestOk) return false;

        int costAssigned = effectiveCost(sa, call, assignedCountFor(currentlyAssigned));
        int costBest = effectiveCost(sb, call, assignedCountFor(best.elevator));

        return (costAssigned - costBest) >= Config.CALL_REASSIGN_MIN_IMPROVEMENT;
    }

    private int effectiveCost(ElevatorSnapshot s, HallCall call, int assignedCount) {
        int cost = strategy.calculateCost(s, call);
        cost += assignedCount * 6;
        if (strategy.isOnTheWay(s, call)) cost -= 3;
        return cost;
    }

    private void log(String tag, String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s][Dispatcher][%s] %s%n", time, tag, msg);
    }
}
