package com.aicodeassistant.exception;

/** An execution request must not implicitly change an existing session's permissions. */
public final class PermissionModeMismatchException extends RuntimeException {
    public PermissionModeMismatchException() {
        super("Requested permission mode differs from the session; change the session permission setting first");
    }
}
