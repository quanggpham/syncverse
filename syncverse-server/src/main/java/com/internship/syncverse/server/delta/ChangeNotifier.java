package com.internship.syncverse.server.delta;

import com.internship.syncverse.server.persistence.ChangeLogRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Component
public final class ChangeNotifier {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition committed = lock.newCondition();
    private long latestCommittedVersion;

    public ChangeNotifier(ChangeLogRepository changes) {
        latestCommittedVersion = changes.maxVersion();
    }

    public void signalCommitted(long version) {
        lock.lock();
        try {
            if (version > latestCommittedVersion) {
                latestCommittedVersion = version;
                committed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitAfter(long cursor, Duration timeout) {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (latestCommittedVersion <= cursor && remaining > 0) {
                try {
                    remaining = committed.awaitNanos(remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return latestCommittedVersion > cursor;
        } finally {
            lock.unlock();
        }
    }
}
