package com.internship.syncverse.client.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectoryWatcherTest {

    @TempDir
    Path workspace;

    @Test
    void repeatedSameNameEventsDebounceToOneFinalCallback() {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> changed = new ArrayList<>();
        DirectoryWatcher watcher = new DirectoryWatcher(
                workspace, changed::add, () -> changed.add("FULL_RESCAN"), scheduler);

        watcher.accept(StandardWatchEventKinds.ENTRY_CREATE, Path.of("note.txt"));
        watcher.accept(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("note.txt"));
        watcher.accept(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("note.txt"));
        scheduler.runPending();

        assertEquals(List.of("note.txt"), changed);
        assertEquals(List.of(
                Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300)),
                scheduler.delays);
    }

    @Test
    void overflowRequestsOneFullRescanAndNestedPathsAreIgnored() {
        ManualScheduler scheduler = new ManualScheduler();
        List<String> changed = new ArrayList<>();
        DirectoryWatcher watcher = new DirectoryWatcher(
                workspace, changed::add, () -> changed.add("FULL_RESCAN"), scheduler);

        watcher.accept(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("child", "nested.txt"));
        watcher.accept(StandardWatchEventKinds.ENTRY_CREATE, Path.of(".syncverse-123.tmp"));
        watcher.accept(StandardWatchEventKinds.OVERFLOW, null);
        scheduler.runPending();

        assertEquals(List.of(".syncverse-123.tmp", "FULL_RESCAN"), changed);
    }

    private static final class ManualScheduler implements DebounceScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable action, Duration delay) {
            ManualTask task = new ManualTask(action);
            tasks.add(task);
            delays.add(delay);
            return task;
        }

        void runPending() {
            List.copyOf(tasks).forEach(ManualTask::run);
        }
    }

    private static final class ManualTask implements Cancellable {
        private final Runnable action;
        private boolean cancelled;

        private ManualTask(Runnable action) {
            this.action = action;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        void run() {
            if (!cancelled) {
                action.run();
            }
        }
    }
}
