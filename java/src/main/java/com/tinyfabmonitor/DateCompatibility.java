package com.tinyfabmonitor;

import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DateCompatibility {
    private static final Pattern ACT_TIME_TEXT = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2})[- T](\\d{2})[:.](\\d{2})[:.](\\d{2})(?:\\.(\\d{1,9}))?$"
    );
    private static final Pattern PLACEHOLDER_TEXT = Pattern.compile(
        "^(?:\\+?0000-|0001-01-01(?:[- T]00[:.]00[:.]00)(?:\\.0+)?(?:Z|[+-]\\d{2}:?\\d{2})?$)"
    );

    private DateCompatibility() {}

    static boolean isPlaceholder(Object value) {
        if (value == null) return false;
        if (value instanceof Date) {
            GregorianCalendar calendar = new GregorianCalendar();
            calendar.setTime((Date) value);
            return calendar.get(Calendar.ERA) == GregorianCalendar.BC || calendar.get(Calendar.YEAR) <= 1;
        }
        return isPlaceholderText(String.valueOf(value));
    }

    static boolean isPlaceholderText(String value) {
        String raw = value == null ? "" : value.trim();
        return PLACEHOLDER_TEXT.matcher(raw).find();
    }

    static Date parseActTime(Object value) throws SQLException {
        if (value == null || isPlaceholder(value)) return null;
        if (value instanceof Date) return new Date(((Date) value).getTime());
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        Matcher matcher = ACT_TIME_TEXT.matcher(raw);
        if (!matcher.matches()) {
            throw new SQLException("无法解析 ACT_TM：" + raw + "；支持 yyyy-MM-dd-HH.mm.ss.ffffff 格式");
        }
        String fraction = matcher.group(5) == null ? "000" : matcher.group(5);
        fraction = (fraction + "000").substring(0, 3);
        String normalized = matcher.group(1) + "-" + matcher.group(2) + "." + matcher.group(3) + "." + matcher.group(4) + "." + fraction;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS", Locale.ROOT);
        format.setLenient(false);
        try { return format.parse(normalized); }
        catch (ParseException e) { throw new SQLException("ACT_TM 日期无效：" + raw, e); }
    }
}
