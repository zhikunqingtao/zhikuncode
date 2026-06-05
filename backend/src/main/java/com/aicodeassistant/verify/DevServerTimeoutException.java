package com.aicodeassistant.verify;

import java.time.Duration;

public class DevServerTimeoutException extends RuntimeException {
    private final String logTail;

    public DevServerTimeoutException(int port, Duration timeout, String logTail) {
        super("Dev server on port " + port + " not ready after " + timeout.toSeconds() + "s");
        this.logTail = logTail;
    }

    public String getLogTail() { return logTail; }
}
