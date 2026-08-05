package com.tinyfabmonitor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MainFrame extends JFrame implements MonitorService.Listener {
    private final MonitorService monitor;
    private final JTextField dateField = new JTextField(9);
    private final JLabel connection = new JLabel("● 正在连接");
    private final JLabel lastPoll = new JLabel("上次刷新：--");
    private final JLabel nextPoll = new JLabel("下次刷新：--");
    private final JLabel error = new JLabel(" ");
    private final JLabel runningMetric = metricValue();
    private final JLabel completedMetric = metricValue();
    private final JLabel anomalyMetric = metricValue();
    private final JLabel historyMetric = metricValue();
    private final TaskTableModel taskModel = new TaskTableModel();
    private final HistoryTableModel historyModel = new HistoryTableModel();
    private final JTable taskTable = table(taskModel);
    private final JTable historyTable = table(historyModel);
    private final TableRowSorter<TaskTableModel> taskSorter = new TableRowSorter<TaskTableModel>(taskModel);
    private final TableRowSorter<HistoryTableModel> historySorter = new TableRowSorter<HistoryTableModel>(historyModel);
    private final DagPanel dag = new DagPanel();
    private final JTextField taskFilterField = new JTextField();
    private final JTextField threadFilterField = new JTextField(9);
    private final JComboBox<String> statusFilter = new JComboBox<String>();
    private final JTextField levelMinFilter = new JTextField(4);
    private final JTextField levelMaxFilter = new JTextField(4);
    private final JLabel levelFilterError = new JLabel();
    private final JTextField dagThreadSearch = new JTextField(8);
    private final JTextField dagLevelSearch = new JTextField(6);
    private final JTextField dagFabSearch = new JTextField(10);
    private Models.Dashboard latestDashboard = new Models.Dashboard();
    private long centeredDagRequestId = -1;

    MainFrame(MonitorService monitor, Runnable onClose) {
        super("TINY FAB MONITOR - Oracle FAB 运行监控");
        this.monitor = monitor;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 680));
        setSize(1450, 850);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent event) {
                if (JOptionPane.showConfirmDialog(MainFrame.this, "确定退出监控程序？", "退出", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) onClose.run();
            }
        });
        setContentPane(buildContent());
        dag.setShowDagAction(monitor::loadDependencyDag);
        taskTable.setRowSorter(taskSorter);
        historyTable.setRowSorter(historySorter);
        taskTable.setDefaultRenderer(Object.class, new StatusRenderer());
        monitor.addListener(this);
        new Timer(1000, e -> render(monitor.dashboard())).start();
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(new Color(244, 246, 248));
        root.add(buildHeader(), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false); body.setBorder(new EmptyBorder(14, 20, 12, 20));
        body.add(buildOverview(), BorderLayout.NORTH);
        body.add(buildTabs(), BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(16, 28, 44)); header.setBorder(new EmptyBorder(15, 22, 15, 22));
        JLabel title = new JLabel("TINY FAB MONITOR   Oracle 调度状态与依赖分析");
        title.setForeground(Color.WHITE); title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        header.add(title, BorderLayout.WEST);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); controls.setOpaque(false);
        JLabel dateLabel = new JLabel("业务日期"); dateLabel.setForeground(new Color(185, 196, 210)); controls.add(dateLabel);
        dateField.setHorizontalAlignment(SwingConstants.CENTER); controls.add(dateField);
        JButton query = button("查询日期", new Color(22, 93, 255)); query.addActionListener(e -> changeDate()); controls.add(query);
        JButton refresh = button("立即刷新", new Color(54, 73, 96)); refresh.addActionListener(e -> monitor.refreshNow()); controls.add(refresh);
        JButton reset = button("重置 DAG", new Color(54, 73, 96)); reset.addActionListener(e -> dag.resetView()); controls.add(reset);
        header.add(controls, BorderLayout.EAST);
        dateField.addActionListener(e -> changeDate());
        return header;
    }

    private JPanel buildOverview() {
        JPanel container = new JPanel(new BorderLayout(0, 10)); container.setOpaque(false);
        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 0)); status.setOpaque(false);
        status.add(connection); status.add(lastPoll); status.add(nextPoll); status.add(error); container.add(status, BorderLayout.NORTH);
        JPanel cards = new JPanel(new GridLayout(1, 4, 12, 0)); cards.setOpaque(false);
        cards.add(metricCard("正在运行", "状态 I / E / B", runningMetric, new Color(22, 93, 255)));
        cards.add(metricCard("当日已完成", "已捕获 I → R", completedMetric, new Color(22, 143, 98)));
        cards.add(metricCard("异常任务", "捕获到状态 E", anomalyMetric, new Color(216, 59, 59)));
        cards.add(metricCard("历史运行记录", "重启后仍保留", historyMetric, new Color(122, 69, 201)));
        container.add(cards, BorderLayout.CENTER);
        return container;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("任务状态", taskTablePanel());
        JPanel dagContainer = new JPanel(new BorderLayout());
        JLabel note = new JLabel("箭头方向：依赖任务 → 后续任务；鼠标滚轮缩放，按住拖动画布。");
        JPanel dagTop = new JPanel(new BorderLayout(10, 0)); dagTop.setBorder(new EmptyBorder(8, 10, 8, 10)); dagTop.add(note, BorderLayout.WEST);
        JPanel search = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        search.add(new JLabel("Thread ID：")); search.add(dagThreadSearch);
        search.add(new JLabel("Level No：")); search.add(dagLevelSearch);
        search.add(new JLabel("FAB ID：")); search.add(dagFabSearch);
        JButton locate = new JButton("搜索定位"); locate.addActionListener(e -> locateDagTask()); search.add(locate);
        dagTop.add(search, BorderLayout.EAST); dagContainer.add(dagTop, BorderLayout.NORTH); dagContainer.add(dag, BorderLayout.CENTER); tabs.addTab("依赖 DAG", dagContainer);
        tabs.addTab("运行历史", tablePanel(historyTable, "筛选日期 / FAB / Thread", historySorter));
        return tabs;
    }

    private JPanel taskTablePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8)); panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        taskFilterField.setPreferredSize(new Dimension(280, 30));
        threadFilterField.setToolTipText("Thread ID 包含匹配");
        statusFilter.addItem("全部状态");
        levelMinFilter.setToolTipText("Level No 起始值（包含）");
        levelMaxFilter.setToolTipText("Level No 结束值（包含）");
        levelFilterError.setForeground(new Color(190, 35, 35));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        top.add(levelFilterError); top.add(new JLabel("Thread ID：")); top.add(threadFilterField);
        top.add(new JLabel("Level No：")); top.add(levelMinFilter); top.add(new JLabel("至")); top.add(levelMaxFilter);
        top.add(new JLabel("状态：")); top.add(statusFilter); top.add(new JLabel("任务筛选：")); top.add(taskFilterField); panel.add(top, BorderLayout.NORTH);
        Runnable update = this::applyTaskFilter;
        taskFilterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update.run(); } public void removeUpdate(DocumentEvent e) { update.run(); } public void changedUpdate(DocumentEvent e) { update.run(); }
        });
        threadFilterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update.run(); } public void removeUpdate(DocumentEvent e) { update.run(); } public void changedUpdate(DocumentEvent e) { update.run(); }
        });
        DocumentListener levelListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update.run(); } public void removeUpdate(DocumentEvent e) { update.run(); } public void changedUpdate(DocumentEvent e) { update.run(); }
        };
        levelMinFilter.getDocument().addDocumentListener(levelListener);
        levelMaxFilter.getDocument().addDocumentListener(levelListener);
        statusFilter.addActionListener(e -> update.run());
        panel.add(new JScrollPane(taskTable), BorderLayout.CENTER); return panel;
    }

    private void applyTaskFilter() {
        String text = taskFilterField.getText();
        final String wantedThread = threadFilterField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String status = String.valueOf(statusFilter.getSelectedItem());
        final String wantedText = text == null ? "" : text.trim().toLowerCase();
        final String wantedStatus = "全部状态".equals(status) ? "" : status;
        final Integer minLevel;
        final Integer maxLevel;
        try {
            minLevel = ViewLogic.parseLevelBound(levelMinFilter.getText());
            maxLevel = ViewLogic.parseLevelBound(levelMaxFilter.getText());
        } catch (IllegalArgumentException e) {
            levelFilterError.setText("Level 必须是整数");
            levelFilterError.setToolTipText(e.getMessage());
            return;
        }
        if (minLevel != null && maxLevel != null && minLevel > maxLevel) {
            levelFilterError.setText("起始值不能大于结束值");
            levelFilterError.setToolTipText("Level No 起始值必须小于或等于结束值");
            return;
        }
        levelFilterError.setText(""); levelFilterError.setToolTipText(null);
        if (wantedText.isEmpty() && wantedThread.isEmpty() && wantedStatus.isEmpty() && minLevel == null && maxLevel == null) { taskSorter.setRowFilter(null); return; }
        taskSorter.setRowFilter(new RowFilter<TaskTableModel, Integer>() {
            @Override public boolean include(Entry<? extends TaskTableModel, ? extends Integer> entry) {
                Models.TaskView task = taskModel.rowAt(entry.getIdentifier());
                if (!wantedStatus.isEmpty() && !wantedStatus.equalsIgnoreCase(task.status)) return false;
                if (!ViewLogic.threadContains(task.threadId, wantedThread)) return false;
                if (!ViewLogic.levelInRange(task.levelNo, minLevel, maxLevel)) return false;
                if (wantedText.isEmpty()) return true;
                for (int i = 0; i < entry.getValueCount(); i++) if (entry.getStringValue(i).toLowerCase().contains(wantedText)) return true;
                return false;
            }
        });
    }

    private void locateDagTask() {
        String thread = dagThreadSearch.getText().trim();
        String level = dagLevelSearch.getText().trim();
        String fab = dagFabSearch.getText().trim();
        if (thread.isEmpty() && level.isEmpty() && fab.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请至少输入一个搜索条件", "搜索定位", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Models.TaskView> matches = ViewLogic.findDagTasks(latestDashboard.tasks, thread, level, fab);
        if (matches.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前业务日期中找不到匹配任务", "未找到", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Models.TaskView target;
        if (matches.size() == 1) {
            target = matches.get(0);
        } else {
            TaskChoice[] choices = new TaskChoice[matches.size()];
            for (int i = 0; i < matches.size(); i++) choices[i] = new TaskChoice(matches.get(i));
            TaskChoice selected = (TaskChoice) JOptionPane.showInputDialog(this, "找到多个任务，请选择需要定位的任务：", "选择任务", JOptionPane.PLAIN_MESSAGE, null, choices, choices[0]);
            if (selected == null) return;
            target = selected.task;
        }
        monitor.loadDependencyDag(target.fabId);
    }

    private static class TaskChoice {
        final Models.TaskView task;
        TaskChoice(Models.TaskView task) { this.task = task; }
        @Override public String toString() { return "Thread " + task.threadId + " / Level " + task.levelNo + " / FAB " + task.fabId + " / 状态 " + task.status; }
    }

    private JPanel tablePanel(JTable table, String placeholder, TableRowSorter<?> sorter) {
        JPanel panel = new JPanel(new BorderLayout(0, 8)); panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextField filter = new JTextField(); filter.setToolTipText(placeholder); filter.setPreferredSize(new Dimension(300, 30));
        JLabel label = new JLabel(placeholder + "："); JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); top.add(label); top.add(filter); panel.add(top, BorderLayout.NORTH);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            private void update() { String value = filter.getText().trim(); sorter.setRowFilter(value.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(value))); }
            public void insertUpdate(DocumentEvent e) { update(); } public void removeUpdate(DocumentEvent e) { update(); } public void changedUpdate(DocumentEvent e) { update(); }
        });
        panel.add(new JScrollPane(table), BorderLayout.CENTER); return panel;
    }

    private void changeDate() {
        try { monitor.setProcessDate(dateField.getText().trim()); }
        catch (Exception e) { JOptionPane.showMessageDialog(this, e.getMessage(), "日期无效", JOptionPane.WARNING_MESSAGE); }
    }

    @Override public void dashboardChanged() { SwingUtilities.invokeLater(() -> render(monitor.dashboard())); }

    private void render(Models.Dashboard dashboard) {
        latestDashboard = dashboard;
        if (!dateField.hasFocus()) dateField.setText(dashboard.processDate);
        connection.setText(dashboard.polling ? "● 正在读取" : dashboard.connected ? "● Oracle 已连接" : dashboard.lastError.isEmpty() ? "● 等待连接" : "● Oracle 连接失败");
        connection.setForeground(dashboard.connected ? new Color(22, 143, 98) : dashboard.lastError.isEmpty() ? Color.DARK_GRAY : new Color(216, 59, 59));
        lastPoll.setText("上次刷新：" + UiFormat.dateTime(dashboard.lastPollAt)); nextPoll.setText("下次刷新：" + UiFormat.dateTime(dashboard.nextPollAt));
        error.setText(dashboard.lastError.isEmpty() ? "" : dashboard.lastError); error.setForeground(new Color(180, 45, 45)); error.setToolTipText(dashboard.lastError);
        int running = 0, completed = 0, anomalies = 0;
        for (Models.TaskView task : dashboard.tasks) {
            if (Arrays.asList("I", "E", "B").contains(task.status)) running++;
            if (task.completedAt != null || "R".equals(task.status)) completed++;
            if ("E".equals(task.status) || !task.anomalyTimes.isEmpty()) anomalies++;
        }
        runningMetric.setText(String.valueOf(running)); completedMetric.setText(String.valueOf(completed)); anomalyMetric.setText(String.valueOf(anomalies)); historyMetric.setText(String.valueOf(dashboard.totalHistoricalRuns));
        updateStatusOptions(dashboard.tasks);
        taskModel.setRows(dashboard.tasks); historyModel.setRows(dashboard.recentRuns); dag.setDashboard(dashboard);
        if (!dashboard.dagLoading && dashboard.dagError.isEmpty() && !dashboard.dagRootFabId.isEmpty() && centeredDagRequestId != dashboard.dagRequestId) {
            centeredDagRequestId = dashboard.dagRequestId;
            SwingUtilities.invokeLater(() -> dag.focusFabId(dashboard.dagRootFabId));
        }
    }

    private void updateStatusOptions(List<Models.TaskView> tasks) {
        String selected = String.valueOf(statusFilter.getSelectedItem());
        Set<String> found = new LinkedHashSet<String>();
        for (String standard : new String[]{"W", "I", "E", "B", "R"}) {
            for (Models.TaskView task : tasks) if (standard.equalsIgnoreCase(task.status)) { found.add(standard); break; }
        }
        List<String> other = new ArrayList<String>();
        for (Models.TaskView task : tasks) {
            String status = task.status == null ? "" : task.status.trim().toUpperCase(java.util.Locale.ROOT);
            if (!status.isEmpty() && !found.contains(status) && !Arrays.asList("W", "I", "E", "B", "R").contains(status)) other.add(status);
        }
        Collections.sort(other); found.addAll(other);
        List<String> current = new ArrayList<String>();
        for (int i = 1; i < statusFilter.getItemCount(); i++) current.add(statusFilter.getItemAt(i));
        if (current.equals(new ArrayList<String>(found))) return;
        statusFilter.removeAllItems(); statusFilter.addItem("全部状态");
        for (String status : found) statusFilter.addItem(status);
        boolean restored = false;
        for (int i = 0; i < statusFilter.getItemCount(); i++) if (statusFilter.getItemAt(i).equals(selected)) { statusFilter.setSelectedIndex(i); restored = true; break; }
        if (!restored) statusFilter.setSelectedIndex(0);
    }

    private static JPanel metricCard(String title, String note, JLabel value, Color accent) {
        JPanel card = new JPanel(new BorderLayout()); card.setBackground(Color.WHITE); card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, accent), new EmptyBorder(12, 16, 10, 14)));
        JLabel titleLabel = new JLabel(title); titleLabel.setForeground(new Color(101, 113, 126)); JLabel noteLabel = new JLabel(note); noteLabel.setForeground(new Color(138, 148, 158)); noteLabel.setFont(noteLabel.getFont().deriveFont(11f));
        card.add(titleLabel, BorderLayout.NORTH); card.add(value, BorderLayout.CENTER); card.add(noteLabel, BorderLayout.SOUTH); return card;
    }
    private static JLabel metricValue() { JLabel label = new JLabel("0"); label.setFont(label.getFont().deriveFont(Font.BOLD, 28f)); return label; }
    private static JButton button(String title, Color color) { JButton button = new JButton(title); button.setForeground(Color.WHITE); button.setBackground(color); button.setFocusPainted(false); return button; }
    private static JTable table(AbstractTableModel model) {
        JTable table = new JTable(model); table.setRowHeight(42); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); table.setFillsViewportHeight(true);
        int[] widths = model instanceof TaskTableModel ? new int[]{135, 220, 210, 65, 135, 135, 135, 155, 240} : new int[]{95, 145, 145, 135, 135, 125, 260};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        return table;
    }

    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            java.awt.Component component = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected && column == 3) {
                String status = String.valueOf(value); component.setForeground("E".equals(status) ? new Color(190, 35, 35) : "I".equals(status) ? new Color(22, 93, 255) : "R".equals(status) ? new Color(10, 125, 82) : Color.DARK_GRAY);
                component.setFont(component.getFont().deriveFont(Font.BOLD));
            } else if (!selected) component.setForeground(Color.DARK_GRAY);
            return component;
        }
    }

    private static class TaskTableModel extends AbstractTableModel {
        private final String[] columns = {"FAB ID", "FAB 描述", "Thread / Level", "状态", "状态开始时间", "I 开始时间", "当前 / 本次时长", "历史平均", "异常时间"};
        private List<Models.TaskView> rows = new ArrayList<Models.TaskView>();
        void setRows(List<Models.TaskView> rows) { this.rows = new ArrayList<Models.TaskView>(rows); fireTableDataChanged(); }
        Models.TaskView rowAt(int row) { return rows.get(row); }
        public int getRowCount() { return rows.size(); } public int getColumnCount() { return columns.length; } public String getColumnName(int c) { return columns[c]; }
        public Object getValueAt(int row, int column) {
            Models.TaskView t = rows.get(row);
            switch (column) {
                case 0: return t.fabId;
                case 1: return t.fabDescription;
                case 2: return t.threadId + " / " + t.levelNo + (t.levelDescription.isEmpty() ? "" : "  " + t.levelDescription);
                case 3: return t.status;
                case 4: return t.actTimePlaceholder ? "无有效时间" : UiFormat.dateTime(t.actTime);
                case 5: return UiFormat.dateTime(t.startedAt);
                case 6: return t.completedAt != null ? UiFormat.duration(t.lastDurationSeconds) : t.startedAt != null ? UiFormat.duration(t.currentDurationSeconds) : "--";
                case 7: return t.completedRunCount >= 2 ? UiFormat.duration(t.averageDurationSeconds) + "  (" + t.completedRunCount + "次)" : "--  (" + t.completedRunCount + "次)";
                default: return UiFormat.anomalies(t.anomalyTimes);
            }
        }
    }

    private static class HistoryTableModel extends AbstractTableModel {
        private final String[] columns = {"业务日期", "FAB", "Thread / Level", "I 时间", "R 时间", "持续时长", "异常时间点"};
        private List<Models.RunRecord> rows = new ArrayList<Models.RunRecord>();
        void setRows(List<Models.RunRecord> rows) { this.rows = new ArrayList<Models.RunRecord>(rows); fireTableDataChanged(); }
        public int getRowCount() { return rows.size(); } public int getColumnCount() { return columns.length; } public String getColumnName(int c) { return columns[c]; }
        public Object getValueAt(int row, int column) {
            Models.RunRecord r = rows.get(row);
            switch (column) {
                case 0: return r.task.processDate; case 1: return r.task.fabId; case 2: return r.task.threadId + " / " + r.task.levelNo;
                case 3: return UiFormat.dateTime(r.startedAt); case 4: return UiFormat.dateTime(r.completedAt);
                case 5: return r.startedAt == null ? "仅异常记录" : r.completedAt == null ? "运行中" : UiFormat.duration(r.durationSeconds); default: return UiFormat.anomalies(r.anomalyTimes);
            }
        }
    }
}
