package com.refinedmods.refinedstorage.api.autocrafting.lp;

import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class LpPerformanceHistoryRecorder {
    private static final String HEADER = String.join(",",
        "timestamp",
        "run_id",
        "kind",
        "scenario",
        "phase",
        "duration_nanos",
        "duration_millis",
        "preview_type",
        "tree_preview_type",
        "has_path",
        "solved",
        "ticks",
        "task_state"
    );

    private static final String RUN_ID = UUID.randomUUID().toString();
    private static final Path OUTPUT_PATH = resolveOutputPath();

    private LpPerformanceHistoryRecorder() {
    }

    static synchronized void recordPreview(
        final String scenario,
        final String phase,
        final long durationNanos,
        final PreviewType previewType,
        final PreviewType treePreviewType,
        final boolean hasPath
    ) {
        appendRow(
            "preview",
            scenario,
            phase,
            durationNanos,
            previewType,
            treePreviewType,
            hasPath,
            null,
            null,
            null
        );
    }

    static synchronized void recordExecution(
        final String scenario,
        final String phase,
        final long durationNanos,
        final int ticks,
        final TaskState taskState
    ) {
        appendRow(
            "execution",
            scenario,
            phase,
            durationNanos,
            null,
            null,
            null,
            null,
            ticks,
            taskState
        );
    }

    static synchronized void recordSection(
        final String scenario,
        final String phase,
        final long durationNanos,
        final boolean solved,
        final PreviewType previewType,
        final PreviewType treePreviewType
    ) {
        appendRow(
            "section",
            scenario,
            phase,
            durationNanos,
            previewType,
            treePreviewType,
            null,
            solved,
            null,
            null
        );
    }

    static Path outputPath() {
        return OUTPUT_PATH;
    }

    private static void appendRow(
        final String kind,
        final String scenario,
        final String phase,
        final long durationNanos,
        final PreviewType previewType,
        final PreviewType treePreviewType,
        final Boolean hasPath,
        final Boolean solved,
        final Integer ticks,
        final TaskState taskState
    ) {
        final String row = String.join(",",
            csv(Instant.now().toString()),
            csv(RUN_ID),
            csv(kind),
            csv(scenario),
            csv(phase),
            csv(Long.toString(durationNanos)),
            csv(Long.toString(durationNanos / 1_000_000L)),
            csv(previewType == null ? "" : previewType.name()),
            csv(treePreviewType == null ? "" : treePreviewType.name()),
            csv(hasPath == null ? "" : hasPath.toString()),
            csv(solved == null ? "" : solved.toString()),
            csv(ticks == null ? "" : ticks.toString()),
            csv(taskState == null ? "" : taskState.name())
        );

        try {
            ensureHeaderExists();
            Files.writeString(
                OUTPUT_PATH,
                row + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (final IOException e) {
            System.err.println("[LP-PERF][history] Could not write history row: " + e.getMessage());
        }
    }

    private static void ensureHeaderExists() throws IOException {
        if (Files.exists(OUTPUT_PATH)) {
            return;
        }
        final Path parent = OUTPUT_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
            OUTPUT_PATH,
            List.of(HEADER),
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static Path resolveOutputPath() {
        final String configured = System.getProperty("lp.perf.history.file", "").trim();
        if (!configured.isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get("performance-history", "lp-performance-history.csv").toAbsolutePath().normalize();
    }

    private static String csv(final String raw) {
        final String escaped = raw.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
