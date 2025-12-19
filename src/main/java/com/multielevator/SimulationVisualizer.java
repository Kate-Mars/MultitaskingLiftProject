package com.multielevator;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;

public final class SimulationVisualizer {

    private final int floors;
    private final List<Elevator> elevators;
    private final Dispatcher dispatcher;
    private final SimulationControl control;

    private JFrame frame;
    private JLabel header;
    private RealisticBuildingPanel panel;
    private javax.swing.Timer timer;

    public SimulationVisualizer(int floors, List<Elevator> elevators, Dispatcher dispatcher, SimulationControl control) {
        this.floors = floors;
        this.elevators = new ArrayList<>(elevators);
        this.dispatcher = dispatcher;
        this.control = control;
    }

    public void start() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            frame = new JFrame("Multi-elevator simulation (visual)");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            header = new JLabel();
            header.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            header.setFont(header.getFont().deriveFont(Font.BOLD));

            panel = new RealisticBuildingPanel(floors, elevators, dispatcher);
            JScrollPane scroll = new JScrollPane(panel);
            scroll.getVerticalScrollBar().setUnitIncrement(24);
            scroll.getHorizontalScrollBar().setUnitIncrement(24);

            frame.add(header, BorderLayout.NORTH);
            frame.add(scroll, BorderLayout.CENTER);
            frame.add(buildControlsPanel(), BorderLayout.EAST);

            frame.setSize(1300, 820);
            frame.setLocationRelativeTo(null);

            installKeyBindings(frame.getRootPane());

            timer = new javax.swing.Timer(33, e -> {
                header.setText(buildHeaderText());
                panel.tick();
                panel.repaint();
            });
            timer.start();

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    if (timer != null) timer.stop();
                }
            });

            frame.setVisible(true);
        });
    }

    public void onSimulationFinished() {
        SwingUtilities.invokeLater(() -> {
            if (header != null) header.setText(buildHeaderText() + "  |  FINISHED");
            if (timer != null) timer.stop();
            if (panel != null) panel.repaint();
        });
    }

    private String buildHeaderText() {
        int waiting = dispatcher.getTotalWaiting();
        StringBuilder sb = new StringBuilder();
        sb.append("waiting: ").append(waiting);
        sb.append("  |  generated: ").append(control.getGeneratedCount()).append("/").append(control.getPassengerLimit());
        sb.append("  |  speed: ").append(String.format(Locale.US, "%.2fx", SimulationClock.getSpeed()));
        if (SimulationClock.isPaused()) sb.append("  (PAUSED)");
        sb.append("  |  elevators: ").append(elevators.size());
        if (Config.ZONING_ENABLED) {
            sb.append("  |  zoning: ON (split=").append(Config.ZONE_SPLIT_FLOOR).append(")");
        } else {
            sb.append("  |  zoning: OFF");
        }
        return sb.toString();
    }

    private JPanel buildControlsPanel() {
    JPanel root = new JPanel() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();

                // Gradient background
                GradientPaint gp = new GradientPaint(0, 0, new Color(18, 18, 30), 0, h, new Color(8, 8, 14));
                g2.setPaint(gp);
                g2.fillRect(0, 0, w, h);

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.08f));
                g2.setColor(Color.white);
                for (int x = -h; x < w + h; x += 16) {
                    g2.drawLine(x, 0, x + h, h);
                }
                g2.setComposite(AlphaComposite.SrcOver);

                g2.setColor(new Color(140, 120, 255, 140));
                g2.drawRoundRect(6, 8, w - 12, h - 16, 18, 18);
                g2.setColor(new Color(90, 240, 255, 90));
                g2.drawRoundRect(8, 10, w - 16, h - 20, 16, 16);
            } finally {
                g2.dispose();
            }
        }
    };
    root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
    root.setPreferredSize(new Dimension(340, 10));
    root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
    root.setOpaque(false);

    JLabel title = new JLabel("CONTROL DECK");
    title.setFont(new Font("SansSerif", Font.BOLD, 15));
    title.setForeground(new Color(235, 235, 245));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    root.add(title);

    JLabel subtitle = new JLabel("space / + / - / g");
    subtitle.setFont(new Font("SansSerif", Font.PLAIN, 11));
    subtitle.setForeground(new Color(150, 150, 170));
    subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
    root.add(subtitle);
    root.add(Box.createVerticalStrut(14));

    JLabel speedLabel = new JLabel();
    speedLabel.setForeground(new Color(235, 235, 245));
    speedLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

    JSlider speed = new JSlider(25, 800, (int) Math.round(SimulationClock.getSpeed() * 100.0));
    speed.setAlignmentX(Component.LEFT_ALIGNMENT);
    speed.setOpaque(false);
    speed.setForeground(new Color(200, 200, 215));
    speed.setPaintTicks(true);
    speed.setPaintLabels(true);
    speed.setMajorTickSpacing(100);
    speed.setMinorTickSpacing(25);
    speed.addChangeListener(e -> {
        double sp = speed.getValue() / 100.0;
        SimulationClock.setSpeed(sp);
        speedLabel.setText("Time warp: " + String.format(Locale.US, "%.2fx", sp));
    });
    speedLabel.setText("Time warp: " + String.format(Locale.US, "%.2fx", SimulationClock.getSpeed()));

    JButton pause = hudButton("Pause / Resume");
    pause.addActionListener(e -> SimulationClock.togglePause());

    JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    quick.setAlignmentX(Component.LEFT_ALIGNMENT);
    quick.setOpaque(false);
    JButton x1 = hudPill("1x");
    JButton x2 = hudPill("2x");
    JButton x5 = hudPill("5x");
    x1.addActionListener(e -> { SimulationClock.setSpeed(1.0); speed.setValue(100); });
    x2.addActionListener(e -> { SimulationClock.setSpeed(2.0); speed.setValue(200); });
    x5.addActionListener(e -> { SimulationClock.setSpeed(5.0); speed.setValue(500); });
    quick.add(x1); quick.add(x2); quick.add(x5);

    root.add(sectionTitle("SPEED"));
    root.add(speedLabel);
    root.add(Box.createVerticalStrut(6));
    root.add(speed);
    root.add(Box.createVerticalStrut(8));
    root.add(quick);
    root.add(Box.createVerticalStrut(8));
    root.add(pause);
    root.add(Box.createVerticalStrut(18));

    root.add(sectionTitle("PASSENGERS"));

    JLabel genTitle = new JLabel("Total to generate:");
    genTitle.setForeground(new Color(200, 200, 215));
    genTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
    root.add(genTitle);
    root.add(Box.createVerticalStrut(6));

    int initial = Math.max(control.getPassengerLimit(), control.getGeneratedCount());
    SpinnerNumberModel model = new SpinnerNumberModel(initial, 0, 100_000, 1);
    JSpinner limitSpinner = new JSpinner(model);
    limitSpinner.setAlignmentX(Component.LEFT_ALIGNMENT);
    limitSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, limitSpinner.getPreferredSize().height));
    styleSpinner(limitSpinner);

    JButton apply = hudButton("Apply");
    apply.setAlignmentX(Component.LEFT_ALIGNMENT);
    Runnable applyAction = () -> {
        Object v = limitSpinner.getValue();
        int n = (v instanceof Number) ? ((Number) v).intValue() : initial;
        control.setPassengerLimit(n);
    };
    apply.addActionListener(e -> applyAction.run());
    if (limitSpinner.getEditor() instanceof JSpinner.DefaultEditor ed) {
        ed.getTextField().addActionListener(e -> applyAction.run());
    }

    root.add(limitSpinner);
    root.add(Box.createVerticalStrut(10));
    root.add(apply);
    root.add(Box.createVerticalStrut(18));

    JTextArea help = new JTextArea(
                "Shortcuts:\n" +
                "  Space  — pause/resume\n" +
                "  + / -  — speed up / down\n" +
                "  G      — set passengers...\n\n" +
                "Tip: You can increase passenger total while running."
        );
        help.setEditable(false);
    help.setOpaque(false);
    help.setFont(new Font("SansSerif", Font.PLAIN, 12));
    help.setForeground(new Color(160, 160, 175));
    help.setAlignmentX(Component.LEFT_ALIGNMENT);
    root.add(help);

    return root;
}

