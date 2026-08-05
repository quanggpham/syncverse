package com.internship.syncverse.server.sync;

import com.internship.syncverse.common.dto.FileChangeRequest;
import com.internship.syncverse.common.dto.FileChangeResponse;
import com.internship.syncverse.common.protocol.ChangeOutcome;
import com.internship.syncverse.common.protocol.FileOperation;
import com.internship.syncverse.server.delta.ChangeNotifier;
import com.internship.syncverse.server.persistence.ChangeLogRepository;
import com.internship.syncverse.server.persistence.FileState;
import com.internship.syncverse.server.persistence.FileStateRepository;
import com.internship.syncverse.server.persistence.OperationReceipt;
import com.internship.syncverse.server.persistence.OperationReceiptRepository;
import com.internship.syncverse.server.session.ClientSession;
import com.internship.syncverse.server.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public final class SyncService {

    private final SessionService sessions;
    private final FileStateRepository files;
    private final ChangeLogRepository changes;
    private final OperationReceiptRepository receipts;
    private final FileChangeValidator validator;
    private final ConflictNameGenerator conflictNames;
    private final GlobalMutationLock mutationLock;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ChangeNotifier notifier;

    @Autowired
    public SyncService(
            SessionService sessions,
            FileStateRepository files,
            ChangeLogRepository changes,
            OperationReceiptRepository receipts,
            FileChangeValidator validator,
            ConflictNameGenerator conflictNames,
            GlobalMutationLock mutationLock,
            TransactionTemplate transactions,
            Clock clock,
            ChangeNotifier notifier) {
        this.sessions = sessions;
        this.files = files;
        this.changes = changes;
        this.receipts = receipts;
        this.validator = validator;
        this.conflictNames = conflictNames;
        this.mutationLock = mutationLock;
        this.transactions = transactions;
        this.clock = clock;
        this.notifier = notifier;
    }

    SyncService(
            SessionService sessions,
            FileStateRepository files,
            ChangeLogRepository changes,
            OperationReceiptRepository receipts,
            FileChangeValidator validator,
            ConflictNameGenerator conflictNames,
            GlobalMutationLock mutationLock,
            TransactionTemplate transactions,
            Clock clock) {
        this(sessions, files, changes, receipts, validator, conflictNames,
                mutationLock, transactions, clock, new ChangeNotifier(changes));
    }

    public FileChangeResponse apply(FileChangeRequest request) {
        ClientSession session = sessions.requireActive(
                request == null ? null : request.sessionId());
        byte[] content = validator.validate(request);
        return mutationLock.execute(() -> {
            FileChangeResponse response = Objects.requireNonNull(
                    transactions.execute(status -> applyInTransaction(request, content, session)),
                    "File change transaction returned no response");
            if (response.globalVersion() != null) {
                notifier.signalCommitted(response.globalVersion());
            }
            return response;
        });
    }

    private FileChangeResponse applyInTransaction(
            FileChangeRequest request, byte[] content, ClientSession session) {
        Optional<OperationReceipt> duplicate = receipts.find(request.operationId());
        if (duplicate.isPresent()) {
            return response(duplicate.orElseThrow());
        }

        Optional<FileState> current = files.find(request.filename());
        long currentVersion = current.map(FileState::fileVersion).orElse(0L);
        boolean stale = request.baseFileVersion() != currentVersion;

        if (stale && request.operation() == FileOperation.DELETE) {
            return rejectStaleDelete(request, currentVersion);
        }
        if (stale) {
            return createConflictCopy(request, content, session.clientName());
        }
        return applyCanonical(request, content, session.clientName());
    }

    private FileChangeResponse rejectStaleDelete(
            FileChangeRequest request, long currentVersion) {
        OperationReceipt receipt = new OperationReceipt(
                request.operationId(),
                ChangeOutcome.CONFLICT_REJECTED,
                request.filename(),
                request.filename(),
                null,
                currentVersion,
                clock.instant());
        receipts.insert(receipt);
        return response(receipt);
    }

    private FileChangeResponse createConflictCopy(
            FileChangeRequest request, byte[] content, String clientName) {
        String conflictFilename = conflictNames.generate(
                request.filename(), clientName, request.operationId());
        Instant now = clock.instant();
        long version = changes.append(
                conflictFilename,
                FileOperation.CREATE,
                content,
                request.checksum(),
                content.length,
                clientName,
                now);
        files.upsert(FileState.present(
                conflictFilename,
                content,
                request.checksum(),
                content.length,
                version,
                clientName,
                now));
        OperationReceipt receipt = new OperationReceipt(
                request.operationId(),
                ChangeOutcome.CONFLICT_COPY_CREATED,
                request.filename(),
                conflictFilename,
                version,
                version,
                now);
        receipts.insert(receipt);
        return response(receipt);
    }

    private FileChangeResponse applyCanonical(
            FileChangeRequest request, byte[] content, String clientName) {
        Instant now = clock.instant();
        long size = content == null ? 0 : content.length;
        long version = changes.append(
                request.filename(),
                request.operation(),
                content,
                request.checksum(),
                size,
                clientName,
                now);
        if (request.operation() == FileOperation.DELETE) {
            files.upsert(FileState.tombstone(
                    request.filename(), version, clientName, now));
        } else {
            files.upsert(FileState.present(
                    request.filename(), content, request.checksum(), size,
                    version, clientName, now));
        }
        OperationReceipt receipt = new OperationReceipt(
                request.operationId(),
                ChangeOutcome.APPLIED,
                request.filename(),
                request.filename(),
                version,
                version,
                now);
        receipts.insert(receipt);
        return response(receipt);
    }

    private static FileChangeResponse response(OperationReceipt receipt) {
        return new FileChangeResponse(
                receipt.outcome(),
                receipt.requestedFilename(),
                receipt.acceptedFilename(),
                receipt.globalVersion(),
                receipt.fileVersion());
    }
}
