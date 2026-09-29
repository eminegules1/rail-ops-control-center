package com.railops.backend;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Whether the Redis live state may be stale and needs rebuilding from PostgreSQL. */
@Component
public class ReconcileState {

    private final AtomicBoolean needed = new AtomicBoolean();

    public void markNeeded() {
        needed.set(true);
    }

    public boolean isNeeded() {
        return needed.get();
    }

    public void clear() {
        needed.set(false);
    }
}
