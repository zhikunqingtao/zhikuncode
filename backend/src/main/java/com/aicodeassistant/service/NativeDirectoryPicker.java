package com.aicodeassistant.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens the host operating system's folder chooser for direct desktop use.
 * Commands and scripts are fixed here and never contain request data.
 */
interface NativeDirectoryPicker {

    boolean isAvailable();

    Optional<String> pick();

    default Optional<String> pickFile() {
        throw new UnavailableException(null);
    }

    final class BusyException extends RuntimeException {}

    final class TimeoutException extends RuntimeException {}

    final class UnavailableException extends RuntimeException {
        UnavailableException(Throwable cause) {
            super(cause);
        }
    }
}

final class SystemNativeDirectoryPicker implements NativeDirectoryPicker {

    private static final String CANCELLED = "__ZHIKUN_CANCELLED__";
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Platform platform;
    private final Path executable;
    private final Duration timeout;
    private final AtomicBoolean active = new AtomicBoolean();

    SystemNativeDirectoryPicker() {
        this(detectPlatform(), DEFAULT_TIMEOUT);
    }

    SystemNativeDirectoryPicker(Platform platform, Duration timeout) {
        this.platform = platform;
        this.executable = executableFor(platform);
        this.timeout = timeout;
    }

    @Override
    public boolean isAvailable() {
        return platform != Platform.UNSUPPORTED
                && executable != null
                && Files.isRegularFile(executable)
                && Files.isExecutable(executable);
    }

    @Override
    public Optional<String> pick() {
        return pick(SelectionType.DIRECTORY);
    }

    @Override
    public Optional<String> pickFile() {
        return pick(SelectionType.FILE);
    }

    private Optional<String> pick(SelectionType selectionType) {
        if (!isAvailable()) {
            throw new UnavailableException(null);
        }
        if (!active.compareAndSet(false, true)) {
            throw new BusyException();
        }

        Process process = null;
        try {
            process = new ProcessBuilder(command(selectionType))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new TimeoutException();
            }

            byte[] output = process.getInputStream()
                    .readNBytes(MAX_OUTPUT_BYTES + 1);
            if (output.length > MAX_OUTPUT_BYTES || process.exitValue() != 0) {
                throw new UnavailableException(null);
            }
            String selected = new String(output, StandardCharsets.UTF_8)
                    .trim();
            if (selected.isEmpty() || CANCELLED.equals(selected)) {
                return Optional.empty();
            }
            return Optional.of(selected);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminate(process);
            }
            throw new UnavailableException(interrupted);
        } catch (IOException failure) {
            throw new UnavailableException(failure);
        } finally {
            active.set(false);
        }
    }

    List<String> command() {
        return command(SelectionType.DIRECTORY);
    }

    List<String> fileCommand() {
        return command(SelectionType.FILE);
    }

    private List<String> command(SelectionType selectionType) {
        return switch (platform) {
            case MACOS -> List.of(
                    executable.toString(),
                    "-e",
                    selectionType == SelectionType.FILE ? macFileScript() : macScript(),
                    "--",
                    defaultStartDirectory().toString());
            case WINDOWS -> List.of(
                    executable.toString(),
                    "-NoProfile",
                    "-NonInteractive",
                    "-STA",
                    "-Command",
                    selectionType == SelectionType.FILE ? windowsFileScript() : windowsScript());
            case UNSUPPORTED -> List.of();
        };
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static Platform detectPlatform() {
        String osName = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return Platform.MACOS;
        }
        if (osName.contains("win")) {
            return Platform.WINDOWS;
        }
        return Platform.UNSUPPORTED;
    }

    private static Path executableFor(Platform platform) {
        return switch (platform) {
            case MACOS -> Path.of("/usr/bin/osascript");
            case WINDOWS -> Path.of(
                    System.getenv().getOrDefault(
                            "SystemRoot", "C:\\Windows"),
                    "System32", "WindowsPowerShell", "v1.0",
                    "powershell.exe");
            case UNSUPPORTED -> null;
        };
    }

    private static String macScript() {
        return "on run argv\n"
                + "set startFolder to POSIX file (item 1 of argv) as alias\n"
                + "try\n"
                + "set selectedFolder to choose folder with prompt "
                + "\"Select a ZhikunCode workspace\" default location "
                + "startFolder\n"
                + "return POSIX path of selectedFolder\n"
                + "on error number -128\n"
                + "return \"" + CANCELLED + "\"\n"
                + "end try\n"
                + "end run";
    }

    private static String macFileScript() {
        return "on run argv\n"
                + "set startFolder to POSIX file (item 1 of argv) as alias\n"
                + "try\n"
                + "tell application \"Finder\"\n"
                + "activate\n"
                + "set selectedFile to choose file with prompt "
                + "\"Select a local file path to reference\" default location "
                + "startFolder\n"
                + "end tell\n"
                + "return POSIX path of selectedFile\n"
                + "on error number -128\n"
                + "return \"" + CANCELLED + "\"\n"
                + "end try\n"
                + "end run";
    }

    private static Path defaultStartDirectory() {
        Path home = Path.of(System.getProperty("user.home", "."))
                .toAbsolutePath().normalize();
        Path desktop = home.resolve("Desktop");
        return Files.isDirectory(desktop) ? desktop : home;
    }

    private static String windowsScript() {
        return "[Console]::OutputEncoding = New-Object "
                + "System.Text.UTF8Encoding($false); "
                + "Add-Type -AssemblyName System.Windows.Forms; "
                + "$dialog = New-Object "
                + "System.Windows.Forms.FolderBrowserDialog; "
                + "try { "
                + "$dialog.Description = 'Select a ZhikunCode workspace'; "
                + "$dialog.SelectedPath = "
                + "[Environment]::GetFolderPath('DesktopDirectory'); "
                + "$dialog.ShowNewFolderButton = $true; "
                + "if ($dialog.ShowDialog() -eq "
                + "[System.Windows.Forms.DialogResult]::OK) "
                + "{ [Console]::Out.Write($dialog.SelectedPath) } else "
                + "{ [Console]::Out.Write('" + CANCELLED + "') } "
                + "} finally { $dialog.Dispose() }";
    }

    private static String windowsFileScript() {
        return "[Console]::OutputEncoding = New-Object "
                + "System.Text.UTF8Encoding($false); "
                + "Add-Type -AssemblyName System.Windows.Forms; "
                + "$dialog = New-Object System.Windows.Forms.OpenFileDialog; "
                + "try { "
                + "$dialog.Title = 'Select a local file path to reference'; "
                + "$dialog.InitialDirectory = "
                + "[Environment]::GetFolderPath('DesktopDirectory'); "
                + "$dialog.Multiselect = $false; "
                + "$dialog.CheckFileExists = $true; "
                + "if ($dialog.ShowDialog() -eq "
                + "[System.Windows.Forms.DialogResult]::OK) "
                + "{ [Console]::Out.Write($dialog.FileName) } else "
                + "{ [Console]::Out.Write('" + CANCELLED + "') } "
                + "} finally { $dialog.Dispose() }";
    }

    private enum SelectionType {
        DIRECTORY,
        FILE
    }

    enum Platform {
        MACOS,
        WINDOWS,
        UNSUPPORTED
    }
}
