package com.quizforge.platform.tenancy;

import java.util.UUID;

/**
 * Holds the workspace in scope for the current request.
 *
 * <p>Backed by a ThreadLocal. Virtual threads each carry their own copy, so
 * this remains correct under Loom; it would not be safe if work were handed to
 * a shared pool without propagation, which is why nothing in this codebase
 * does that.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID workspaceId) {
        CURRENT.set(workspaceId);
    }

    public static UUID current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
