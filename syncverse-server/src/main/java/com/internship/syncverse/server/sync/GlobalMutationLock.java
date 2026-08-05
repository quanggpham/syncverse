package com.internship.syncverse.server.sync;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Component
public final class GlobalMutationLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T execute(Supplier<T> mutation) {
        lock.lock();
        try {
            return mutation.get();
        } finally {
            lock.unlock();
        }
    }
}
