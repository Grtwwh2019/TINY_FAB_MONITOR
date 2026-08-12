package com.tinyfabmonitor;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

final class PersistentNodeTooltip {
    interface HitTester { boolean containsNode(Point point); }

    private final ToolTipManager manager = ToolTipManager.sharedInstance();
    private final HitTester hitTester;
    private boolean persistent;
    private int previousDismissDelay;

    private PersistentNodeTooltip(JComponent component, HitTester hitTester) {
        this.hitTester = hitTester;
        component.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent event) { update(hitTester.containsNode(event.getPoint()), event); }
        });
        component.addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent event) { update(false, event); }
        });
    }

    static PersistentNodeTooltip install(JComponent component, HitTester hitTester) {
        return new PersistentNodeTooltip(component, hitTester);
    }

    void update(boolean insideNode, MouseEvent event) {
        if (insideNode && !persistent) {
            previousDismissDelay = manager.getDismissDelay();
            manager.setDismissDelay(Integer.MAX_VALUE);
            persistent = true;
        } else if (!insideNode && persistent) {
            manager.setDismissDelay(previousDismissDelay);
            persistent = false;
            if (event != null) manager.mouseExited(event);
        }
    }

    boolean isPersistent() { return persistent; }
}
