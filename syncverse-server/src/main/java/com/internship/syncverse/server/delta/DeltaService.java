package com.internship.syncverse.server.delta;

import com.internship.syncverse.common.dto.DeltaResponse;
import com.internship.syncverse.common.dto.FileRevision;
import com.internship.syncverse.server.config.SyncProperties;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import com.internship.syncverse.server.persistence.ChangeRecord;
import com.internship.syncverse.server.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public final class DeltaService {

    private static final int BATCH_SIZE = 20;

    private final SessionService sessions;
    private final ChangeLogRepository changes;
    private final ChangeNotifier notifier;
    private final Duration timeout;

    @Autowired
    public DeltaService(
            SessionService sessions,
            ChangeLogRepository changes,
            ChangeNotifier notifier,
            SyncProperties properties) {
        this(sessions, changes, notifier, properties.longPollTimeout());
    }

    DeltaService(
            SessionService sessions,
            ChangeLogRepository changes,
            ChangeNotifier notifier,
            Duration timeout) {
        this.sessions = sessions;
        this.changes = changes;
        this.notifier = notifier;
        this.timeout = timeout;
    }

    public DeltaResponse poll(UUID sessionId, long since) {
        sessions.requireActive(sessionId);
        if (since < 0) {
            throw new IllegalArgumentException("Delta cursor cannot be negative");
        }
        List<ChangeRecord> records = changes.findAfter(since, BATCH_SIZE);
        if (records.isEmpty()) {
            notifier.awaitAfter(since, timeout);
            records = changes.findAfter(since, BATCH_SIZE);
        }
        List<FileRevision> revisions = records.stream()
                .map(DeltaService::revision)
                .toList();
        long latest = revisions.isEmpty()
                ? since
                : revisions.get(revisions.size() - 1).globalVersion();
        return new DeltaResponse(since, latest, revisions);
    }

    private static FileRevision revision(ChangeRecord change) {
        String content = change.content() == null
                ? null
                : Base64.getEncoder().encodeToString(change.content());
        return new FileRevision(
                change.globalVersion(),
                change.filename(),
                change.operation(),
                change.globalVersion(),
                change.checksum(),
                change.sizeBytes(),
                content);
    }
}