private static JLabel sectionTitle(String text) {
    JLabel l = new JLabel(text);
    l.setFont(new Font("SansSerif", Font.BOLD, 12));
    l.setForeground(new Color(120, 220, 255));
    l.setAlignmentX(Component.LEFT_ALIGNMENT);
    l.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
    return l;
}

private static JButton hudButton(String text) {
    JButton b = new JButton(text);
    b.setAlignmentX(Component.LEFT_ALIGNMENT);
    b.setFocusPainted(false);
    b.setFont(new Font("SansSerif", Font.BOLD, 12));
    b.setForeground(new Color(235, 235, 245));
    b.setBackground(new Color(30, 30, 48));
    b.setOpaque(true);
    b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(140, 120, 255, 160), 1, true),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
    ));
    return b;
}

private static JButton hudPill(String text) {
    JButton b = new JButton(text);
    b.setFocusPainted(false);
    b.setFont(new Font("SansSerif", Font.BOLD, 12));
    b.setForeground(new Color(235, 235, 245));
    b.setBackground(new Color(24, 24, 38));
    b.setOpaque(true);
    b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(90, 240, 255, 130), 1, true),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
    ));
    return b;
}

private static void styleSpinner(JSpinner sp) {
    sp.setOpaque(false);
    if (sp.getEditor() instanceof JSpinner.DefaultEditor ed) {
        ed.getTextField().setFont(new Font("SansSerif", Font.BOLD, 12));
        ed.getTextField().setForeground(new Color(235, 235, 245));
        ed.getTextField().setBackground(new Color(20, 20, 30));
        ed.getTextField().setCaretColor(new Color(235, 235, 245));
        ed.getTextField().setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 240, 255, 130), 1, true),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
    }
    sp.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
}

    private void installKeyBindings(JComponent root) {
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();

        im.put(KeyStroke.getKeyStroke(' '), "togglePause");
        am.put("togglePause", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                SimulationClock.togglePause();
            }
        });

        im.put(KeyStroke.getKeyStroke('+'), "speedUp");
        im.put(KeyStroke.getKeyStroke('='), "speedUp");
        im.put(KeyStroke.getKeyStroke('-'), "speedDown");

        am.put("speedUp", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                SimulationClock.setSpeed(SimulationClock.getSpeed() * 1.15);
            }
        });
        am.put("speedDown", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                SimulationClock.setSpeed(SimulationClock.getSpeed() / 1.15);
            }
        });

        im.put(KeyStroke.getKeyStroke('g'), "setPassengers");
        im.put(KeyStroke.getKeyStroke('G'), "setPassengers");
        am.put("setPassengers", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                String in = JOptionPane.showInputDialog(frame,
                        "Total passengers to generate (can be increased during run):",
                        control.getPassengerLimit());
                if (in == null) return;
                in = in.trim();
                if (in.isEmpty()) return;
                try {
                    int n = Integer.parseInt(in);
                    control.setPassengerLimit(n);
                } catch (NumberFormatException ignored) {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
        });
    }

    private static final class RealisticBuildingPanel extends JPanel {

        // Layout
        private static final int TOP = 24;
        private static final int BOTTOM = 24;
        private static final int LEFT_INFO_W = 90;
        private static final int LOBBY_W = 250;

        private static final int SHAFT_W = 150;
        private static final int SHAFT_GAP = 26;

        private static final int FLOOR_H = 68; // крупно, чтобы было видно людей

        private static final int DOOR_H = 44;
        private static final int CAR_H = 54;

        private static final int MAX_WAITING_SAMPLE = 24;
        private static final int MAX_INSIDE_DRAW = 12;

        private static final long BOARD_MS = 650;
        private static final long EXIT_MS = 650;

        private final int floors;
        private final List<Elevator> elevators;
        private final Dispatcher dispatcher;

        private final Map<Integer, PassengerKindPos> lastPos = new HashMap<>();
        private final Map<Integer, Point> lastWaitingCenter = new HashMap<>();
        private final Map<Integer, Point> lastInsideCenter = new HashMap<>();

        private final List<Anim> animations = new ArrayList<>();
        private final Set<Integer> animatingIds = new HashSet<>();

        private final Map<Integer, List<Passenger>> waitingUpByFloor = new HashMap<>();
        private final Map<Integer, List<Passenger>> waitingDownByFloor = new HashMap<>();
        private final List<List<Passenger>> insideByElevator = new ArrayList<>();

        private final Map<Integer, Float> doorOpenByElevatorId = new HashMap<>();
        private long lastTickMs = System.currentTimeMillis();

private static final Color THEME_BG_TOP = new Color(10, 10, 22);
private static final Color THEME_BG_BOTTOM = new Color(2, 2, 6);
private static final Color THEME_GLASS = new Color(120, 210, 255, 45);
private static final Color THEME_NEON = new Color(120, 240, 255, 190);
private static final Color THEME_NEON_2 = new Color(165, 120, 255, 170);
private static final Color THEME_TEXT = new Color(235, 235, 245);
private static final Color THEME_MUTED = new Color(160, 160, 175);

private final List<Star> stars = new ArrayList<>();
private int starsW = -1, starsH = -1;
private final Random fxRand = new Random(42);

        RealisticBuildingPanel(int floors, List<Elevator> elevators, Dispatcher dispatcher) {
            this.floors = floors;
            this.elevators = elevators;
            this.dispatcher = dispatcher;
            setBackground(new Color(8, 8, 14));
            setOpaque(true);
            updatePreferredSize();
        }

        private void updatePreferredSize() {
    int shaftsW = elevators.size() * SHAFT_W + Math.max(0, elevators.size() - 1) * SHAFT_GAP;
    int w = LEFT_INFO_W + LOBBY_W + 30 + shaftsW + 40;
    int h = TOP + BOTTOM + floors * FLOOR_H;
    setPreferredSize(new Dimension(w, h));
    ensureStars(w, h);
    revalidate();
}

private void ensureStars(int w, int h) {
    if (w <= 0 || h <= 0) return;
    if (w == starsW && h == starsH && !stars.isEmpty()) return;

    starsW = w;
    starsH = h;
    stars.clear();

    fxRand.setSeed(42L + (long) floors * 1000L + (long) elevators.size() * 17L);
    int count = Math.max(120, (w * h) / 9000);
    for (int i = 0; i < count; i++) {
        float x = fxRand.nextFloat() * w;
        float y = fxRand.nextFloat() * h;
        float size = 0.8f + fxRand.nextFloat() * 2.4f;
        float phase = fxRand.nextFloat() * (float) (Math.PI * 2.0);
        float speed = 6f + fxRand.nextFloat() * 24f;
        stars.add(new Star(x, y, size, phase, speed));
    }
}

        void tick() {
            updatePreferredSize();

            long nowMs = System.currentTimeMillis();
            long dt = Math.max(1, nowMs - lastTickMs);
            lastTickMs = nowMs;

            for (Elevator e : elevators) {
                ElevatorSnapshot snap = e.snapshot();
                float cur = doorOpenByElevatorId.getOrDefault(snap.id(), 0f);
                float target = (snap.status() == ElevatorStatus.DOORS_OPEN) ? 1f : 0f;
                float step = (float) dt / 250f;
                if (target > cur) cur = Math.min(target, cur + step);
                else cur = Math.max(target, cur - step);
                doorOpenByElevatorId.put(snap.id(), cur);
            }

            waitingUpByFloor.clear();
            waitingDownByFloor.clear();
            insideByElevator.clear();

            Map<Integer, PassengerKindPos> curPos = new HashMap<>();
            Map<Integer, Point> curWaitingCenter = new HashMap<>();
            Map<Integer, Point> curInsideCenter = new HashMap<>();

            int totalFloors = dispatcher.getTotalFloors();
            for (int f = 1; f <= totalFloors; f++) {
                int upCount = dispatcher.getWaitingCount(f, Direction.UP);
                int downCount = dispatcher.getWaitingCount(f, Direction.DOWN);

                List<Passenger> up = dispatcher.peekWaitingPassengers(f, Direction.UP, Math.min(MAX_WAITING_SAMPLE, upCount));
                List<Passenger> down = dispatcher.peekWaitingPassengers(f, Direction.DOWN, Math.min(MAX_WAITING_SAMPLE, downCount));
                waitingUpByFloor.put(f, up);
                waitingDownByFloor.put(f, down);

                layoutWaitingGroup(curWaitingCenter, f, up, true);
                layoutWaitingGroup(curWaitingCenter, f, down, false);

                for (Passenger p : up) {
                    curPos.put(p.getId(), PassengerKindPos.waiting(f, p.getTargetFloor()));
                }
                for (Passenger p : down) {
                    curPos.put(p.getId(), PassengerKindPos.waiting(f, p.getTargetFloor()));
                }
            }

            for (int i = 0; i < elevators.size(); i++) {
                Elevator e = elevators.get(i);
                List<Passenger> inside = new ArrayList<>(e.passengersInsideSnapshot(0));
                inside.sort(Comparator.comparingInt(Passenger::getId));
                insideByElevator.add(inside);

                double pos = e.getVisualFloorPos();
                layoutInsideGroup(curInsideCenter, i, pos, inside);

                for (Passenger p : inside) {
                    curPos.put(p.getId(), PassengerKindPos.inside(i, p.getTargetFloor()));
                }
            }

            long now = nowMs;
            for (Map.Entry<Integer, PassengerKindPos> en : lastPos.entrySet()) {
                int pid = en.getKey();
                PassengerKindPos prev = en.getValue();
                PassengerKindPos cur = curPos.get(pid);

                if (prev.kind == Kind.WAITING && cur != null && cur.kind == Kind.INSIDE) {
                    Point from = lastWaitingCenter.get(pid);
                    Point to = curInsideCenter.get(pid);
                    if (from != null && to != null) {
                        animations.add(Anim.board(pid, from, to, now, BOARD_MS, cur.elevatorIndex));
                    }
                }

                if (prev.kind == Kind.INSIDE && cur == null) {
                    Point from = lastInsideCenter.get(pid);
                    if (from != null) {
                        Point to = exitPointForFloor(prev.targetFloor);
                        animations.add(Anim.exit(pid, from, to, now, EXIT_MS));
                    }
                }
            }

            animatingIds.clear();
            animations.removeIf(a -> a.isFinished(now));
            for (Anim a : animations) animatingIds.add(a.passengerId);

            lastPos.clear();
            lastPos.putAll(curPos);
            lastWaitingCenter.clear();
            lastWaitingCenter.putAll(curWaitingCenter);
            lastInsideCenter.clear();
            lastInsideCenter.putAll(curInsideCenter);
        }

        private void layoutWaitingGroup(Map<Integer, Point> centers, int floor, List<Passenger> ps, boolean upGroup) {
            if (ps == null || ps.isEmpty()) return;

            int yCenter = yForFloorCenter(floor);
            int baseY = yCenter + (upGroup ? -14 : 14);

            int doorLineX = shaftsStartX();
            int baseX = doorLineX - 30;

            int cols = 6;
            int gapX = 18;
            int gapY = 18;

            for (int i = 0; i < ps.size(); i++) {
                Passenger p = ps.get(i);
                int row = i / cols;
                int col = i % cols;
                int x = baseX - col * gapX;
                int y = baseY + row * gapY;
                centers.put(p.getId(), new Point(x, y));
            }
        }

        private void layoutInsideGroup(Map<Integer, Point> centers, int elevatorIndex, double visualPos, List<Passenger> ps) {
            if (ps == null || ps.isEmpty()) return;

            Rectangle car = carRectAt(elevatorIndex, visualPos);
            int cols = 4;
            int gapX = 20;
            int gapY = 18;
            int startX = car.x + 24;
            int startY = car.y + 28;

            for (int i = 0; i < ps.size(); i++) {
                Passenger p = ps.get(i);
                int row = i / cols;
                int col = i % cols;
                int x = startX + col * gapX;
                int y = startY + row * gapY;
                if (y > car.y + car.height - 12) break;
                centers.put(p.getId(), new Point(x, y));
            }
        }

        private Point exitPointForFloor(int floor) {
            int x = LEFT_INFO_W + 18;
            int y = yForFloorCenter(floor);
            return new Point(x, y);
        }

        private int shaftsStartX() {
            return LEFT_INFO_W + LOBBY_W + 20;
        }

        private int shaftX(int elevatorIndex) {
            return shaftsStartX() + elevatorIndex * (SHAFT_W + SHAFT_GAP);
        }

        private Rectangle carRectAt(int elevatorIndex, double floorPos) {
            int shaftX = shaftX(elevatorIndex);
            int yCenter = (int) Math.round(yForFloorCenter(floorPos));
            int x = shaftX + 14;
            int y = yCenter - CAR_H / 2;
            int w = SHAFT_W - 28;
            return new Rectangle(x, y, w, CAR_H);
        }

        private int yForFloorCenter(int floor) {
            int clamped = Math.max(1, Math.min(floors, floor));
            int idxFromTop = floors - clamped;
            return TOP + idxFromTop * FLOOR_H + FLOOR_H / 2;
        }

        private double yForFloorCenter(double floorPos) {
            double clamped = Math.max(1.0, Math.min((double) floors, floorPos));
            double idxFromTop = floors - clamped;
            return TOP + idxFromTop * FLOOR_H + FLOOR_H / 2.0;
        }

        private int yForFloorDoorTop(int floor) {
            return yForFloorCenter(floor) - DOOR_H / 2;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                drawStaticBackground(g2);
                drawLobby(g2);
                drawFloors(g2);
                drawShaftsAndDoors(g2);
                drawCarsAndInside(g2);
                drawWaitingPeople(g2);
                drawAnimations(g2);

            } finally {
                g2.dispose();
            }
        }

        private void drawStaticBackground(Graphics2D g2) {
    int w = getWidth();
    int h = getHeight();
    ensureStars(getPreferredSize().width, getPreferredSize().height);

    g2.setPaint(new GradientPaint(0, 0, THEME_BG_TOP, 0, h, THEME_BG_BOTTOM));
    g2.fillRect(0, 0, w, h);

    long t = System.currentTimeMillis();
    for (Star s : stars) {
        float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin((t / 1000.0) * (s.speed / 10.0) + s.phase));
        int a = (int) (25 + 180 * tw);
        g2.setColor(new Color(230, 240, 255, Math.max(0, Math.min(255, a))));
        int x = (int) (s.x);
        int y = (int) (s.y);
        int r = Math.max(1, (int) s.size);
        g2.fillOval(x, y, r, r);

        if (r >= 2 && ((x + y) % 37 == 0)) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
            g2.drawLine(x - 2, y, x + 2, y);
            g2.drawLine(x, y - 2, x, y + 2);
            g2.setComposite(AlphaComposite.SrcOver);
        }

        s.y += 0.05f;
        if (s.y > h + 10) s.y = -10;
    }

    int horizon = TOP + floors * FLOOR_H + 10;
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
    g2.setColor(new Color(8, 8, 18));
    g2.fillRect(0, horizon, w, h - horizon);
    g2.setComposite(AlphaComposite.SrcOver);

    int buildingX = LEFT_INFO_W - 14;
    int buildingY = 10;
    int buildingW = w - buildingX - 18;
    int buildingH = TOP + floors * FLOOR_H + BOTTOM - 10;

    g2.setColor(new Color(0, 0, 0, 120));
    g2.fillRoundRect(buildingX + 10, buildingY + 10, buildingW, buildingH, 28, 28);

    g2.setPaint(new GradientPaint(buildingX, buildingY, new Color(22, 24, 40, 210),
            buildingX, buildingY + buildingH, new Color(6, 6, 12, 210)));
    g2.fillRoundRect(buildingX, buildingY, buildingW, buildingH, 28, 28);

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
    g2.setPaint(new GradientPaint(buildingX, 0, new Color(120, 210, 255, 80),
            buildingX + buildingW, 0, new Color(255, 255, 255, 0)));
    g2.fillRoundRect(buildingX + 14, buildingY + 10, buildingW / 2, buildingH - 20, 24, 24);
    g2.setComposite(AlphaComposite.SrcOver);

    g2.setColor(new Color(120, 240, 255, 110));
    g2.drawRoundRect(buildingX, buildingY, buildingW, buildingH, 28, 28);
    g2.setColor(new Color(165, 120, 255, 90));
    g2.drawRoundRect(buildingX + 2, buildingY + 2, buildingW - 4, buildingH - 4, 26, 26);

    int cols = Math.max(1, (buildingW - 60) / 36);
    int winW = 18;
    int winH = 10;
    for (int f = 1; f <= floors; f++) {
        int y = yForFloorDoorTop(f) + 12;
        for (int c = 0; c < cols; c++) {
            int x = buildingX + 20 + c * 34;
            if (x > shaftsStartX() - 34) break;

            int hsh = (f * 10007) ^ (c * 97);
            boolean on = (Math.abs(hsh) % 5) != 0;
            if (!on) continue;

            float pulse = 0.5f + 0.5f * (float) Math.sin((t / 700.0) + (f * 0.23) + (c * 0.7));
            int a = 25 + (int) (90 * pulse);
            g2.setColor(new Color(120, 240, 255, a));
            g2.fillRoundRect(x, y, winW, winH, 6, 6);
        }
    }
}

        private void drawLobby(Graphics2D g2) {
    int x = LEFT_INFO_W;
    int w = LOBBY_W;
    int h = TOP + floors * FLOOR_H + BOTTOM;

    long t = System.currentTimeMillis();

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
    g2.setPaint(new GradientPaint(x, 0, new Color(30, 40, 70, 120), x + w, 0, new Color(10, 12, 22, 170)));
    g2.fillRect(x, 0, w, h);

    int doorLineX = shaftsStartX() - 10;
    g2.setColor(new Color(120, 240, 255, 140));
    g2.drawLine(doorLineX, 0, doorLineX, h);

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
    g2.setColor(new Color(120, 240, 255, 120));
    for (int yy = TOP; yy < h; yy += 18) {
        g2.drawLine(x + 10, yy, x + w - 10, yy);
    }
    for (int xx = x + 12; xx < x + w; xx += 34) {
        g2.drawLine(xx, TOP, xx, h);
    }
    g2.setComposite(AlphaComposite.SrcOver);

    int scanY = (int) ((t / 12) % (h + 120)) - 60;
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
    g2.setPaint(new GradientPaint(0, scanY, new Color(90, 240, 255, 0),
            0, scanY + 120, new Color(90, 240, 255, 160)));
    g2.fillRect(x, scanY, w, 120);
    g2.setComposite(AlphaComposite.SrcOver);

    g2.setFont(new Font("SansSerif", Font.BOLD, 12));
    int plateW = 148;
    int plateH = 22;
    int px = x + 12;
    int py = 10;

    g2.setColor(new Color(0, 0, 0, 110));
    g2.fillRoundRect(px + 2, py + 2, plateW, plateH, 12, 12);

    g2.setColor(new Color(20, 20, 34, 220));
    g2.fillRoundRect(px, py, plateW, plateH, 12, 12);

    g2.setColor(new Color(120, 240, 255, 160));
    g2.drawRoundRect(px, py, plateW, plateH, 12, 12);

    g2.setColor(new Color(235, 235, 245));
    g2.drawString("LOBBY", px + 12, py + 15);

    int baseY = yForFloorCenter(1) + 12;
    drawHoloColumn(g2, x + 18, baseY);
    drawHoloColumn(g2, x + w - 44, baseY);
}

