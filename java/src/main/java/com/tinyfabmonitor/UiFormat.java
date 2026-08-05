package com.tinyfabmonitor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class UiFormat {
    private UiFormat() {}
    static String dateTime(Date value) { return value == null ? "--" : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(value); }
    static String duration(long seconds) {
        seconds = Math.max(0, seconds);
        long days = seconds / 86400, hours = seconds % 86400 / 3600, minutes = seconds % 3600 / 60, secs = seconds % 60;
        return (days > 0 ? days + "天 " : "") + String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, secs);
    }
    static String anomalies(List<Date> values) {
        if (values == null || values.isEmpty()) return "—";
        StringBuilder result = new StringBuilder();
        for (Date value : values) { if (result.length() > 0) result.append("; "); result.append(dateTime(value)); }
        return result.toString();
    }
}
