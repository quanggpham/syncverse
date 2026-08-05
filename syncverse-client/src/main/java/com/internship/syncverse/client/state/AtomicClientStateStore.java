package com.internship.syncverse.client.state;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class AtomicClientStateStore {

    private final Path path;
    private final Path temporaryPath;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final StateFileMover mover;

    public AtomicClientStateStore(Path workspace) {
        this(workspace, AtomicClientStateStore::replaceAtomically);
    }

    AtomicClientStateStore(Path workspace, StateFileMover mover) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        path = normalizedWorkspace.resolveSibling(
                normalizedWorkspace.getFileName() + ".syncverse-state.json");
        temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
        this.mover = mover;
    }

    public Path path() {
        return path;
    }

    public Optional<ClientState> load() throws IOException {
        if (Files.notExists(path)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(path.toFile(), ClientState.class));
    }

    public void save(ClientState state) throws IOException {
        try {
            objectMapper.writeValue(temporaryPath.toFile(), state);
            mover.replace(temporaryPath, path);
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

@FunctionalInterface
interface StateFileMover {
    void replace(Path source, Path target) throws IOException;
}
