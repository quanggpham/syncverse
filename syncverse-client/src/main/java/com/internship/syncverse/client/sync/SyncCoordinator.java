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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

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
    private LongSupplier reconciliationTarget;
    private LongConsumer reconciliationComplete;

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
        start(() -> state.lastSeenGlobalVersion(), ignored -> { });
    }

    public void start(long targetGlobalVersion, LongConsumer completion) throws Exception {
        start(() -> targetGlobalVersion, completion);
    }

    public void start(LongSupplier targetGlobalVersion, LongConsumer completion) throws Exception {
        if (serverApi == null || running) {
            throw new IllegalStateException("Sync coordinator cannot be started in this state");
        }
        this.reconciliationTarget = targetGlobalVersion;
        this.reconciliationComplete = completion;
        stateStore.save(state);
        watcher.start();
        running = true;
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

    private void reconcileSession(long targetGlobalVersion) throws Exception {
        syncExecutor.submit(() -> {
            retryPending();
            return null;
        }).get();
        Map<String, FileSnapshot> local = syncExecutor.submit(scanner::scan).get();
        ClientState base = state;
        List<FileRevision> revisions = fetchThrough(targetGlobalVersion);
        syncExecutor.submit(() -> {
            reconcile(base, local, revisions);
            return null;
        }).get();
    }

    private List<FileRevision> fetchThrough(long targetGlobalVersion) throws Exception {
        ArrayList<FileRevision> revisions = new ArrayList<>();
        long cursor = state.lastSeenGlobalVersion();
        while (cursor < targetGlobalVersion) {
            DeltaResponse response = serverApi.deltas(requireSession(), cursor);
            if (response.fromExclusive() != cursor || response.changes().isEmpty()) {
                throw new IllegalStateException("Server did not provide required reconciliation deltas");
            }
            for (FileRevision revision : response.changes()) {
                if (revision.globalVersion() <= cursor) {
                    throw new IllegalStateException("Reconciliation deltas are not increasing");
                }
                revisions.add(revision);
                cursor = revision.globalVersion();
            }
        }
        return List.copyOf(revisions);
    }

    private void reconcile(
            ClientState base,
            Map<String, FileSnapshot> local,
            List<FileRevision> revisions) throws Exception {
        HashMap<String, FileRevision> remote = new HashMap<>();
        base.manifest().forEach((filename, entry) ->
                remote.put(filename, manifestRevision(filename, entry)));
        long reconciledCursor = base.lastSeenGlobalVersion();
        for (FileRevision revision : revisions) {
            if (revision.globalVersion() <= reconciledCursor) {
                throw new IllegalArgumentException("Reconciliation revisions are not ordered");
            }
            remote.put(revision.filename(), revision);
            reconciledCursor = revision.globalVersion();
        }

        TreeSet<String> filenames = new TreeSet<>(base.manifest().keySet());
        filenames.addAll(local.keySet());
        filenames.addAll(remote.keySet());
        for (String filename : filenames) {
            FileManifestEntry baseEntry = base.manifest().get(filename);
            FileSnapshot snapshot = local.get(filename);
            FileRevision remoteRevision = remote.get(filename);
            ReconciliationAction action = Reconciler.reconcile(
                    baseEntry, snapshot, remoteRevision);
            switch (action.kind()) {
                case NO_OP -> recordRemote(remoteRevision);
                case UPLOAD_LOCAL -> uploadSnapshot(
                        filename, snapshot, baseEntry, action.fileVersion());
                case APPLY_REMOTE, APPLY_DELETE -> applyRemote(remoteRevision);
                case UPLOAD_CONFLICT -> {
                    uploadSnapshot(filename, snapshot, baseEntry, action.fileVersion());
                    applyRemote(remoteRevision);
                }
            }
        }
        ClientState reconciled = new ClientState(
                state.formatVersion(), state.clientName(), reconciledCursor,
                state.manifest(), state.pendingOperation());
        stateStore.save(reconciled);
        state = reconciled;
    }

    private void uploadSnapshot(
            String filename,
            FileSnapshot snapshot,
            FileManifestEntry base,
            long baseVersion) throws Exception {
        FileOperation operation;
        String checksum = null;
        String content = null;
        if (snapshot == null) {
            operation = FileOperation.DELETE;
        } else {
            operation = base == null || base.deleted()
                    ? FileOperation.CREATE
                    : FileOperation.UPDATE;
            checksum = snapshot.checksum();
            content = Base64.getEncoder().encodeToString(snapshot.content());
        }
        submitUpload(new PendingOperation(
                UUID.randomUUID(), filename, operation, baseVersion, checksum, content));
    }

    private void applyRemote(FileRevision revision) throws Exception {
        if (revision == null) {
            return;
        }
        state = revisionApplier.apply(state, revision);
    }

    private void recordRemote(FileRevision revision) throws Exception {
        if (revision == null) {
            return;
        }
        HashMap<String, FileManifestEntry> manifest = new HashMap<>(state.manifest());
        boolean deleted = revision.operation() == FileOperation.DELETE;
        manifest.put(revision.filename(), new FileManifestEntry(
                deleted ? null : revision.checksum(), revision.fileVersion(), deleted));
        ClientState recorded = new ClientState(
                state.formatVersion(), state.clientName(), state.lastSeenGlobalVersion(),
                manifest, state.pendingOperation());
        stateStore.save(recorded);
        state = recorded;
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
        UUID reconciledSession = null;
        while (running) {
            try {
                UUID currentSession = requireSession();
                if (!currentSession.equals(reconciledSession)) {
                    reconcileSession(reconciliationTarget.getAsLong());
                    reconciliationComplete.accept(state.lastSeenGlobalVersion());
                    reconciledSession = currentSession;
                    syncExecutor.submit(this::drainDirty);
                }
                DeltaResponse response = serverApi.deltas(
                        currentSession, state.lastSeenGlobalVersion());
                accept(response).get();
                syncExecutor.submit(this::drainDirty);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
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
