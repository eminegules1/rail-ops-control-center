package com.railops.backend;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

/**
 * Serializes a live-state write (event ingestion, a status change) against a live-state rebuild. Writes take the
 * read side and may run concurrently with each other; a rebuild takes the write side and waits for every in-flight
 * write to finish first, so neither can apply its own delta on top of the other's snapshot. Ingestion needs this
 * too: pausing the Kafka listener only requests a pause and does not wait for the consumer's in-flight record, so
 * the lock is what actually excludes a write mid-rebuild, not the pause by itself.
 */
@Component
class LiveStateLock {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    Lock forLiveStateWrite() {
        return lock.readLock();
    }

    Lock forRebuild() {
        return lock.writeLock();
    }
}
