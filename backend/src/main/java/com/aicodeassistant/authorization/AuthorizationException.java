package com.aicodeassistant.authorization;

public final class AuthorizationException extends RuntimeException {
    private final String code;
    public AuthorizationException(String code, String message) { super(message); this.code = code; }
    public AuthorizationException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
