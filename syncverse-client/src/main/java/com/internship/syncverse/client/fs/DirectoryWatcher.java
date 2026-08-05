package com.internship.syncverse.client.fs;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DirectoryWatcher implements AutoCloseable {

    private static final Duration DEBOUNCE = Duration.ofMillis(300);

    private final Path workspace;
    private final Consumer<String> fileChanged;
    private final Runnable fullRescan;
    private final DebounceScheduler scheduler;
    private final Map<String, Cancellable> pending = new HashMap<>();
    private final Map<String, Long> generations = new HashMap<>();

    private WatchService watchService;
    private Thread watchThread;

    public DirectoryWatcher(
            Path workspace, Consumer<String> fileChanged, Runnable fullRescan) {
        this(workspace, fileChanged, fullRescan, new ExecutorDebounceScheduler());
    }

    DirectoryWatcher(
            Path workspace,
            Consumer<String> fileChanged,
            Runnable fullRescan,
            DebounceScheduler scheduler) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.fileChanged = fileChanged;
        this.fullRescan = fullRescan;
        this.scheduler = scheduler;
    }

    public synchronized void start() throws IOException {
        if (watchService != null) {
            throw new IllegalStateException("Directory watcher has already started");
        }
        watchService = FileSystems.getDefault().newWatchService();
        workspace.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        watchThread = new Thread(this::watchLoop, "syncverse-file-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    synchronized void accept(WatchEvent.Kind<?> kind, Path context) {
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            scheduler.schedule(fullRescan, Duration.ZERO);
            return;
        }
        if (context == null || context.getNameCount() != 1) {
            return;
        }
        String filename = context.toString();
        long generation = generations.merge(filename, 1L, Long::sum);
        Cancellable previous = pending.remove(filename);
        if (previous != null) {
            previous.cancel();
        }
        pending.put(filename, scheduler.schedule(
                () -> deliver(filename, generation), DEBOUNCE));
    }

    private void deliver(String filename, long generation) {
        synchronized (this) {
            if (generations.getOrDefault(filename, 0L) != generation) {
                return;
            }
            pending.remove(filename);
            generations.remove(filename);
        }
        fileChanged.accept(filename);
    }

    private void watchLoop() {
        try {
            while (true) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path context = event.context() instanceof Path path ? path : null;
                    accept(event.kind(), context);
                }
                if (!key.reset()) {
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
            // Normal shutdown.
        }
    }

    @Override
    public synchronized void close() throws IOException {
        pending.values().forEach(Cancellable::cancel);
        pending.clear();
        if (watchService != null) {
            watchService.close();
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (scheduler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IOException("Cannot close watcher scheduler", exception);
            }
        }
    }

    private static final class ExecutorDebounceScheduler
            implements DebounceScheduler, AutoCloseable {

        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor();

        @Override
        public Cancellable schedule(Runnable action, Duration delay) {
            var future = executor.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}

@FunctionalInterface
interface DebounceScheduler {
    Cancellable schedule(Runnable action, Duration delay);
}

@FunctionalInterface
interface Cancellable {
    void cancel();
}
