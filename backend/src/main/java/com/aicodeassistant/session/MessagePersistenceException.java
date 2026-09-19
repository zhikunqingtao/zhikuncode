package com.aicodeassistant.session;

/** A durable session message could not be confirmed. */
public class MessagePersistenceException extends RuntimeException {
    private final String code;

    public MessagePersistenceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public MessagePersistenceException(String code, String message) {
        this(code, message, null);
    }

    public String code() { return code; }
}
