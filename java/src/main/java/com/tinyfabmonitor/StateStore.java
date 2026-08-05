package com.tinyfabmonitor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;

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
        if (Files.isRegularFile(path)) {
            state = mapper.readValue(path.toFile(), Models.PersistedState.class);
            normalize(state);
            if (state.version < 2) {
                migrateV2(state);
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
