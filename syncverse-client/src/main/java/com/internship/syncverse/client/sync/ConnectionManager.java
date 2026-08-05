package com.internship.syncverse.client.sync;

import com.internship.syncverse.client.http.ServerApiClient;
import com.internship.syncverse.client.http.ServerApiException;
import com.internship.syncverse.common.dto.RegisterResponse;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class ConnectionManager {

    private final ServerApiClient serverApi;
    private final String clientName;
    private final ScheduledExecutorService scheduler;
    private final Duration heartbeatInterval;
    private final Runnable firstSessionAvailable;
    private final LongSupplier persistedCursor;
    private final boolean returningClient;
    private final RetryPolicy retryPolicy = RetryPolicy.exponential(
            Duration.ofSeconds(1), Duration.ofSeconds(30));

    private volatile ClientMode mode = ClientMode.STARTING;
    private volatile UUID sessionId;
    private volatile long lastSeenGlobalVersion;
    private volatile long serverGlobalVersion;
    private int consecutiveFailures;
    private ScheduledFuture<?> heartbeatTask;

    public ConnectionManager(
            ServerApiClient serverApi,
            String clientName,
            ScheduledExecutorService scheduler,
            Duration heartbeatInterval) {
        this(serverApi, clientName, scheduler, heartbeatInterval, () -> { }, null, false);
    }

    public ConnectionManager(
            ServerApiClient serverApi,
            String clientName,
            ScheduledExecutorService scheduler,
            Duration heartbeatInterval,
            Runnable firstSessionAvailable) {
        this(serverApi, clientName, scheduler, heartbeatInterval,
                firstSessionAvailable, null, false);
    }

    public ConnectionManager(
            ServerApiClient serverApi,
            String clientName,
            ScheduledExecutorService scheduler,
            Duration heartbeatInterval,
            Runnable firstSessionAvailable,
            LongSupplier persistedCursor) {
        this(serverApi, clientName, scheduler, heartbeatInterval,
                firstSessionAvailable, persistedCursor, false);
    }

    public ConnectionManager(
            ServerApiClient serverApi,
            String clientName,
            ScheduledExecutorService scheduler,
            Duration heartbeatInterval,
            Runnable firstSessionAvailable,
            LongSupplier persistedCursor,
            boolean returningClient) {
        this.serverApi = serverApi;
        this.clientName = clientName;
        this.scheduler = scheduler;
        this.heartbeatInterval = heartbeatInterval;
        this.firstSessionAvailable = firstSessionAvailable;
        this.persistedCursor = persistedCursor == null
                ? () -> lastSeenGlobalVersion
                : persistedCursor;
        this.returningClient = returningClient;
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        }
    }

    public synchronized void start() {
        if (mode != ClientMode.STARTING) {
            throw new IllegalStateException("Connection manager has already started");
        }
        try {
            long cursor = persistedCursor.getAsLong();
            if (returningClient) {
                accept(serverApi.reconnect(clientName, cursor), ClientMode.RECONCILING);
            } else {
                accept(serverApi.register(clientName), ClientMode.RECONCILING);
            }
        } catch (ServerApiException exception) {
            handleFailure(exception);
        }
        if (mode != ClientMode.STOPPED) {
            scheduleNext(mode == ClientMode.ONLINE || mode == ClientMode.RECONCILING
                    ? heartbeatInterval
                    : retryPolicy.delay(0));
        }
    }

    public synchronized void tick() {
        if (mode == ClientMode.ONLINE || mode == ClientMode.RECONCILING) {
            sendHeartbeat();
        } else if (mode == ClientMode.OFFLINE) {
            reconnect();
        }
    }

    public synchronized void reconciliationComplete(long latestGlobalVersion) {
        if (mode != ClientMode.RECONCILING) {
            throw new IllegalStateException("Client is not reconciling");
        }
        lastSeenGlobalVersion = latestGlobalVersion;
        mode = ClientMode.ONLINE;
    }

    public synchronized void shutdown() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        scheduler.shutdownNow();
        mode = ClientMode.STOPPED;
    }

    public ClientMode mode() {
        return mode;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public long serverGlobalVersion() {
        return serverGlobalVersion;
    }

    public boolean isStopped() {
        return mode == ClientMode.STOPPED;
    }

    private void sendHeartbeat() {
        try {
            serverApi.heartbeat(sessionId);
            consecutiveFailures = 0;
        } catch (ServerApiException exception) {
            handleFailure(exception);
        }
    }

    private void reconnect() {
        try {
            accept(serverApi.reconnect(clientName, persistedCursor.getAsLong()),
                    ClientMode.RECONCILING);
            consecutiveFailures = 0;
        } catch (ServerApiException exception) {
            handleFailure(exception);
        }
    }

    private void accept(RegisterResponse response, ClientMode nextMode) {
        boolean firstSession = sessionId == null;
        sessionId = response.sessionId();
        serverGlobalVersion = response.currentGlobalVersion();
        mode = nextMode;
        if (firstSession) {
            firstSessionAvailable.run();
        }
    }

    private void runScheduledTick() {
        try {
            tick();
        } catch (RuntimeException exception) {
            consecutiveFailures++;
            mode = ClientMode.OFFLINE;
        }
        synchronized (this) {
            if (mode != ClientMode.STOPPED) {
                Duration delay = mode == ClientMode.ONLINE || mode == ClientMode.RECONCILING
                        ? heartbeatInterval
                        : retryPolicy.delay(Math.max(0, consecutiveFailures - 1));
                scheduleNext(delay);
            }
        }
    }

    private void handleFailure(ServerApiException exception) {
        if (exception.kind() == ServerApiException.Kind.PERMANENT) {
            shutdown();
            return;
        }
        consecutiveFailures++;
        mode = ClientMode.OFFLINE;
    }

    private void scheduleNext(Duration delay) {
        heartbeatTask = scheduler.schedule(
                this::runScheduledTick, delay.toMillis(), TimeUnit.MILLISECONDS);
    }
}