private void drawHoloColumn(Graphics2D g2, int x, int y) {
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.65f));
    g2.setPaint(new GradientPaint(x, y - 30, new Color(165, 120, 255, 180),
            x, y + 30, new Color(90, 240, 255, 120)));
    g2.fillRoundRect(x, y - 26, 22, 58, 12, 12);

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
    g2.setColor(new Color(255, 255, 255, 200));
    g2.drawLine(x + 6, y - 22, x + 6, y + 26);
    g2.setComposite(AlphaComposite.SrcOver);

    g2.setColor(new Color(120, 240, 255, 160));
    g2.drawRoundRect(x, y - 26, 22, 58, 12, 12);
}

        private void drawPlant(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(120, 95, 70));
            g2.fillRoundRect(x, y + 14, 18, 12, 6, 6);
            g2.setColor(new Color(60, 130, 70));
            g2.fillOval(x - 4, y, 26, 22);
            g2.setColor(new Color(40, 90, 50));
            g2.drawOval(x - 4, y, 26, 22);
        }

        private void drawFloors(Graphics2D g2) {
    int right = getPreferredSize().width;

    g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
    for (int f = 1; f <= floors; f++) {
        int y = yForFloorCenter(f);

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.14f));
        g2.setColor(new Color(120, 240, 255));
        g2.drawLine(LEFT_INFO_W - 6, y, right - 10, y);
        g2.setComposite(AlphaComposite.SrcOver);

        // LED floor plate
        int px = 10;
        int py = y - 14;
        int pw = 68;
        int ph = 28;

        g2.setColor(new Color(0, 0, 0, 130));
        g2.fillRoundRect(px + 2, py + 2, pw, ph, 12, 12);

        g2.setColor(new Color(16, 16, 26, 220));
        g2.fillRoundRect(px, py, pw, ph, 12, 12);

        g2.setColor(new Color(90, 240, 255, 160));
        g2.drawRoundRect(px, py, pw, ph, 12, 12);

        g2.setColor(new Color(235, 235, 245));
        String fl = String.format(Locale.US, "F%02d", f);
        g2.drawString(fl, px + 10, y + 4);

        int up = dispatcher.getWaitingCount(f, Direction.UP);
        int down = dispatcher.getWaitingCount(f, Direction.DOWN);

        drawCounterPill(g2, 86, y - 16, "↑", up, new Color(255, 170, 80));
        drawCounterPill(g2, 86, y + 2, "↓", down, new Color(110, 170, 255));
    }
}

