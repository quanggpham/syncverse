package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.fs.DirectoryScanner;
import com.internship.syncverse.client.fs.DirectoryWatcher;
import com.internship.syncverse.client.fs.FileSnapshot;
import com.internship.syncverse.client.fs.RemoteFileApplier;
import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.client.state.PendingOperation;
import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.common.protocol.FileOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class SyncCoordinator implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncCoordinator.class);

    private final AtomicClientStateStore stateStore;
    private final RevisionApplier revisionApplier;
    private final ExecutorService syncExecutor;
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final Map<String, String> permanentRejections = new ConcurrentHashMap<>();
    private volatile ClientState state;
    private ServerApiClient serverApi;
    private Supplier<UUID> sessionId;
    private DirectoryScanner scanner;
    private UploadService uploads;
    private DirectoryWatcher watcher;
    private Thread pollThread;
    private volatile boolean running;

    public SyncCoordinator(
            Path workspace,
            ServerApiClient serverApi,
            Supplier<UUID> sessionId,
            AtomicClientStateStore stateStore,
            ClientState initialState) {
        this(initialState, stateStore,
                new RemoteFileApplier(workspace, stateStore)::apply,
                Executors.newSingleThreadExecutor());
        this.serverApi = serverApi;
        this.sessionId = sessionId;
        this.scanner = new DirectoryScanner(workspace);
        this.uploads = new UploadService(serverApi, stateStore);
        this.watcher = new DirectoryWatcher(
                workspace, this::enqueueFilename, this::enqueueFullRescan);
    }

    SyncCoordinator(
            ClientState initialState,
            AtomicClientStateStore stateStore,
            RevisionApplier revisionApplier,
            ExecutorService syncExecutor) {
        this.state = initialState;
        this.stateStore = stateStore;
        this.revisionApplier = revisionApplier;
        this.syncExecutor = syncExecutor;
    }

    public Future<ClientState> accept(DeltaResponse response) {
        return syncExecutor.submit(() -> apply(response));
    }

    public void start() throws Exception {
        if (serverApi == null || running) {
            throw new IllegalStateException("Sync coordinator cannot be started in this state");
        }
        stateStore.save(state);
        watcher.start();
        running = true;
        enqueueFullRescan();
        pollThread = new Thread(this::pollLoop, "syncverse-delta-poller");
        pollThread.start();
    }

    public ClientState state() {
        return state;
    }

    private ClientState apply(DeltaResponse response) throws Exception {
        ClientState current = state;
        if (response.fromExclusive() != current.lastSeenGlobalVersion()) {
            throw new IllegalArgumentException("Delta response does not start at current cursor");
        }
        for (FileRevision revision : response.changes()) {
            if (revision.globalVersion() <= current.lastSeenGlobalVersion()) {
                throw new IllegalArgumentException("Delta revisions are not strictly increasing");
            }
            if (!alreadyApplied(current, revision)) {
                current = revisionApplier.apply(current, revision);
            }
            ClientState advanced = new ClientState(
                    current.formatVersion(),
                    current.clientName(),
                    revision.globalVersion(),
                    current.manifest(),
                    current.pendingOperation());
            stateStore.save(advanced);
            state = advanced;
            current = advanced;
        }
        return current;
    }

    private void reconcileAllLocalFiles() throws Exception {
        Map<String, FileSnapshot> snapshots = scanner.scan();
        TreeSet<String> filenames = new TreeSet<>(state.manifest().keySet());
        filenames.addAll(snapshots.keySet());
        dirty.addAll(filenames);
        drainDirty();
    }

    private void retryPending() throws Exception {
        PendingOperation pending = state.pendingOperation();
        if (pending != null) {
            submitUpload(pending);
        }
    }

    private void handleLocal(String filename, FileSnapshot knownSnapshot) throws Exception {
        retryPending();
        FileSnapshot snapshot = knownSnapshot;
        if (snapshot == null) {
            snapshot = scanner.snapshot(filename).orElse(null);
        }
        FileManifestEntry entry = state.manifest().get(filename);
        ReconciliationAction decision = Reconciler.reconcile(
                entry, snapshot, manifestRevision(filename, entry));
        if (decision.kind() == ReconciliationAction.Kind.NO_OP) {
            return;
        }
        if (decision.kind() != ReconciliationAction.Kind.UPLOAD_LOCAL) {
            throw new IllegalStateException(
                    "Live local reconciliation produced " + decision.kind());
        }

        FileOperation operation;
        String checksum = null;
        String content = null;
        if (snapshot == null) {
            operation = FileOperation.DELETE;
        } else {
            operation = entry == null || entry.deleted()
                    ? FileOperation.CREATE
                    : FileOperation.UPDATE;
            checksum = snapshot.checksum();
            content = Base64.getEncoder().encodeToString(snapshot.content());
        }
        long baseVersion = decision.fileVersion();
        String fingerprint = fingerprint(operation, checksum);
        if (Objects.equals(permanentRejections.get(filename), fingerprint)) {
            return;
        }
        permanentRejections.remove(filename);
        submitUpload(new PendingOperation(
                UUID.randomUUID(), filename, operation, baseVersion, checksum, content));
    }

    private void submitUpload(PendingOperation operation) throws Exception {
        try {
            state = uploads.submit(requireSession(), state, operation);
            permanentRejections.remove(operation.filename());
        } catch (ServerApiException exception) {
            state = stateStore.load().orElse(state);
            if (exception.kind() == ServerApiException.Kind.PERMANENT) {
                permanentRejections.put(operation.filename(),
                        fingerprint(operation.operation(), operation.checksum()));
                LOGGER.warn("Server permanently rejected local file {}", operation.filename(), exception);
                return;
            }
            dirty.add(operation.filename());
            throw exception;
        } catch (Exception exception) {
            state = stateStore.load().orElse(state);
            dirty.add(operation.filename());
            throw exception;
        }
    }

    private UUID requireSession() {
        UUID currentSession = sessionId.get();
        if (currentSession == null) {
            throw new IllegalStateException("No active server session");
        }
        return currentSession;
    }

    private void enqueueFilename(String filename) {
        permanentRejections.remove(filename);
        dirty.add(filename);
        syncExecutor.submit(this::drainDirty);
    }

    private void enqueueFullRescan() {
        syncExecutor.submit(() -> {
            try {
                reconcileAllLocalFiles();
            } catch (Exception exception) {
                LOGGER.warn("Full filesystem reconciliation failed", exception);
            }
        });
    }

    private void drainDirty() {
        for (String filename : Set.copyOf(dirty)) {
            if (!dirty.remove(filename)) {
                continue;
            }
            try {
                handleLocal(filename, null);
            } catch (Exception exception) {
                dirty.add(filename);
                LOGGER.warn("Local file synchronization failed for {}", filename, exception);
                return;
            }
        }
    }

    private void pollLoop() {
        while (running) {
            try {
                DeltaResponse response = serverApi.deltas(
                        requireSession(), state.lastSeenGlobalVersion());
                accept(response).get();
                syncExecutor.submit(this::drainDirty);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | RuntimeException
                     | com.internship.syncverse.client.http.ServerApiException exception) {
                if (running) {
                    LOGGER.warn("Delta polling failed; retrying", exception);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static boolean alreadyApplied(ClientState state, FileRevision revision) {
        FileManifestEntry entry = state.manifest().get(revision.filename());
        boolean deleted = revision.operation() == FileOperation.DELETE;
        return entry != null
                && entry.fileVersion() == revision.fileVersion()
                && entry.deleted() == deleted
                && Objects.equals(entry.checksum(), revision.checksum());
    }

    private static String fingerprint(FileOperation operation, String checksum) {
        return operation.name() + ':' + Objects.toString(checksum, "");
    }

    private static FileRevision manifestRevision(
            String filename, FileManifestEntry entry) {
        if (entry == null) {
            return null;
        }
        return new FileRevision(
                entry.fileVersion(),
                filename,
                entry.deleted() ? FileOperation.DELETE : FileOperation.UPDATE,
                entry.fileVersion(),
                entry.checksum(),
                0,
                null);
    }

    @Override
    public void close() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException exception) {
                LOGGER.warn("Cannot close directory watcher", exception);
            }
        }
        syncExecutor.shutdown();
        try {
            if (!syncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                syncExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            syncExecutor.shutdownNow();
        }
    }
}

@FunctionalInterface
interface RevisionApplier {
    ClientState apply(ClientState state, FileRevision revision) throws Exception;
}
