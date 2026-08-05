package com.tinyfabmonitor;

import javax.swing.JPanel;
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
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

final class DagPanel extends JPanel {
    private static final int NODE_W = 190;
    private static final int NODE_H = 88;
    private final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    private List<Models.Dependency> edges = new ArrayList<Models.Dependency>();
    private double scale = 1.0;
    private double offsetX = 35;
    private double offsetY = 35;
    private Point dragStart;

    private static class Node {
        String id, description = "", status = "";
        java.util.Date startedAt;
        long average;
        int x, y;
    }

    DagPanel() {
        setBackground(new Color(249, 251, 253));
        setPreferredSize(new Dimension(1100, 570));
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseWheelListener(this::zoom);
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragStart = e.getPoint(); }
            public void mouseReleased(MouseEvent e) { dragStart = null; }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                offsetX += e.getX() - dragStart.x;
                offsetY += e.getY() - dragStart.y;
                dragStart = e.getPoint();
                repaint();
            }
        });
    }

    void setDashboard(Models.Dashboard dashboard) {
        nodes.clear();
        edges = new ArrayList<Models.Dependency>(dashboard.dependencies);
        for (Models.TaskView task : dashboard.tasks) {
            Node node = nodes.get(task.fabId);
            if (node == null) { node = new Node(); node.id = task.fabId; nodes.put(node.id, node); }
            node.description = task.fabDescription;
            node.status = task.status;
            node.startedAt = task.startedAt;
            node.average = task.averageDurationSeconds;
        }
        for (Models.Dependency edge : edges) {
            ensureNode(edge.fabId);
            ensureNode(edge.dependencyId);
        }
        for (Map.Entry<String, Long> entry : dashboard.historicalAverageByFab.entrySet()) {
            Node node = nodes.get(entry.getKey());
            if (node != null && node.average == 0) node.average = entry.getValue();
        }
        layoutNodes();
        repaint();
    }

    void resetView() { scale = 1; offsetX = 35; offsetY = 35; repaint(); }

    private void ensureNode(String id) {
        if (!nodes.containsKey(id)) { Node node = new Node(); node.id = id; nodes.put(id, node); }
    }

    private void layoutNodes() {
        Map<String, Integer> degree = new HashMap<String, Integer>();
        Map<String, List<String>> outgoing = new HashMap<String, List<String>>();
        Map<String, Integer> level = new HashMap<String, Integer>();
        for (String id : nodes.keySet()) { degree.put(id, 0); outgoing.put(id, new ArrayList<String>()); }
        for (Models.Dependency edge : edges) {
            if (!nodes.containsKey(edge.fabId) || !nodes.containsKey(edge.dependencyId)) continue;
            outgoing.get(edge.dependencyId).add(edge.fabId);
            degree.put(edge.fabId, degree.get(edge.fabId) + 1);
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
                node.x = entry.getKey() * 265;
                node.y = row * 115;
            }
        }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(offsetX, offsetY); g.scale(scale, scale);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(new Color(174, 185, 197));
        for (Models.Dependency edge : edges) {
            Node from = nodes.get(edge.dependencyId), to = nodes.get(edge.fabId);
            if (from == null || to == null) continue;
            double x1 = from.x + NODE_W, y1 = from.y + NODE_H / 2.0, x2 = to.x, y2 = to.y + NODE_H / 2.0;
            double middle = (x1 + x2) / 2.0;
            Path2D path = new Path2D.Double(); path.moveTo(x1, y1); path.curveTo(middle, y1, middle, y2, x2 - 8, y2); g.draw(path);
            Path2D arrow = new Path2D.Double(); arrow.moveTo(x2, y2); arrow.lineTo(x2 - 10, y2 - 5); arrow.lineTo(x2 - 10, y2 + 5); arrow.closePath(); g.fill(arrow);
        }
        for (Node node : nodes.values()) drawNode(g, node);
        if (nodes.isEmpty()) {
            g.setColor(new Color(120, 130, 140)); g.setFont(getFont().deriveFont(14f)); g.drawString("暂无依赖关系", 25, 35);
        }
        g.dispose();
    }

    private void drawNode(Graphics2D g, Node node) {
        Color color = statusColor(node.status);
        g.setColor(Color.WHITE); g.fill(new RoundRectangle2D.Double(node.x, node.y, NODE_W, NODE_H, 14, 14));
        g.setColor(color); g.setStroke(new BasicStroke(node.status.isEmpty() ? 1.2f : 2f)); g.draw(new RoundRectangle2D.Double(node.x, node.y, NODE_W, NODE_H, 14, 14));
        g.fillOval(node.x + 13, node.y + 14, 8, 8);
        g.setFont(getFont().deriveFont(Font.BOLD, 13f)); g.setColor(new Color(27, 39, 52)); g.drawString(node.id, node.x + 28, node.y + 23);
        g.setFont(getFont().deriveFont(Font.BOLD, 11f)); g.setColor(color); g.drawString(node.status, node.x + 165, node.y + 23);
        g.setFont(getFont().deriveFont(10f)); g.setColor(new Color(113, 128, 142));
        String start = node.startedAt != null ? "开始 " + UiFormat.dateTime(node.startedAt) : node.status.isEmpty() ? "依赖节点" : "尚未捕获 I";
        String average = node.average > 0 ? "平均 " + UiFormat.duration(node.average) : "平均 --（不足 2 次）";
        g.drawString(clip(start, 28), node.x + 14, node.y + 49);
        g.drawString(clip(average, 28), node.x + 14, node.y + 69);
    }

    @Override public String getToolTipText(MouseEvent event) {
        double x = (event.getX() - offsetX) / scale, y = (event.getY() - offsetY) / scale;
        for (Node node : nodes.values()) if (x >= node.x && x <= node.x + NODE_W && y >= node.y && y <= node.y + NODE_H)
            return node.id + (node.description.isEmpty() ? "" : " - " + node.description);
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
        if ("I".equals(status)) return new Color(22, 93, 255);
        if ("R".equals(status)) return new Color(22, 143, 98);
        if ("E".equals(status)) return new Color(216, 59, 59);
        if ("B".equals(status)) return new Color(212, 123, 0);
        return new Color(140, 152, 164);
    }
    private static String clip(String value, int length) { return value == null ? "" : value.length() <= length ? value : value.substring(0, length - 1) + "…"; }
}
