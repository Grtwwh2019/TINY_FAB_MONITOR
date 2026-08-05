package com.tinyfabmonitor;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

final class DagPanel extends JPanel {
    private static final int NODE_W = 195;
    private static final int NODE_H = 58;
    private final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    private List<Models.Dependency> edges = new ArrayList<Models.Dependency>();
    private double scale = 1.0;
    private double offsetX = 35;
    private double offsetY = 35;
    private Point dragStart;
    private String focusedId = "";
    private String emptyMessage = "请输入 FAB ID 并点击搜索定位";
    private Consumer<String> showDagAction;

    private static class Node {
        String id = "", description = "", threadId = "", levelNo = "", status = "";
        java.util.Date startedAt, completedAt, actTime;
        boolean actTimePlaceholder;
        long currentDuration, lastDuration, average;
        int completedCount;
        int x, y;
    }

    DagPanel() {
        setBackground(new Color(249, 251, 253));
        setPreferredSize(new Dimension(1100, 570));
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseWheelListener(this::zoom);
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent event) {
                if (showPopup(event)) return;
                if (event.getButton() == MouseEvent.BUTTON1) dragStart = event.getPoint();
            }
            public void mouseReleased(MouseEvent event) {
                showPopup(event);
                dragStart = null;
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent event) {
                if (dragStart == null) return;
                offsetX += event.getX() - dragStart.x;
                offsetY += event.getY() - dragStart.y;
                dragStart = event.getPoint();
                repaint();
            }
        });
    }

    void setShowDagAction(Consumer<String> action) { this.showDagAction = action; }

    void setDashboard(Models.Dashboard dashboard) {
        nodes.clear();
        edges = new ArrayList<Models.Dependency>();
        if (dashboard.dagLoading) emptyMessage = "正在读取上下游依赖…";
        else if (!dashboard.dagError.isEmpty()) emptyMessage = "依赖读取失败：" + dashboard.dagError;
        else if (dashboard.dagRootFabId.isEmpty()) emptyMessage = "请输入 FAB ID 并点击搜索定位";
        else emptyMessage = "当前业务日期没有可显示的依赖任务";

        if (dashboard.dagRootFabId.isEmpty() || dashboard.dagLoading || !dashboard.dagError.isEmpty()) {
            if (dashboard.dagRootFabId.isEmpty()) focusedId = "";
            repaint(); return;
        }
        Set<String> graphIds = new LinkedHashSet<String>();
        graphIds.add(normalize(dashboard.dagRootFabId));
        for (Models.Dependency edge : dashboard.dependencies) {
            graphIds.add(normalize(edge.fabId));
            graphIds.add(normalize(edge.dependencyId));
        }
        Map<String, Models.TaskView> tasksByFab = new LinkedHashMap<String, Models.TaskView>();
        for (Models.TaskView task : dashboard.tasks) {
            String id = normalize(task.fabId);
            if (!graphIds.contains(id)) continue;
            Models.TaskView previous = tasksByFab.get(id);
            if (previous == null || previous.actTime == null || (task.actTime != null && task.actTime.after(previous.actTime))) tasksByFab.put(id, task);
        }
        for (Models.TaskView task : tasksByFab.values()) nodes.put(normalize(task.fabId), nodeOf(task));
        for (Models.Dependency edge : dashboard.dependencies) {
            if (nodes.containsKey(normalize(edge.fabId)) && nodes.containsKey(normalize(edge.dependencyId))) edges.add(edge);
        }
        layoutNodes();
        if (!focusedId.isEmpty() && !nodes.containsKey(normalize(focusedId))) focusedId = "";
        repaint();
    }

    void resetView() { scale = 1; offsetX = 35; offsetY = 35; repaint(); }

    boolean focusFabId(String fabId) {
        Node target = nodes.get(normalize(fabId));
        if (target == null) return false;
        focusedId = target.id;
        offsetX = getWidth() / 2.0 - (target.x + NODE_W / 2.0) * scale;
        offsetY = getHeight() / 2.0 - (target.y + NODE_H / 2.0) * scale;
        repaint();
        return true;
    }

    private static Node nodeOf(Models.TaskView task) {
        Node node = new Node();
        node.id = task.fabId; node.description = task.fabDescription; node.threadId = task.threadId; node.levelNo = task.levelNo; node.status = task.status;
        node.actTime = task.actTime; node.actTimePlaceholder = task.actTimePlaceholder; node.startedAt = task.startedAt; node.completedAt = task.completedAt;
        node.currentDuration = task.currentDurationSeconds; node.lastDuration = task.lastDurationSeconds;
        node.average = task.averageDurationSeconds; node.completedCount = task.completedRunCount;
        return node;
    }

    private void layoutNodes() {
        Map<String, Integer> degree = new HashMap<String, Integer>();
        Map<String, List<String>> outgoing = new HashMap<String, List<String>>();
        Map<String, Integer> level = new HashMap<String, Integer>();
        for (String id : nodes.keySet()) { degree.put(id, 0); outgoing.put(id, new ArrayList<String>()); }
        for (Models.Dependency edge : edges) {
            String from = normalize(edge.dependencyId), to = normalize(edge.fabId);
            if (!nodes.containsKey(from) || !nodes.containsKey(to)) continue;
            outgoing.get(from).add(to); degree.put(to, degree.get(to) + 1);
        }
        Queue<String> queue = new ArrayDeque<String>();
        List<String> ids = new ArrayList<String>(nodes.keySet()); Collections.sort(ids);
        for (String id : ids) if (degree.get(id) == 0) { queue.add(id); level.put(id, 0); }
        while (!queue.isEmpty()) {
            String id = queue.remove();
            for (String next : outgoing.get(id)) {
                level.put(next, Math.max(level.containsKey(next) ? level.get(next) : 0, level.get(id) + 1));
                degree.put(next, degree.get(next) - 1);
                if (degree.get(next) == 0) queue.add(next);
            }
        }
        Map<Integer, List<String>> columns = new HashMap<Integer, List<String>>();
        for (String id : ids) {
            int column = level.containsKey(id) ? level.get(id) : 0;
            if (!columns.containsKey(column)) columns.put(column, new ArrayList<String>());
            columns.get(column).add(id);
        }
        for (Map.Entry<Integer, List<String>> entry : columns.entrySet()) {
            Collections.sort(entry.getValue());
            for (int row = 0; row < entry.getValue().size(); row++) {
                Node node = nodes.get(entry.getValue().get(row));
                node.x = entry.getKey() * 265; node.y = row * 92;
            }
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (nodes.isEmpty()) {
            g.setColor(new Color(105, 118, 132)); g.setFont(getFont().deriveFont(14f));
            int width = g.getFontMetrics().stringWidth(emptyMessage);
            g.drawString(emptyMessage, Math.max(24, (getWidth() - width) / 2), Math.max(40, getHeight() / 2));
            g.dispose(); return;
        }
        g.translate(offsetX, offsetY); g.scale(scale, scale);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(new Color(160, 172, 185));
        for (Models.Dependency edge : edges) {
            Node from = nodes.get(normalize(edge.dependencyId)), to = nodes.get(normalize(edge.fabId));
            if (from == null || to == null) continue;
            double x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2.0, x2 = to.x, y2 = to.y + NODE_H / 2.0, middle = (x1 + x2) / 2.0;
            Path2D path = new Path2D.Double(); path.moveTo(x1, y1); path.curveTo(middle, y1, middle, y2, x2 - 8, y2); g.draw(path);
            Path2D arrow = new Path2D.Double(); arrow.moveTo(x2, y2); arrow.lineTo(x2 - 10, y2 - 5); arrow.lineTo(x2 - 10, y2 + 5); arrow.closePath(); g.fill(arrow);
        }
        for (Node node : nodes.values()) drawNode(g, node);
        g.dispose();
    }

    private void drawNode(Graphics2D g, Node node) {
        Color color = statusColor(node.status);
        boolean unfinished = !"R".equalsIgnoreCase(node.status);
        if (node.id.equalsIgnoreCase(focusedId)) {
            g.setColor(new Color(255, 174, 0)); g.setStroke(new BasicStroke(4f));
            g.draw(new RoundRectangle2D.Double(node.x - 5, node.y - 5, NODE_W + 10, NODE_H + 10, 18, 18));
        }
        g.setColor(statusFill(node.status)); g.fill(new RoundRectangle2D.Double(node.x, node.y, NODE_W, NODE_H, 14, 14));
        g.setColor(color); g.setStroke(new BasicStroke(unfinished ? 3f : 1.7f)); g.draw(new RoundRectangle2D.Double(node.x, node.y, NODE_W, NODE_H, 14, 14));
        g.fillOval(node.x + 14, node.y + 16, 9, 9);
        g.setFont(getFont().deriveFont(Font.BOLD, 13f)); g.setColor(new Color(27, 39, 52)); g.drawString(clip(node.id, 22), node.x + 30, node.y + 25);
        g.setFont(getFont().deriveFont(Font.BOLD, 12f)); g.setColor(color); g.drawString(node.status, node.x + 15, node.y + 47);
        g.setFont(getFont().deriveFont(10f)); g.setColor(new Color(92, 106, 120)); g.drawString(unfinished ? "未完成" : "已完成", node.x + 42, node.y + 47);
    }

    @Override public String getToolTipText(MouseEvent event) {
        Node node = nodeAt(event.getPoint());
        if (node == null) return null;
        String statusTime = node.actTimePlaceholder ? "无有效时间" : UiFormat.dateTime(node.actTime);
        String duration = node.startedAt == null ? "--" : node.completedAt != null ? UiFormat.duration(node.lastDuration) : UiFormat.duration(node.currentDuration);
        String average = node.completedCount >= 2 ? UiFormat.duration(node.average) + "（" + node.completedCount + "次）" : "--（" + node.completedCount + "次）";
        return "<html><b>FAB ID：</b>" + html(node.id) + "<br><b>描述：</b>" + html(empty(node.description)) +
            "<br><b>Thread ID：</b>" + html(node.threadId) + "<br><b>Level No：</b>" + html(node.levelNo) +
            "<br><b>状态：</b>" + html(node.status) + "<br><b>状态开始时间：</b>" + statusTime +
            "<br><b>I 开始时间：</b>" + UiFormat.dateTime(node.startedAt) + "<br><b>当前/本次时长：</b>" + duration +
            "<br><b>历史平均：</b>" + average + "</html>";
    }

    private boolean showPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) return false;
        Node node = nodeAt(event.getPoint());
        if (node == null || showDagAction == null) return false;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem item = new JMenuItem("展示此 FAB 的依赖 DAG");
        item.addActionListener(e -> showDagAction.accept(node.id));
        menu.add(item); menu.show(this, event.getX(), event.getY());
        return true;
    }

    private Node nodeAt(Point point) {
        double x = (point.x - offsetX) / scale, y = (point.y - offsetY) / scale;
        for (Node node : nodes.values()) if (x >= node.x && x <= node.x + NODE_W && y >= node.y && y <= node.y + NODE_H) return node;
        return null;
    }

    private void zoom(MouseWheelEvent event) {
        double old = scale;
        scale = Math.max(.35, Math.min(2.3, scale * (event.getWheelRotation() < 0 ? 1.12 : .89)));
        double factor = scale / old;
        offsetX = event.getX() - (event.getX() - offsetX) * factor;
        offsetY = event.getY() - (event.getY() - offsetY) * factor;
        repaint();
    }

    private static Color statusColor(String status) {
        if ("R".equalsIgnoreCase(status)) return new Color(22, 143, 98);
        if ("E".equalsIgnoreCase(status)) return new Color(205, 37, 37);
        if ("I".equalsIgnoreCase(status)) return new Color(22, 93, 255);
        if ("B".equalsIgnoreCase(status)) return new Color(212, 123, 0);
        return new Color(125, 73, 190);
    }
    private static Color statusFill(String status) {
        if ("R".equalsIgnoreCase(status)) return new Color(241, 250, 246);
        if ("E".equalsIgnoreCase(status)) return new Color(255, 235, 235);
        if ("I".equalsIgnoreCase(status)) return new Color(235, 242, 255);
        if ("B".equalsIgnoreCase(status)) return new Color(255, 246, 228);
        return new Color(247, 239, 255);
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String clip(String value, int length) { return value == null ? "" : value.length() <= length ? value : value.substring(0, length - 1) + "…"; }
    private static String empty(String value) { return value == null || value.isEmpty() ? "--" : value; }
    private static String html(String value) { return empty(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