private void drawCounterPill(Graphics2D g2, int x, int y, String icon, int count, Color accent) {
    int w = 64;
    int h = 16;

    g2.setColor(new Color(0, 0, 0, 120));
    g2.fillRoundRect(x + 2, y + 2, w, h, 10, 10);

    g2.setColor(new Color(16, 16, 26, 210));
    g2.fillRoundRect(x, y, w, h, 10, 10);

    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 160));
    g2.drawRoundRect(x, y, w, h, 10, 10);

    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
    g2.setColor(new Color(235, 235, 245));
    g2.drawString(icon + ":" + count, x + 10, y + 12);
}

        private void drawShaftsAndDoors(Graphics2D g2) {
    int topY = TOP;
    int bottomY = TOP + floors * FLOOR_H;

    for (int i = 0; i < elevators.size(); i++) {
        int sx = shaftX(i);

        Color accent = elevatorAccent(i);

        g2.setPaint(new GradientPaint(sx, topY, new Color(12, 12, 20), sx, bottomY, new Color(0, 0, 0)));
        g2.fillRoundRect(sx, topY, SHAFT_W, bottomY - topY, 18, 18);

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
        g2.setPaint(new GradientPaint(sx, topY, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120),
                sx + SHAFT_W, topY, new Color(255, 255, 255, 0)));
        g2.fillRoundRect(sx + 6, topY + 6, SHAFT_W - 12, bottomY - topY - 12, 14, 14);
        g2.setComposite(AlphaComposite.SrcOver);

        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
        g2.drawRoundRect(sx, topY, SHAFT_W, bottomY - topY, 18, 18);

        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
        g2.drawLine(sx + 22, topY + 10, sx + 22, bottomY - 10);
        g2.drawLine(sx + SHAFT_W - 22, topY + 10, sx + SHAFT_W - 22, bottomY - 10);

        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        g2.setColor(new Color(0, 0, 0, 140));
        g2.fillRoundRect(sx + 6, topY - 18, 34, 16, 10, 10);
        g2.setColor(THEME_TEXT);
        g2.drawString("E" + (i + 1), sx + 14, topY - 6);

        for (int f = 1; f <= floors; f++) {
            int dy = yForFloorDoorTop(f);
            int doorX = sx + 12;
            int doorW = SHAFT_W - 24;

            g2.setColor(new Color(18, 18, 28, 220));
            g2.fillRoundRect(doorX, dy, doorW, DOOR_H, 10, 10);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
            g2.drawRoundRect(doorX, dy, doorW, DOOR_H, 10, 10);
            int up = dispatcher.getWaitingCount(f, Direction.UP);
            int down = dispatcher.getWaitingCount(f, Direction.DOWN);
            int ledX = doorX + doorW - 22;
            int ledY = dy + 8;

            drawLedDot(g2, ledX, ledY, up > 0, new Color(255, 170, 80));
            drawLedDot(g2, ledX, ledY + 16, down > 0, new Color(110, 170, 255));
        }
    }
}

