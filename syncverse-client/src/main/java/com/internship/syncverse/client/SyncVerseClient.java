package com.internship.syncverse.client;

import com.internship.syncverse.client.cli.CliArguments;
import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.state.AtomicClientStateStore;
import com.internship.syncverse.client.state.ClientState;
import com.internship.syncverse.client.sync.ConnectionManager;
import com.internship.syncverse.client.sync.SyncCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class SyncVerseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncVerseClient.class);
    private static final URI DEFAULT_SERVER_URI = URI.create("http://localhost:8080");

    private SyncVerseClient() {
    }

    public static void main(String[] args) throws Exception {
        CliArguments arguments = CliArguments.parse(args);
        URI serverUri = serverUri(System.getenv());
        ServerApiClient serverApi = ServerApiClient.http(serverUri);
        AtomicClientStateStore stateStore = new AtomicClientStateStore(arguments.workspace());
        ClientState state = stateStore.load().orElseGet(
                () -> ClientState.empty(arguments.clientName()));
        if (!state.clientName().equals(arguments.clientName())) {
            throw new IllegalArgumentException(
                    "Workspace state belongs to client " + state.clientName());
        }
        AtomicReference<SyncCoordinator> coordinatorReference = new AtomicReference<>();
        ConnectionManager connection = new ConnectionManager(
                serverApi,
                arguments.clientName(),
                Executors.newSingleThreadScheduledExecutor(),
                Duration.ofSeconds(4),
                () -> {
                    try {
                        coordinatorReference.get().start();
                    } catch (Exception exception) {
                        throw new IllegalStateException("Cannot start synchronization", exception);
                    }
                },
                () -> {
                    SyncCoordinator current = coordinatorReference.get();
                    return current == null
                            ? state.lastSeenGlobalVersion()
                            : current.state().lastSeenGlobalVersion();
                });

        SyncCoordinator coordinator = new SyncCoordinator(
                arguments.workspace(), serverApi, connection::sessionId, stateStore, state);
        coordinatorReference.set(coordinator);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            coordinator.close();
            connection.shutdown();
        }, "syncverse-shutdown"));
        connection.start();
        LOGGER.info("SyncVerse client {} started for workspace {} using server {}",
                arguments.clientName(), arguments.workspace(), serverUri);
    }

    static URI serverUri(Map<String, String> environment) {
        String configured = environment.get("SYNCVERSE_SERVER_URL");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SERVER_URI;
        }
        URI uri = URI.create(configured);
        String scheme = uri.getScheme();
        if ((scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("SYNCVERSE_SERVER_URL must be an absolute HTTP URL");
        }
        return uri;
    }
}
