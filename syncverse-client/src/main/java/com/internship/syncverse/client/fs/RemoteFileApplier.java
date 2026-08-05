package com.internship.syncverse.client.fs;

import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.state.FileManifestEntry;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.common.protocol.FileOperation;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;

public final class RemoteFileApplier {

    private final Path workspace;
    private final AtomicClientStateStore stateStore;

    public RemoteFileApplier(Path workspace, AtomicClientStateStore stateStore) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.stateStore = stateStore;
    }

    public ClientState apply(ClientState state, FileRevision revision) throws IOException {
        Path target = target(revision.filename());
        if (revision.operation() == FileOperation.DELETE) {
            validateDelete(revision);
            Files.deleteIfExists(target);
        } else {
            byte[] content = validateContent(revision);
            replace(target, content);
        }

        HashMap<String, FileManifestEntry> manifest = new HashMap<>(state.manifest());
        boolean deleted = revision.operation() == FileOperation.DELETE;
        manifest.put(revision.filename(), new FileManifestEntry(
                deleted ? null : revision.checksum(), revision.fileVersion(), deleted));
        ClientState applied = new ClientState(
                state.formatVersion(), state.clientName(), state.lastSeenGlobalVersion(),
                manifest, state.pendingOperation());
        stateStore.save(applied);
        return applied;
    }

    private Path target(String filename) {
        if (filename == null || filename.isBlank()
                || filename.equals(".") || filename.equals("..")
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.indexOf('\0') >= 0) {
            throw new InvalidRemoteRevisionException("Remote filename is not flat and safe");
        }
        Path target = workspace.resolve(filename).normalize();
        if (!workspace.equals(target.getParent())) {
            throw new InvalidRemoteRevisionException("Remote filename escapes workspace");
        }
        return target;
    }

    private static void validateDelete(FileRevision revision) {
        if (revision.contentBase64() != null
                || revision.checksum() != null
                || revision.sizeBytes() != 0) {
            throw new InvalidRemoteRevisionException("Remote delete contains file bytes");
        }
    }

    private static byte[] validateContent(FileRevision revision) {
        if (revision.contentBase64() == null || revision.checksum() == null) {
            throw new InvalidRemoteRevisionException("Remote file content is incomplete");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(revision.contentBase64());
        } catch (IllegalArgumentException exception) {
            throw new InvalidRemoteRevisionException("Remote content is not valid Base64");
        }
        if (content.length > DirectoryScanner.MAX_FILE_BYTES) {
            throw new InvalidRemoteRevisionException("Remote file exceeds 1,048,576 bytes");
        }
        if (revision.sizeBytes() != content.length || !checksum(content).equals(revision.checksum())) {
            throw new InvalidRemoteRevisionException("Remote content checksum or size does not match");
        }
        return content;
    }

    private void replace(Path target, byte[] content) throws IOException {
        Path workspaceParent = workspace.getParent();
        if (workspaceParent == null) {
            throw new IOException("Workspace must have a parent directory");
        }
        Path temporary = Files.createTempFile(
                workspaceParent, ".syncverse-" + workspace.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
