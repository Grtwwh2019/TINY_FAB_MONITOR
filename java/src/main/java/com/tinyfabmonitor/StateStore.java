package com.tinyfabmonitor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.StdDateFormat;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StateStore {
    interface Updater { void update(Models.PersistedState state); }

    private final Path path;
    private final ObjectMapper mapper;
    private Models.PersistedState state;

    StateStore(Path path) throws IOException {
        this.path = path;
        mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        SimpleModule compatibility = new SimpleModule();
        compatibility.addDeserializer(Date.class, new CompatibleDateDeserializer());
        mapper.registerModule(compatibility);
        if (Files.isRegularFile(path)) {
            byte[] source = Files.readAllBytes(path);
            String json = new String(source, StandardCharsets.UTF_8);
            boolean containsPlaceholder = containsLegacyPlaceholder(json);
            state = mapper.readValue(source, Models.PersistedState.class);
            normalize(state);
            boolean changed = false;
            if (state.version < 2) {
                migrateV2(state);
                changed = true;
            }
            if (containsPlaceholder) {
                backupBeforePlaceholderRepair();
                changed = true;
            }
            if (changed) {
                persist(state);
            }
        } else {
            state = new Models.PersistedState();
        }
    }

    synchronized Models.PersistedState snapshot() {
        return copy(state);
    }

    synchronized void update(Updater updater) throws IOException {
        Models.PersistedState working = copy(state);
        updater.update(working);
        working.updatedAt = new Date();
        persist(working);
        state = working;
    }

    private void persist(Models.PersistedState value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Models.PersistedState copy(Models.PersistedState source) {
        return mapper.convertValue(source, Models.PersistedState.class);
    }

    private void backupBeforePlaceholderRepair() throws IOException {
        Path backup = path.resolveSibling(path.getFileName().toString() + ".before-placeholder-repair.bak");
        if (!Files.exists(backup)) Files.copy(path, backup);
    }

    private static boolean containsLegacyPlaceholder(String json) {
        return json.contains("+0000-") || json.contains("0001-01-01-00.00.00") || json.contains("0001-01-01T00:00:00");
    }

    private static class CompatibleDateDeserializer extends JsonDeserializer<Date> {
        private static final Pattern ISO_FRACTION = Pattern.compile("^(.*T\\d{2}:\\d{2}:\\d{2})\\.(\\d{1,9})(Z|[+-]\\d{2}:?\\d{2})$");
        private final StdDateFormat format = new StdDateFormat().withColonInTimeZone(true);

        @Override public Date deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NULL) return null;
            if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) return new Date(parser.getLongValue());
            String raw = parser.getValueAsString();
            if (raw == null || raw.trim().isEmpty() || DateCompatibility.isPlaceholderText(raw)) return null;
            raw = normalizeFraction(raw.trim());
            try { return format.parse(raw); }
            catch (ParseException e) { throw context.weirdStringException(raw, Date.class, "不支持的日期格式"); }
        }

        private static String normalizeFraction(String value) {
            Matcher matcher = ISO_FRACTION.matcher(value);
            if (!matcher.matches()) return value;
            String fraction = (matcher.group(2) + "000").substring(0, 3);
            return matcher.group(1) + "." + fraction + matcher.group(3);
        }
    }

    private static void normalize(Models.PersistedState value) {
        if (value.tracked == null) value.tracked = new LinkedHashMap<String, Models.TrackedTask>();
        if (value.runs == null) value.runs = new ArrayList<Models.RunRecord>();
        if (value.selectedProcessDate == null) value.selectedProcessDate = "";
        for (Models.RunRecord run : value.runs) {
            if (run.events == null) run.events = new ArrayList<Models.StateEvent>();
            if (run.anomalyTimes == null) run.anomalyTimes = new ArrayList<Date>();
        }
    }

    private static void migrateV2(Models.PersistedState value) {
        for (Models.RunRecord run : value.runs) {
            Date start = run.startedAt;
            boolean anomaly = false;
            for (Models.StateEvent event : run.events) {
                if ("E".equals(event.status)) anomaly = true;
                else if ("I".equals(event.status) && anomaly) {
                    start = event.at;
                    anomaly = false;
                }
            }
            run.startedAt = start;
            if (run.completedAt != null && start != null) {
                run.durationSeconds = Math.max(0L, (run.completedAt.getTime() - start.getTime()) / 1000L);
            }
        }
        value.version = 2;
    }
}
