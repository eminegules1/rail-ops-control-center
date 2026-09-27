package com.railops.backend;

/** A service's health as the Lua scripts derive it from its active incidents. */
public enum ServiceHealth {
    /** No active MAJOR, WARNING or CRITICAL incident. */
    HEALTHY,
    /** An active MAJOR or WARNING incident, and no active CRITICAL one. */
    DEGRADED,
    /** An active CRITICAL incident. */
    DOWN
}
