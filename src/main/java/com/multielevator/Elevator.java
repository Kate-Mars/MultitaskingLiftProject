package com.multielevator;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Elevator implements Runnable {

    private enum ElevatorState {
        IDLE,
        MOVING_UP,
        MOVING_DOWN,
        DOORS_OPEN,
        LOAD_FULL
    }

    private final int id;
    private final int maxCapacity;
    private final Dispatcher dispatcher;

    private volatile int currentFloor;
    private volatile double visualFloorPos;
    private volatile Direction currentDirection;
    private volatile ElevatorStatus status;

    // защищено lock
    private final List<Passenger> passengersInside = new ArrayList<>();

    // защищено lock
    private final NavigableSet<Integer> stopsUp = new TreeSet<>();
    private final NavigableSet<Integer> stopsDown = new TreeSet<>();

    // защищено lock
    private final NavigableSet<Integer> internalStopsUp = new TreeSet<>();
    private final NavigableSet<Integer> internalStopsDown = new TreeSet<>();

    // защищено lock
    private final Map<Integer, EnumSet<Direction>> hallCallsByFloor = new HashMap<>();

    // защищено lock
    private final Set<HallCall> reservedHallCalls = new HashSet<>();

    private final Queue<HallCall> pendingCalls = new ConcurrentLinkedQueue<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition newTaskCondition = lock.newCondition();

    private volatile boolean running = true;

    public Elevator(int id, int startFloor, int maxCapacity, Dispatcher dispatcher) {
        this.id = id;
        this.currentFloor = startFloor;
        this.visualFloorPos = startFloor;
        this.maxCapacity = maxCapacity;
        this.dispatcher = dispatcher;
        this.currentDirection = Direction.IDLE;
        this.status = ElevatorStatus.IDLE;
    }

    public double getVisualFloorPos() {
        return visualFloorPos;
    }

    public List<Passenger> passengersInsideSnapshot(int limit) {
        lock.lock();
        try {
            if (passengersInside.isEmpty()) return List.of();
            int n = (limit <= 0) ? passengersInside.size() : Math.min(limit, passengersInside.size());
            return new ArrayList<>(passengersInside.subList(0, n));
        } finally {
            lock.unlock();
        }
    }

    public void addInternalStop(int floor) {
        lock.lock();
        try {
            addInternalStopUnlocked(floor);
            newTaskCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void addInternalStopUnlocked(int floor) {
        if (floor >= currentFloor) internalStopsUp.add(floor);
        else internalStopsDown.add(floor);
        addStopUnlocked(floor);
    }

    public void addHallCall(int floor, Direction dir) {
        tryAddHallCall(floor, dir);
    }

    public boolean tryAddHallCall(int floor, Direction dir) {
        if (dir == Direction.IDLE) return false;

        if (getLoadSafe() >= maxCapacity) {
            status = ElevatorStatus.LOAD_FULL;
            return false;
        }

        if (floor == currentFloor && status == ElevatorStatus.DOORS_OPEN) {
            lock.lock();
            try {
                hallCallsByFloor
                        .computeIfAbsent(floor, f -> EnumSet.noneOf(Direction.class))
                        .add(dir);
                newTaskCondition.signalAll();
            } finally {
                lock.unlock();
            }
            return true;
        }

        lock.lock();
        try {
            if (currentDirection == Direction.UP && floor < currentFloor) {
                return false;
            }
            if (currentDirection == Direction.DOWN && floor > currentFloor) {
                return false;
            }

            if (currentDirection != Direction.IDLE && dir != currentDirection) {
                if (passengersInside.isEmpty() && plannedStopsUnlocked() <= 1 && status != ElevatorStatus.DOORS_OPEN) {
                    reservedHallCalls.add(new HallCall(floor, dir));
                    newTaskCondition.signalAll();
                    return true;
                }
                return false;
            }

            EnumSet<Direction> set = hallCallsByFloor.computeIfAbsent(floor, f -> EnumSet.noneOf(Direction.class));
            set.add(dir);

            addStopUnlocked(floor);

            newTaskCondition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private int plannedStopsUnlocked() {
        return stopsUp.size() + stopsDown.size() + internalStopsUp.size() + internalStopsDown.size();
    }

    public boolean tryReserveHallCall(HallCall call) {
        if (call == null) return false;
        if (call.direction() == Direction.IDLE) return false;

        lock.lock();
        try {
            if (passengersInside.size() >= maxCapacity) {
                status = ElevatorStatus.LOAD_FULL;
                return false;
            }
            if ((stopsUp.size() + stopsDown.size()) >= Config.MAX_PLANNED_STOPS) {
                return false;
            }

            reservedHallCalls.add(call);
            newTaskCondition.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean canAcceptHallCall(HallCall call) {
        return canAcceptHallCallReason(call) == HallCallRejectReason.ACCEPTED;
    }

    public boolean canContinueServingAssignedCall(HallCall call) {
        if (call == null) return false;

        // If the car already committed this call (planned or reserved), keep the assignment.
        if (isCommittedToHallCall(call)) return true;

        ElevatorSnapshot s = snapshot();

        // If we are already at the floor (doors open), don't thrash assignments; the car will decide boarding itself.
        if (s.status() == ElevatorStatus.DOORS_OPEN && s.currentFloor() == call.floor()) {
            return true;
        }

        HallCallRejectReason reason = canAcceptHallCallReason(call);
        if (reason == HallCallRejectReason.ACCEPTED || reason == HallCallRejectReason.ACCEPTED_RESERVED) return true;
        // DOORS_BUSY means the car is temporarily unavailable; keep assignment until it closes.
        return reason == HallCallRejectReason.DOORS_BUSY;
    }

    public HallCallRejectReason canAcceptHallCallReason(HallCall call) {
        if (call == null) return HallCallRejectReason.OUT_OF_ROUTE;

        lock.lock();
        try {
            int load = passengersInside.size();
            if (load >= maxCapacity) return HallCallRejectReason.FULL_CAPACITY;
            if (stopsUp.size() + stopsDown.size() >= Config.MAX_PLANNED_STOPS) return HallCallRejectReason.TOO_MANY_STOPS;

            int furthestUp = 0;
            int furthestDown = 0;
            for (Integer f : stopsUp) {
                if (f == null) continue;
                if (f > currentFloor) furthestUp = Math.max(furthestUp, f);
                if (f < currentFloor) furthestDown = (furthestDown == 0) ? f : Math.min(furthestDown, f);
            }
            for (Integer f : stopsDown) {
                if (f == null) continue;
                if (f > currentFloor) furthestUp = Math.max(furthestUp, f);
                if (f < currentFloor) furthestDown = (furthestDown == 0) ? f : Math.min(furthestDown, f);
            }
            for (Passenger p : passengersInside) {
                int tf = p.getTargetFloor();
                if (tf > currentFloor) furthestUp = Math.max(furthestUp, tf);
                if (tf < currentFloor) furthestDown = (furthestDown == 0) ? tf : Math.min(furthestDown, tf);
            }

            // If doors are open on this floor – accept ONLY the current service direction.
            // Real elevators don't let new hall calls flip direction while doors are open;
            // the car either continues in its current direction or, if truly idle, can take any.
            // (Direction is updated right after stop removal, before opening doors.)
            if (status == ElevatorStatus.DOORS_OPEN) {
                if (currentFloor != call.floor()) return HallCallRejectReason.DOORS_BUSY;
                if (currentDirection == Direction.IDLE || currentDirection == call.direction()) {
                    return HallCallRejectReason.ACCEPTED;
                }
                return HallCallRejectReason.WRONG_DIRECTION;
            }

            if (currentDirection == Direction.IDLE) return HallCallRejectReason.ACCEPTED;

            // "On the way" in the same direction within the route envelope.
            if (call.direction() == currentDirection) {
                if (currentDirection == Direction.UP) {
                    int bound = (furthestUp > 0) ? furthestUp : currentFloor;
                    boolean onWay = (call.floor() >= currentFloor) && (call.floor() <= bound);
                    return onWay ? HallCallRejectReason.ACCEPTED : HallCallRejectReason.OUT_OF_ROUTE;
                } else {
                    int bound = (furthestDown > 0) ? furthestDown : currentFloor;
                    boolean onWay = (call.floor() <= currentFloor) && (call.floor() >= bound);
                    return onWay ? HallCallRejectReason.ACCEPTED : HallCallRejectReason.OUT_OF_ROUTE;
                }
            }

            // Opposite direction: only reserve if empty and very close to reversing, and the call is ahead toward the reversal point.
            if (load != 0) return HallCallRejectReason.WRONG_DIRECTION;

            int distToReverse;
            boolean onReversePath;
            if (currentDirection == Direction.UP) {
                int top = (furthestUp > 0) ? furthestUp : currentFloor;
                distToReverse = Math.max(0, top - currentFloor);
                onReversePath = (call.floor() >= currentFloor) && (call.floor() <= top);
            } else {
                int bottom = (furthestDown > 0) ? furthestDown : currentFloor;
                distToReverse = Math.max(0, currentFloor - bottom);
                onReversePath = (call.floor() <= currentFloor) && (call.floor() >= bottom);
            }

            boolean reserveOk = onReversePath
                    && (distToReverse <= Config.RESERVE_REVERSE_SOON_FLOORS)
                    && (stopsUp.size() + stopsDown.size() <= 1);

            return reserveOk ? HallCallRejectReason.ACCEPTED_RESERVED : HallCallRejectReason.WRONG_DIRECTION;
        } finally {
            lock.unlock();
        }
    }

    public void cancelHallCall(int floor, Direction dir) {
        if (dir == Direction.IDLE) return;

        lock.lock();
        try {
            reservedHallCalls.remove(new HallCall(floor, dir));

            EnumSet<Direction> set = hallCallsByFloor.get(floor);
            if (set != null) {
                set.remove(dir);
                if (set.isEmpty()) {
                    hallCallsByFloor.remove(floor);
                }
            }

            if (!hallCallsByFloor.containsKey(floor) && !hasInternalNeedForFloorUnlocked(floor)) {
                stopsUp.remove(floor);
                stopsDown.remove(floor);
            }

            newTaskCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public ElevatorSnapshot snapshot() {
        lock.lock();
        try {
            int planned = stopsUp.size() + stopsDown.size();
            int load = passengersInside.size();

            int furthestUp = 0;
            int furthestDown = 0;

            // Estimate route bounds: farthest requested destination above/below current floor.
            for (Integer f : stopsUp) {
                if (f == null) continue;
                if (f > currentFloor) furthestUp = Math.max(furthestUp, f);
                if (f < currentFloor) furthestDown = (furthestDown == 0) ? f : Math.min(furthestDown, f);
            }
            for (Integer f : stopsDown) {
                if (f == null) continue;
                if (f > currentFloor) furthestUp = Math.max(furthestUp, f);
                if (f < currentFloor) furthestDown = (furthestDown == 0) ? f : Math.min(furthestDown, f);
            }
            // Include onboard passenger destinations (so dispatcher knows the real travel envelope)
            for (Passenger p : passengersInside) {
                int tf = p.getTargetFloor();
                if (tf > currentFloor) furthestUp = Math.max(furthestUp, tf);
                if (tf < currentFloor) furthestDown = (furthestDown == 0) ? tf : Math.min(furthestDown, tf);
            }

            return new ElevatorSnapshot(id, currentFloor, currentDirection, status, load, maxCapacity, planned, furthestUp, furthestDown);
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try {
            running = false;
            newTaskCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isTrulyIdle() {
        ElevatorSnapshot s = snapshot();
        return s.load() == 0 && s.plannedStops() == 0 && s.direction() == Direction.IDLE;
    }

    public boolean isCommittedToHallCall(HallCall call) {
        if (call == null) return false;

        lock.lock();
        try {
            if (reservedHallCalls.contains(call)) return true;
            EnumSet<Direction> dirs = hallCallsByFloor.get(call.floor());
            return dirs != null && dirs.contains(call.direction());
        } finally {
            lock.unlock();
        }
    }


    @Override
    public void run() {
        log("SYSTEM", "Started at floor " + currentFloor);

        while (running) {
            Integer target;

            lock.lock();
            try {
                while (stopsUp.isEmpty() && stopsDown.isEmpty() && passengersInside.isEmpty()) {
                    if (!reservedHallCalls.isEmpty()) {
                        activateReservedCallsUnlocked();
                        if (!stopsUp.isEmpty() || !stopsDown.isEmpty()) {
                            break;
                        }
                    }
                    currentDirection = Direction.IDLE;
                    status = ElevatorStatus.IDLE;
                    dispatcher.notifyElevatorUpdate(this);
                    newTaskCondition.await();
                }

                updateDirectionUnlocked();
                target = chooseNextTargetUnlocked();

                if (target == null) {
                    updateDirectionUnlocked();
                    continue;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                lock.unlock();
            }
            int arrivedFloor = moveTo(target);

            lock.lock();
            try {
                stopsUp.remove(arrivedFloor);
                stopsDown.remove(arrivedFloor);
                internalStopsUp.remove(arrivedFloor);
                internalStopsDown.remove(arrivedFloor);

                updateDirectionUnlocked();
            } finally {
                lock.unlock();
            }

            operateDoorsAndExchangePassengers(arrivedFloor);

            flushPendingCallsIfPossible();
        }

        log("SYSTEM", "Stopped");
    }

    private void flushPendingCallsIfPossible() {
        if (pendingCalls.isEmpty()) return;
        if (getLoadSafe() >= maxCapacity) {
            status = ElevatorStatus.LOAD_FULL;
            return;
        }

        for (int i = 0; i < 3; i++) {
            HallCall c = pendingCalls.poll();
            if (c == null) break;
            if (canAcceptHallCall(c)) {
                addHallCall(c.floor(), c.direction());
            }
        }
    }

    private void activateReservedCallsUnlocked() {
        if (reservedHallCalls.isEmpty()) return;
        if (!passengersInside.isEmpty()) return;

        var it = reservedHallCalls.iterator();
        while (it.hasNext()) {
            HallCall c = it.next();
            if (c == null) { it.remove(); continue; }
            if (!dispatcher.hasWaiting(c.floor(), c.direction())) {
                it.remove();
                continue;
            }

            hallCallsByFloor
                    .computeIfAbsent(c.floor(), f -> EnumSet.noneOf(Direction.class))
                    .add(c.direction());
            addStopUnlocked(c.floor());
            it.remove();
        }
    }

    private void addStopUnlocked(int floor) {
        if (floor >= currentFloor) {
            stopsUp.add(floor);
        } else {
            stopsDown.add(floor);
        }
    }

    private void updateDirectionUnlocked() {
        if (currentDirection == Direction.IDLE) {
            Integer up = stopsUp.ceiling(currentFloor);
            if (up == null && !stopsUp.isEmpty()) up = stopsUp.first();

            Integer down = stopsDown.floor(currentFloor);
            if (down == null && !stopsDown.isEmpty()) down = stopsDown.last();

            if (up == null && down == null) {
                currentDirection = Direction.IDLE;
                return;
            }
            if (up == null) {
                currentDirection = Direction.DOWN;
                return;
            }
            if (down == null) {
                currentDirection = Direction.UP;
                return;
            }

            int distUp = Math.abs(up - currentFloor);
            int distDown = Math.abs(currentFloor - down);
            currentDirection = (distUp <= distDown) ? Direction.UP : Direction.DOWN;
            return;
        }

        if (currentDirection == Direction.UP && stopsUp.isEmpty() && !stopsDown.isEmpty()) {
            currentDirection = Direction.DOWN;
        } else if (currentDirection == Direction.DOWN && stopsDown.isEmpty() && !stopsUp.isEmpty()) {
            currentDirection = Direction.UP;
        }
    }

    private Integer chooseNextTargetUnlocked() {
        if (currentDirection == Direction.UP) {
            Integer t = internalStopsUp.ceiling(currentFloor);
            if (t == null && !internalStopsUp.isEmpty()) t = internalStopsUp.first();
            if (t != null) return t;

            Integer h = stopsUp.ceiling(currentFloor);
            if (h == null && !stopsUp.isEmpty()) h = stopsUp.first();
            return h;
        }

        if (currentDirection == Direction.DOWN) {
            Integer t = internalStopsDown.floor(currentFloor);
            if (t == null && !internalStopsDown.isEmpty()) t = internalStopsDown.last();
            if (t != null) return t;

            Integer h = stopsDown.floor(currentFloor);
            if (h == null && !stopsDown.isEmpty()) h = stopsDown.last();
            return h;
        }

        // IDLE: попробуем выбрать ближайшую внутреннюю цель, иначе ближайший стоп
        Integer iu = internalStopsUp.ceiling(currentFloor);
        if (iu == null && !internalStopsUp.isEmpty()) iu = internalStopsUp.first();

        Integer id = internalStopsDown.floor(currentFloor);
        if (id == null && !internalStopsDown.isEmpty()) id = internalStopsDown.last();

        if (iu != null || id != null) {
            if (iu == null) return id;
            if (id == null) return iu;
            int du = Math.abs(iu - currentFloor);
            int dd = Math.abs(currentFloor - id);
            return (du <= dd) ? iu : id;
        }

        // Нет внутренних целей: берём общий стоп
        Integer up = stopsUp.ceiling(currentFloor);
        if (up == null && !stopsUp.isEmpty()) up = stopsUp.first();

        Integer down = stopsDown.floor(currentFloor);
        if (down == null && !stopsDown.isEmpty()) down = stopsDown.last();

        if (up == null) return down;
        if (down == null) return up;

        int distUp = Math.abs(up - currentFloor);
        int distDown = Math.abs(currentFloor - down);
        return (distUp <= distDown) ? up : down;
    }

    private int moveTo(int target) {
        if (target == currentFloor) return currentFloor;

        status = ElevatorStatus.MOVING;

        try {
            int step = (target > currentFloor) ? 1 : -1;
            // направление движения к цели
            currentDirection = (step > 0) ? Direction.UP : Direction.DOWN;

            int floorsToTravel = Math.abs(target - currentFloor);
            int tickMs = 40;
            int substeps = Math.max(1, Config.TIME_MOVE_ONE_FLOOR / tickMs);
            int sleepMs = Math.max(1, Config.TIME_MOVE_ONE_FLOOR / substeps);

            for (int i = 0; i < floorsToTravel; i++) {
                for (int s = 0; s < substeps; s++) {
                    SimulationClock.sleep(sleepMs);
                    visualFloorPos += step * (1.0 / substeps);
                }

                // логический переход на следующий этаж
                int reached;
                lock.lock();
                try {
                    currentFloor += step;
                    reached = currentFloor;
                } finally {
                    lock.unlock();
                }
                visualFloorPos = reached;

                if (shouldStopAtFloor(reached)) {
                    return reached;
                }

                if (shouldStopForWaitingAtFloor(reached, currentDirection)) {
                    dispatcher.claimHallCallAtFloor(reached, currentDirection, this);
                    return reached;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return currentFloor;
    }

    private boolean shouldStopAtFloor(int floor) {
        lock.lock();
        try {
            return internalStopsUp.contains(floor)
                    || internalStopsDown.contains(floor)
                    || stopsUp.contains(floor)
                    || stopsDown.contains(floor);
        } finally {
            lock.unlock();
        }
    }

    private boolean shouldStopForWaitingAtFloor(int floor, Direction dir) {
        if (!Config.ENROUTE_PICKUP_ENABLED) return false;
        if (dir != Direction.UP && dir != Direction.DOWN) return false;
        if (!dispatcher.hasWaiting(floor, dir)) return false;

        // Не делаем лишних остановок, если лифт полон.
        if (getLoadSafe() >= maxCapacity) return false;

        // Не нарушаем лимит остановок.
        ElevatorSnapshot s = snapshot();
        if (s.plannedStops() >= Config.MAX_PLANNED_STOPS) return false;

        // Если вызов уже назначен другому лифту, не перехватываем "на всякий случай".
        // Перехват разрешаем только если назначенный лифт заметно дальше или движется "не туда".
        Elevator assigned = dispatcher.getAssignedElevator(floor, dir);
        if (assigned == null || assigned == this) return true;

        ElevatorSnapshot as = assigned.snapshot();
        int dist = Math.abs(as.currentFloor() - floor);

        boolean movingAway;
        if (dir == Direction.UP) {
            // чтобы забрать "UP" на этаже floor, назначенный лифт должен приближаться снизу.
            movingAway = (as.direction() == Direction.DOWN && as.currentFloor() < floor)
                    || (as.direction() == Direction.UP && as.currentFloor() > floor);
        } else {
            // "DOWN": назначенный лифт должен приближаться сверху.
            movingAway = (as.direction() == Direction.UP && as.currentFloor() > floor)
                    || (as.direction() == Direction.DOWN && as.currentFloor() < floor);
        }

        if (movingAway) return true;
        return dist >= Config.ENROUTE_STEAL_MIN_ASSIGNED_DISTANCE;
    }

    private void operateDoorsAndExchangePassengers(int floor) {
        // Защита от «двоения» прибытия: если уже на этаже и двери открыты — не логируем повторно.
        if (floor == currentFloor && status == ElevatorStatus.DOORS_OPEN) {
            return;
        }

        log("ARRIVED", "Floor " + floor);

        try {
            status = ElevatorStatus.DOORS_OPEN;
            log("DOOR", "OPEN");
            SimulationClock.sleep(Config.TIME_DOORS);

            int disembarked;
            lock.lock();
            try {
                disembarked = unloadPassengersUnlocked(floor);
            } finally {
                lock.unlock();
            }
            if (disembarked > 0) {
                log("DISEMBARK", disembarked + " passengers");
            }

            final EnumSet<Direction> allowed;
            lock.lock();
            try {
                EnumSet<Direction> set = hallCallsByFloor.get(floor);
                allowed = (set == null) ? EnumSet.noneOf(Direction.class) : EnumSet.copyOf(set);
            } finally {
                lock.unlock();
            }

            EnumSet<Direction> allowedForBoarding = allowed;
            if (allowedForBoarding.isEmpty()) {
                allowedForBoarding = EnumSet.of(Direction.UP, Direction.DOWN);
            }

            Direction boardingDir = chooseBoardingDirection(floor, allowedForBoarding);

            int freeSpace;
            lock.lock();
            try {
                freeSpace = maxCapacity - passengersInside.size();

                // обновляем статус FULL, если нужно
                if (freeSpace <= 0) status = ElevatorStatus.LOAD_FULL;
            } finally {
                lock.unlock();
            }

            List<Passenger> boarding = List.of();
            if (boardingDir != null && freeSpace > 0) {
                boarding = dispatcher.boardPassengers(floor, boardingDir, freeSpace);

                if (!boarding.isEmpty()) {
                    // добавляем внутрь и ставим внутренние цели
                    lock.lock();
                    try {
                        for (Passenger p : boarding) {
                            passengersInside.add(p);
                        }
                    } finally {
                        lock.unlock();
                    }

                    for (Passenger p : boarding) {
                        addInternalStop(p.getTargetFloor());
                    }

                    log("BOARD", "Boarded: " + boarding.size() + ", dir=" + boardingDir + ", load=" + getLoadSafe() + "/" + maxCapacity);

                    // время посадки
                    SimulationClock.sleep((long) Config.TIME_BOARDING * boarding.size());
                }

            }

            lock.lock();
            try {
                EnumSet<Direction> set = hallCallsByFloor.get(floor);
                if (set != null) {
                    set.removeAll(allowed);
                    if (set.isEmpty()) hallCallsByFloor.remove(floor);
                }
            } finally {
                lock.unlock();
            }

            SimulationClock.sleep(Config.TIME_DOORS);
            log("DOOR", "CLOSE");

            status = (getLoadSafe() >= maxCapacity) ? ElevatorStatus.LOAD_FULL : ElevatorStatus.MOVING;

            tryProcessPendingCalls();

            dispatcher.notifyElevatorUpdate(this);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void tryProcessPendingCalls() {
        for (int i = 0; i < 8; i++) {
            HallCall call = pendingCalls.poll();
            if (call == null) return;

            // Если вызов уже не актуален — просто выбрасываем.
            if (!dispatcher.hasWaiting(call.floor(), call.direction())) {
                continue;
            }

            // Если всё ещё не можем принять — возвращаем обратно и выходим.
            if (!canAcceptHallCall(call)) {
                pendingCalls.offer(call);
                return;
            }

            addHallCall(call.floor(), call.direction());
        }
    }

    private int unloadPassengersUnlocked(int floor) {
        int before = passengersInside.size();
        passengersInside.removeIf(p -> p.getTargetFloor() == floor);
        return before - passengersInside.size();
    }

    private boolean hasInternalNeedForFloorUnlocked(int floor) {
        for (Passenger p : passengersInside) {
            if (p.getTargetFloor() == floor) return true;
        }
        return false;
    }

    private Direction chooseBoardingDirection(int floor, EnumSet<Direction> allowed) {
        if (allowed == null || allowed.isEmpty()) return null;

        boolean upWaiting = dispatcher.hasWaiting(floor, Direction.UP);
        boolean downWaiting = dispatcher.hasWaiting(floor, Direction.DOWN);

        boolean upAllowed = allowed.contains(Direction.UP);
        boolean downAllowed = allowed.contains(Direction.DOWN);

        upWaiting = upWaiting && upAllowed;
        downWaiting = downWaiting && downAllowed;

        if (!upWaiting && !downWaiting) return null;

        Direction dirSnapshot;
        boolean hasStopsInCurrentDir;
        lock.lock();
        try {
            if (!passengersInside.isEmpty()) {
                if (currentDirection == Direction.UP && upWaiting) return Direction.UP;
                if (currentDirection == Direction.DOWN && downWaiting) return Direction.DOWN;
                return null;
            }

            dirSnapshot = currentDirection;
            hasStopsInCurrentDir = switch (dirSnapshot) {
                case UP -> !stopsUp.isEmpty();
                case DOWN -> !stopsDown.isEmpty();
                default -> false;
            };
        } finally {
            lock.unlock();
        }

        if (dirSnapshot == Direction.UP) {
            if (upWaiting) return Direction.UP;
            if (hasStopsInCurrentDir) return null; // ещё едем вверх по плану — не подбираем вниз
            return downWaiting ? Direction.DOWN : null;
        }
        if (dirSnapshot == Direction.DOWN) {
            if (downWaiting) return Direction.DOWN;
            if (hasStopsInCurrentDir) return null; // ещё едем вниз по плану — не подбираем вверх
            return upWaiting ? Direction.UP : null;
        }

        int upCnt = dispatcher.getWaitingCount(floor, Direction.UP);
        int downCnt = dispatcher.getWaitingCount(floor, Direction.DOWN);
        if (upWaiting && downWaiting) {
            return (upCnt >= downCnt) ? Direction.UP : Direction.DOWN;
        }
        return upWaiting ? Direction.UP : Direction.DOWN;
    }

    private int getLoadSafe() {
        lock.lock();
        try {
            return passengersInside.size();
        } finally {
            lock.unlock();
        }
    }


    public int getId() { return id; }
    public int getCapacity() { return maxCapacity; }

    private void log(String tag, String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.printf("[%s][Elevator-%d][%s] %s%n", time, id, tag, msg);
    }
}
