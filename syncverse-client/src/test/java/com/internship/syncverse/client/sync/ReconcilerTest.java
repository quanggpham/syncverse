package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.fs.FileSnapshot;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.common.protocol.FileOperation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.APPLY_DELETE;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.APPLY_REMOTE;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.NO_OP;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.UPLOAD_CONFLICT;
import static com.internship.syncverse.client.sync.ReconciliationAction.Kind.UPLOAD_LOCAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconcilerTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void decidesFromBaseLocalAndRemote(
            String scenario,
            FileManifestEntry base,
            FileSnapshot local,
            FileRevision remote,
            ReconciliationAction expected) {
        assertEquals(expected, Reconciler.reconcile(base, local, remote));
    }

    private static Stream<Arguments> matrix() {
        return Stream.of(
                row("new local file", null, local("local"), null,
                        action(UPLOAD_LOCAL, "local", 0)),
                row("new remote file", null, null, remote("remote", 4),
                        action(APPLY_REMOTE, "remote", 4)),
                row("new identical file on both sides", null, local("same"), remote("same", 5),
                        action(NO_OP, "same", 5)),
                row("new different file on both sides", null, local("local"), remote("remote", 6),
                        action(UPLOAD_CONFLICT, "local", 0)),
                row("only local changed", base("base", 7), local("local"), remote("base", 7),
                        action(UPLOAD_LOCAL, "local", 7)),
                row("only remote changed", base("base", 7), local("base"), remote("remote", 8),
                        action(APPLY_REMOTE, "remote", 8)),
                row("both changed identically", base("base", 7), local("same"), remote("same", 9),
                        action(NO_OP, "same", 9)),
                row("both changed differently", base("base", 7), local("local"), remote("remote", 10),
                        action(UPLOAD_CONFLICT, "local", 7)),
                row("remote deleted unchanged local", base("base", 7), local("base"), deleted(11),
                        action(APPLY_DELETE, null, 11)),
                row("remote changed after local deletion", base("base", 7), null, remote("remote", 12),
                        action(APPLY_REMOTE, "remote", 12)));
    }

    private static Arguments row(
            String name,
            FileManifestEntry base,
            FileSnapshot local,
            FileRevision remote,
            ReconciliationAction expected) {
        return Arguments.of(name, base, local, remote, expected);
    }

    private static FileManifestEntry base(String checksum, long version) {
        return new FileManifestEntry(checksum, version, false);
    }

    private static FileSnapshot local(String checksum) {
        return new FileSnapshot("file.txt", 0, checksum, new byte[0]);
    }

    private static FileRevision remote(String checksum, long version) {
        return new FileRevision(version, "file.txt", FileOperation.UPDATE,
                version, checksum, 0, "");
    }

    private static FileRevision deleted(long version) {
        return new FileRevision(version, "file.txt", FileOperation.DELETE,
                version, null, 0, null);
    }

    private static ReconciliationAction action(
            ReconciliationAction.Kind kind, String checksum, long version) {
        return new ReconciliationAction(kind, checksum, version);
    }
}
