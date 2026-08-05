package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.fs.FileSnapshot;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.common.protocol.FileOperation;

import java.util.Objects;

import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.APPLY_DELETE;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.APPLY_REMOTE;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.NO_OP;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.UPLOAD_CONFLICT;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.UPLOAD_LOCAL;

public final class Reconciler {

    private Reconciler() {
    }

    public static ReconciliationAction reconcile(
            FileManifestEntry base, FileSnapshot local, FileRevision remote) {
        State baseState = State.from(base);
        State localState = State.from(local);
        State remoteState = State.from(remote);
        long baseVersion = base == null ? 0 : base.fileVersion();
        long remoteVersion = remote == null ? baseVersion : remote.fileVersion();

        if (localState.equals(remoteState)) {
            return action(NO_OP, remoteState.checksum(), remoteVersion);
        }

        boolean localChanged = !localState.equals(baseState);
        boolean remoteChanged = !remoteState.equals(baseState);
        if (localChanged && !remoteChanged) {
            return action(UPLOAD_LOCAL, localState.checksum(), baseVersion);
        }
        if (!localChanged && remoteChanged) {
            return remoteState.present()
                    ? action(APPLY_REMOTE, remoteState.checksum(), remoteVersion)
                    : action(APPLY_DELETE, null, remoteVersion);
        }
        if (!localChanged) {
            return action(NO_OP, baseState.checksum(), baseVersion);
        }
        if (!localState.present() && remoteState.present()) {
            return action(APPLY_REMOTE, remoteState.checksum(), remoteVersion);
        }
        return action(UPLOAD_CONFLICT, localState.checksum(), baseVersion);
    }

    private static ReconciliationAction action(
            ReconciliationAction.Kind kind, String checksum, long version) {
        return new ReconciliationAction(kind, checksum, version);
    }

    private record State(boolean present, String checksum) {

        private static State from(FileManifestEntry entry) {
            return entry == null || entry.deleted()
                    ? new State(false, null)
                    : new State(true, entry.checksum());
        }

        private static State from(FileSnapshot snapshot) {
            return snapshot == null
                    ? new State(false, null)
                    : new State(true, Objects.requireNonNull(snapshot.checksum()));
        }

        private static State from(FileRevision revision) {
            return revision == null || revision.operation() == FileOperation.DELETE
                    ? new State(false, null)
                    : new State(true, Objects.requireNonNull(revision.checksum()));
        }
    }
}