private void drawLedDot(Graphics2D g2, int x, int y, boolean on, Color c) {
    int r = 8;
    if (on) {
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 220));
        g2.fillOval(x, y, r, r);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
        g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 255));
        g2.fillOval(x - 3, y - 3, r + 6, r + 6);
        g2.setComposite(AlphaComposite.SrcOver);
    } else {
        g2.setColor(new Color(255, 255, 255, 40));
        g2.drawOval(x, y, r, r);
    }
}

private Color elevatorAccent(int idx) {
    if (idx % 2 == 0) return new Color(90, 240, 255);
    return new Color(165, 120, 255);
}

        private void drawCarsAndInside(Graphics2D g2) {
            for (int i = 0; i < elevators.size(); i++) {
                Elevator e = elevators.get(i);
                ElevatorSnapshot s = e.snapshot();

                double pos = e.getVisualFloorPos();
                Rectangle car = carRectAt(i, pos);
                drawCar(g2, i, s, car);

                List<Passenger> insideSrc = (insideByElevator.size() > i)
                        ? insideByElevator.get(i)
                        : e.passengersInsideSnapshot(0);
                List<Passenger> inside = new ArrayList<>(insideSrc);
                inside.sort(Comparator.comparingInt(Passenger::getId));

                int drawn = 0;
                for (Passenger p : inside) {
                    if (drawn >= MAX_INSIDE_DRAW) break;
                    if (animatingIds.contains(p.getId())) continue;

                    Point c = lastInsideCenter.get(p.getId());
                    if (c == null) c = new Point(car.x + 24 + (drawn % 4) * 20, car.y + 28 + (drawn / 4) * 18);

                    Color shirt = (p.getDirection() == Direction.UP) ? new Color(255, 170, 80) : new Color(110, 170, 255);
                    drawStickPerson(g2, c.x, c.y, 10, shirt, shortId(p.getId()), routeLabel(p));
                    drawn++;
                }

                int total = s.load();
                if (total > drawn) {
                    g2.setColor(new Color(30, 30, 30));
                    g2.setFont(getFont().deriveFont(Font.BOLD, 12f));
                    g2.drawString("+" + (total - drawn), car.x + car.width - 26, car.y + car.height - 10);
                }
            }
        }

        private void drawCar(Graphics2D g2, int idx, ElevatorSnapshot s, Rectangle car) {
    Color accent = elevatorAccent(idx);

    Shape rr = new RoundRectangle2D.Double(car.x, car.y, car.width, car.height, 18, 18);

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 255));
    g2.fill(new RoundRectangle2D.Double(car.x - 6, car.y - 6, car.width + 12, car.height + 12, 22, 22));
    g2.setComposite(AlphaComposite.SrcOver);

    Color base;
    if (s.status() == ElevatorStatus.LOAD_FULL) base = new Color(80, 30, 40);
    else if (s.status() == ElevatorStatus.DOORS_OPEN) base = new Color(20, 40, 70);
    else if (s.status() == ElevatorStatus.IDLE) base = new Color(18, 18, 28);
    else base = new Color(18, 30, 22);

    g2.setPaint(new GradientPaint(car.x, car.y, base.brighter(), car.x, car.y + car.height, base.darker()));
    g2.fill(rr);

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
    g2.setPaint(new GradientPaint(car.x, car.y, new Color(255, 255, 255, 140),
            car.x + car.width, car.y, new Color(255, 255, 255, 0)));
    g2.fill(new RoundRectangle2D.Double(car.x + 10, car.y + 6, car.width / 2.2, car.height - 12, 14, 14));
    g2.setComposite(AlphaComposite.SrcOver);

    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
    g2.draw(rr);
    g2.setColor(new Color(255, 255, 255, 50));
    g2.drawRoundRect(car.x + 2, car.y + 2, car.width - 4, car.height - 4, 16, 16);

    int doorX = car.x + 10;
    int doorY = car.y + 8;
    int doorW = car.width - 20;
    int doorH = car.height - 16;

    float open = doorOpenByElevatorId.getOrDefault(s.id(), (s.status() == ElevatorStatus.DOORS_OPEN) ? 1f : 0f);
    int gap = (int) (doorW * (0.80f * open));
    int half = doorW / 2;

    if (gap > 0) {
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.70f));
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRoundRect(doorX + half - gap / 2, doorY + 4, gap, doorH - 8, 12, 12);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220));
        g2.drawRoundRect(doorX + half - gap / 2, doorY + 4, gap, doorH - 8, 12, 12);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    g2.setColor(new Color(255, 255, 255, 34));
    g2.fillRect(doorX, doorY, half - gap / 2, doorH);
    g2.fillRect(doorX + half + gap / 2, doorY, doorW - half - gap / 2, doorH);

    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
    g2.drawRect(doorX, doorY, doorW, doorH);
    if (gap == 0) g2.drawLine(doorX + half, doorY, doorX + half, doorY + doorH);

    String dir = (s.direction() == Direction.UP) ? "↑" : (s.direction() == Direction.DOWN) ? "↓" : "·";
    String load = s.load() + "/" + s.capacity();

    int hudX = car.x + 10;
    int hudY = car.y + 8;

    g2.setColor(new Color(0, 0, 0, 150));
    g2.fillRoundRect(hudX, hudY, 54, 18, 10, 10);
    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
    g2.drawRoundRect(hudX, hudY, 54, 18, 10, 10);

    g2.setFont(new Font("Monospaced", Font.BOLD, 12));
    g2.setColor(THEME_TEXT);
    g2.drawString("#" + s.id() + dir, hudX + 10, hudY + 13);

    g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
    g2.setColor(new Color(235, 235, 245, 210));
    g2.drawString(load, car.x + 12, car.y + 36);

    g2.setFont(new Font("Monospaced", Font.BOLD, 12));
    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220));
    g2.drawString(String.format(Locale.US, "F%02d", s.currentFloor()), car.x + car.width - 44, car.y + 18);
}

        private void drawWaitingPeople(Graphics2D g2) {
            g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));

            for (int f = 1; f <= floors; f++) {
                List<Passenger> up = waitingUpByFloor.getOrDefault(f, List.of());
                List<Passenger> down = waitingDownByFloor.getOrDefault(f, List.of());

                int drawn = 0;
                for (Passenger p : up) {
                    if (animatingIds.contains(p.getId())) continue;

                    Point c = lastWaitingCenter.get(p.getId());
                    if (c == null) continue;
                    drawStickPerson(g2, c.x, c.y, 10, new Color(255, 170, 80), shortId(p.getId()), routeLabel(p));
                    drawn++;
                }

                drawn = 0;
                for (Passenger p : down) {
                    if (animatingIds.contains(p.getId())) continue;

                    Point c = lastWaitingCenter.get(p.getId());
                    if (c == null) continue;
                    drawStickPerson(g2, c.x, c.y, 10, new Color(110, 170, 255), shortId(p.getId()), routeLabel(p));
                    drawn++;
                }

                int totalUp = dispatcher.getWaitingCount(f, Direction.UP);
                int totalDown = dispatcher.getWaitingCount(f, Direction.DOWN);
                int y = yForFloorCenter(f);

                if (totalUp > up.size()) {
                    drawCrowdBadge(g2, LEFT_INFO_W + 18, y - 18, totalUp);
                }
                if (totalDown > down.size()) {
                    drawCrowdBadge(g2, LEFT_INFO_W + 18, y + 10, totalDown);
                }
            }
        }

        private void drawAnimations(Graphics2D g2) {
            long now = System.currentTimeMillis();

            for (Anim a : animations) {
                float t = a.progress(now);
                int x = (int) (a.from.x + (a.to.x - a.from.x) * t);
                int y = (int) (a.from.y + (a.to.y - a.from.y) * t);

                float alpha = 0.35f + 0.65f * (1f - Math.abs(t - 0.5f) * 2f);
                Composite old = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, Math.max(0f, alpha))));

                Color shirt = (a.type == AnimType.BOARD) ? new Color(255, 170, 80) : new Color(140, 230, 170);
                drawStickPerson(g2, x, y, 11, shirt, shortId(a.passengerId), "");

                g2.setComposite(old);
            }
        }

        private static void drawStickPerson(Graphics2D g2, int cx, int cy, int size, Color shirt, String label, String route) {
    int headR = Math.max(5, size / 2);
    int bodyW = Math.max(10, size);
    int bodyH = Math.max(12, size + 2);

    int headCx = cx;
    int headCy = cy - bodyH / 2;

    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
    g2.setColor(Color.black);
    g2.fillOval(cx - bodyW / 2, cy + bodyH / 2 - 2, bodyW, 6);
    g2.setComposite(AlphaComposite.SrcOver);

    g2.setColor(new Color(245, 235, 220));
    g2.fillOval(headCx - headR, headCy - headR, headR * 2, headR * 2);
    g2.setColor(new Color(255, 255, 255, 60));
    g2.fillOval(headCx - headR + 2, headCy - headR + 2, headR, headR);

    g2.setColor(new Color(40, 40, 50));
    g2.drawOval(headCx - headR, headCy - headR, headR * 2, headR * 2);

    int bodyX = cx - bodyW / 2;
    int bodyY = headCy + headR - 2;
    g2.setColor(new Color(shirt.getRed(), shirt.getGreen(), shirt.getBlue(), 220));
    g2.fillRoundRect(bodyX, bodyY, bodyW, bodyH, 16, 16);
    g2.setColor(new Color(255, 255, 255, 40));
    g2.drawRoundRect(bodyX, bodyY, bodyW, bodyH, 16, 16);

    g2.setColor(new Color(230, 230, 240, 90));
    g2.fillRoundRect(bodyX + 2, bodyY + bodyH - 4, bodyW / 2 - 2, 8, 8, 8);
    g2.fillRoundRect(bodyX + bodyW / 2, bodyY + bodyH - 4, bodyW / 2 - 2, 8, 8, 8);

    g2.setFont(new Font("Monospaced", Font.BOLD, 10));
    g2.setColor(new Color(8, 8, 14, 220));
    g2.drawString(label, bodyX + 2, bodyY + bodyH / 2 + 4);

    if (route != null && !route.isBlank()) {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(route) + 10;
        int th = 16;
        int bx = cx - tw / 2;
        int by = headCy - headR - 18;

        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRoundRect(bx + 2, by + 2, tw, th, 10, 10);

        g2.setColor(new Color(16, 16, 26, 220));
        g2.fillRoundRect(bx, by, tw, th, 10, 10);

        g2.setColor(new Color(120, 240, 255, 150));
        g2.drawRoundRect(bx, by, tw, th, 10, 10);

        g2.setColor(new Color(235, 235, 245));
        g2.drawString(route, bx + 5, by + 12);
    }
}

        private static String routeLabel(Passenger p) {
            if (p == null) return "";
            return p.getStartFloor() + "→" + p.getTargetFloor();
        }

        private static void drawCrowdBadge(Graphics2D g2, int x, int y, int count) {
    int pillW = 64;
    int pillH = 18;

    g2.setColor(new Color(0, 0, 0, 120));
    g2.fillRoundRect(x + 2, y + 2, pillW, pillH, 12, 12);

    g2.setColor(new Color(16, 16, 26, 220));
    g2.fillRoundRect(x, y, pillW, pillH, 12, 12);

    g2.setColor(new Color(90, 240, 255, 140));
    g2.drawRoundRect(x, y, pillW, pillH, 12, 12);

    int r = 7;
    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
    g2.setColor(new Color(235, 235, 245, 180));
    g2.fillOval(x + 8, y + 5, r, r);
    g2.fillOval(x + 16, y + 6, r, r);
    g2.fillOval(x + 24, y + 5, r, r);
    g2.setComposite(AlphaComposite.SrcOver);

    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
    g2.setColor(new Color(235, 235, 245));
    g2.drawString("×" + count, x + 36, y + 13);
}

        private static String shortId(int id) {
            String s = String.valueOf(id);
            if (s.length() <= 3) return s;
            return s.substring(s.length() - 3);
        }


