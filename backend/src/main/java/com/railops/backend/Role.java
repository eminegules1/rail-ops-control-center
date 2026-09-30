package com.railops.backend;

/** ADMIN may change incident status; VIEWER is read-only. */
enum Role {
    ADMIN,
    VIEWER
}
