package com.aicodeassistant.verify;

import java.nio.file.Path;

public record DevServerHandle(Process process, long pid, int port, Path logFile, Path pidFile) {}