private static final class Star {
    float x;
    float y;
    final float size;
    final float phase;
    final float speed;

    Star(float x, float y, float size, float phase, float speed) {
        this.x = x;
        this.y = y;
        this.size = size;
        this.phase = phase;
        this.speed = speed;
    }
}

        private enum Kind { WAITING, INSIDE }

        private static final class PassengerKindPos {
            final Kind kind;
            final int elevatorIndex; // for inside
            final int targetFloor;

            private PassengerKindPos(Kind kind, int elevatorIndex, int targetFloor) {
                this.kind = kind;
                this.elevatorIndex = elevatorIndex;
                this.targetFloor = targetFloor;
            }

            static PassengerKindPos waiting(int floor, int targetFloor) {
                return new PassengerKindPos(Kind.WAITING, -1, targetFloor);
            }

            static PassengerKindPos inside(int elevatorIndex, int targetFloor) {
                return new PassengerKindPos(Kind.INSIDE, elevatorIndex, targetFloor);
            }
        }

        private enum AnimType { BOARD, EXIT }

        private static final class Anim {
            final int passengerId;
            final AnimType type;
            final Point from;
            final Point to;
            final long start;
            final long duration;
            final int elevatorIndex; // only for board (debug)

            private Anim(int passengerId, AnimType type, Point from, Point to, long start, long duration, int elevatorIndex) {
                this.passengerId = passengerId;
                this.type = type;
                this.from = from;
                this.to = to;
                this.start = start;
                this.duration = Math.max(1, duration);
                this.elevatorIndex = elevatorIndex;
            }

            static Anim board(int pid, Point from, Point to, long start, long duration, int elevatorIndex) {
                return new Anim(pid, AnimType.BOARD, from, to, start, duration, elevatorIndex);
            }

            static Anim exit(int pid, Point from, Point to, long start, long duration) {
                return new Anim(pid, AnimType.EXIT, from, to, start, duration, -1);
            }

            float progress(long now) {
                long dt = now - start;
                if (dt <= 0) return 0f;
                if (dt >= duration) return 1f;
                // ease-in-out
                float t = dt / (float) duration;
                return (float) (t * t * (3 - 2 * t));
            }

            boolean isFinished(long now) {
                return (now - start) >= duration;
            }
        }
    }
}
