package com.tinyfabmonitor;

import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class TableCellClipboard {
    private static final String COPY_CELL = "tinyFabCopyCell";

    private TableCellClipboard() {}

    static void install(final JTable table) {
        table.setCellSelectionEnabled(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getInputMap(JTable.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), COPY_CELL);
        table.getActionMap().put(COPY_CELL, new AbstractAction() {
            public void actionPerformed(ActionEvent event) { copySelectedCell(table); }
        });
        table.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent event) { showMenu(event); }
            public void mouseReleased(MouseEvent event) { showMenu(event); }
            private void showMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int row = table.rowAtPoint(event.getPoint()), column = table.columnAtPoint(event.getPoint());
                if (row < 0 || column < 0) return;
                table.changeSelection(row, column, false, false);
                JPopupMenu menu = new JPopupMenu();
                JMenuItem copy = new JMenuItem("复制单元格");
                copy.addActionListener(e -> copySelectedCell(table));
                menu.add(copy); menu.show(table, event.getX(), event.getY());
            }
        });
    }

    static String selectedCellText(JTable table) {
        int row = table.getSelectedRow(), column = table.getSelectedColumn();
        if (row < 0 || column < 0) return null;
        Object value = table.getValueAt(row, column);
        return value == null ? "" : String.valueOf(value);
    }

    private static void copySelectedCell(JTable table) {
        String value = selectedCellText(table);
        if (value == null) { Toolkit.getDefaultToolkit().beep(); return; }
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null); }
        catch (HeadlessException | IllegalStateException | SecurityException e) { Toolkit.getDefaultToolkit().beep(); }
    }
}
