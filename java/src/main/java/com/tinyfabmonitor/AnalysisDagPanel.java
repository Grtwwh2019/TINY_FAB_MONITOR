package com.tinyfabmonitor;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

final class AnalysisDagPanel extends JPanel {
    private static final int WIDTH = 205, HEIGHT = 62;
    private final Map<String, Node> nodes = new LinkedHashMap<String, Node>();
    private final List<Models.Dependency> edges = new ArrayList<Models.Dependency>();
    private Models.AnalysisResult latest = new Models.AnalysisResult();
    private boolean criticalOnly = true;

    private static class Node {
        Models.AnalysisTaskMetric metric;
        int x, y;
    }

    AnalysisDagPanel() {
        setBackground(new Color(249, 251, 253));
        setPreferredSize(new Dimension(1000, 260));
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    void setCriticalOnly(boolean value) { criticalOnly = value; rebuild(); }
    void setResult(Models.AnalysisResult value) { latest = value == null ? new Models.AnalysisResult() : value; rebuild(); }

    private void rebuild() {
        nodes.clear(); edges.clear();
        for (Models.AnalysisTaskMetric metric : latest.rows) {
            if (criticalOnly && !metric.criticalPath) continue;
            Node node = new Node(); node.metric = metric; nodes.put(normalize(metric.fabId), node);
        }
        for (Models.Dependency edge : latest.dependencies) {
            if (nodes.containsKey(normalize(edge.fabId)) && nodes.containsKey(normalize(edge.dependencyId))) edges.add(edge);
        }
        layoutGraph(); repaint();
    }

    private void layoutGraph() {
        Map<String, Integer> indegree = new HashMap<String, Integer>();
        Map<String, List<String>> outgoing = new HashMap<String, List<String>>();
        Map<String, Integer> level = new HashMap<String, Integer>();
        for (String id : nodes.keySet()) { indegree.put(id, 0); outgoing.put(id, new ArrayList<String>()); }
        for (Models.Dependency edge : edges) {
            String from = normalize(edge.dependencyId), to = normalize(edge.fabId);
            outgoing.get(from).add(to); indegree.put(to, indegree.get(to) + 1);
        }
        Queue<String> queue = new ArrayDeque<String>();
        for (String id : nodes.keySet()) if (indegree.get(id) == 0) { queue.add(id); level.put(id, 0); }
        while (!queue.isEmpty()) {
            String id = queue.remove();
            for (String next : outgoing.get(id)) {
                level.put(next, Math.max(level.containsKey(next) ? level.get(next) : 0, level.get(id) + 1));
                indegree.put(next, indegree.get(next) - 1); if (indegree.get(next) == 0) queue.add(next);
            }
        }
        Map<Integer, List<String>> columns = new HashMap<Integer, List<String>>();
        for (String id : nodes.keySet()) {
            int column = level.containsKey(id) ? level.get(id) : 0;
            if (!columns.containsKey(column)) columns.put(column, new ArrayList<String>());
            columns.get(column).add(id);
        }
        int maximumColumn = 0, maximumRows = 1;
        for (Map.Entry<Integer, List<String>> column : columns.entrySet()) {
            Collections.sort(column.getValue()); maximumColumn = Math.max(maximumColumn, column.getKey()); maximumRows = Math.max(maximumRows, column.getValue().size());
            for (int row = 0; row < column.getValue().size(); row++) {
                Node node = nodes.get(column.getValue().get(row)); node.x = 25 + column.getKey() * 255; node.y = 20 + row * 92;
            }
        }
        setPreferredSize(new Dimension(Math.max(1000, 60 + (maximumColumn + 1) * 255), Math.max(260, 55 + maximumRows * 92)));
        revalidate();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (nodes.isEmpty()) { g.setColor(Color.GRAY); g.drawString("完成分析后在此显示差异 DAG", 25, 35); g.dispose(); return; }
        g.setColor(new Color(155, 168, 181)); g.setStroke(new BasicStroke(1.4f));
        for (Models.Dependency edge : edges) {
            Node from = nodes.get(normalize(edge.dependencyId)), to = nodes.get(normalize(edge.fabId)); if (from == null || to == null) continue;
            double x1 = from.x + WIDTH, y1 = from.y + HEIGHT / 2.0, x2 = to.x, y2 = to.y + HEIGHT / 2.0, middle = (x1 + x2) / 2.0;
            Path2D line = new Path2D.Double(); line.moveTo(x1, y1); line.curveTo(middle, y1, middle, y2, x2 - 8, y2); g.draw(line);
            Path2D arrow = new Path2D.Double(); arrow.moveTo(x2, y2); arrow.lineTo(x2 - 10, y2 - 5); arrow.lineTo(x2 - 10, y2 + 5); arrow.closePath(); g.fill(arrow);
        }
        for (Node node : nodes.values()) drawNode(g, node);
        g.dispose();
    }

    private void drawNode(Graphics2D g, Node node) {
        Models.AnalysisTaskMetric metric = node.metric; Color border = color(metric);
        g.setColor(fill(border)); g.fill(new RoundRectangle2D.Double(node.x, node.y, WIDTH, HEIGHT, 14, 14));
        g.setColor(border); g.setStroke(new BasicStroke(metric.criticalPath ? 3f : 1.5f)); g.draw(new RoundRectangle2D.Double(node.x, node.y, WIDTH, HEIGHT, 14, 14));
        g.setFont(getFont().deriveFont(Font.BOLD, 12f)); g.setColor(new Color(30, 42, 55)); g.drawString(clip(metric.fabId, 24), node.x + 12, node.y + 22);
        g.setFont(getFont().deriveFont(11f)); g.setColor(border);
        String delay = metric.completionDelaySeconds == null ? "无完成时间对比" : "完成偏移 " + signed(metric.completionDelaySeconds);
        g.drawString(delay, node.x + 12, node.y + 42);
        g.setColor(new Color(88, 101, 115)); g.drawString(clip(metric.reason, 25), node.x + 12, node.y + 57);
    }

    @Override public String getToolTipText(MouseEvent event) {
        for (Node node : nodes.values()) if (event.getX() >= node.x && event.getX() <= node.x + WIDTH && event.getY() >= node.y && event.getY() <= node.y + HEIGHT) {
            Models.AnalysisTaskMetric m = node.metric;
            return "<html><b>FAB：</b>" + html(m.fabId) + "<br><b>描述：</b>" + html(m.fabDescription) +
                "<br><b>分析精度：</b>" + html(m.confidence) + "<br><b>原因：</b>" + html(m.reason) +
                "<br><b>执行差：</b>" + nullableSigned(m.executionDeltaSeconds) + "<br><b>等待差：</b>" + nullableSigned(m.waitDeltaSeconds) +
                "<br><b>完成偏移差：</b>" + nullableSigned(m.completionDelaySeconds) + "</html>";
        }
        return null;
    }

    private static Color color(Models.AnalysisTaskMetric m) {
        if (m.delayContributionSeconds > 0) return new Color(205, 45, 45);
        if (m.completionDelaySeconds != null && m.completionDelaySeconds > 0) return new Color(217, 123, 0);
        if ("数据不足".equals(m.confidence)) return new Color(112, 122, 132);
        return new Color(22, 143, 98);
    }
    private static Color fill(Color value) { return new Color(Math.min(255, value.getRed() + 225) / 2 + 120, Math.min(255, value.getGreen() + 225) / 2 + 120, Math.min(255, value.getBlue() + 225) / 2 + 120); }
    private static String nullableSigned(Long value) { return value == null ? "--" : signed(value); }
    private static String signed(long seconds) { return (seconds >= 0 ? "+" : "-") + UiFormat.duration(Math.abs(seconds)); }
    private static String clip(String value, int length) { if (value == null) return ""; return value.length() <= length ? value : value.substring(0, length - 1) + "…"; }
    private static String html(String value) { return value == null || value.isEmpty() ? "--" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
}
