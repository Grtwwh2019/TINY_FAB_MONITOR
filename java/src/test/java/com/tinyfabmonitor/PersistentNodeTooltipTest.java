package com.tinyfabmonitor;

import org.junit.Test;

import javax.swing.JPanel;
import javax.swing.ToolTipManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PersistentNodeTooltipTest {
    @Test public void tooltipDismissDelayIsInfiniteOnlyWhilePointerIsInsideNode() {
        ToolTipManager manager = ToolTipManager.sharedInstance();
        int original = manager.getDismissDelay();
        PersistentNodeTooltip controller = PersistentNodeTooltip.install(new JPanel(), point -> true);
        try {
            controller.update(true, null);
            assertTrue(controller.isPersistent());
            assertEquals(Integer.MAX_VALUE, manager.getDismissDelay());
            controller.update(false, null);
            assertFalse(controller.isPersistent());
            assertEquals(original, manager.getDismissDelay());
        } finally {
            controller.update(false, null);
            manager.setDismissDelay(original);
        }
    }
}
