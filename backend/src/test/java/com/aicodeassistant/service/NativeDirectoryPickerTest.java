package com.aicodeassistant.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeDirectoryPickerTest {

    @Test
    void macCommandUsesFixedOsascriptAndDesktopArgument() {
        SystemNativeDirectoryPicker picker =
                new SystemNativeDirectoryPicker(
                        SystemNativeDirectoryPicker.Platform.MACOS,
                        Duration.ofSeconds(1));

        List<String> command = picker.command();

        assertThat(command.getFirst()).isEqualTo("/usr/bin/osascript");
        assertThat(command).containsSubsequence("-e");
        assertThat(command).contains("--");
        assertThat(command.get(2))
                .contains("on run argv")
                .contains("POSIX file (item 1 of argv) as alias")
                .contains("default location startFolder")
                .contains("__ZHIKUN_CANCELLED__");
        assertThat(command.getLast()).doesNotContain("\n");
    }

    @Test
    void windowsCommandUsesFixedStaFolderBrowserDialog() {
        SystemNativeDirectoryPicker picker =
                new SystemNativeDirectoryPicker(
                        SystemNativeDirectoryPicker.Platform.WINDOWS,
                        Duration.ofSeconds(1));

        List<String> command = picker.command();

        assertThat(command.getFirst()).endsWith("powershell.exe");
        assertThat(command).containsSubsequence(
                "-NoProfile", "-STA", "-Command");
        assertThat(command).contains("-NonInteractive");
        assertThat(command.getLast())
                .contains("System.Windows.Forms.FolderBrowserDialog")
                .contains("DesktopDirectory")
                .contains("$dialog.Dispose()")
                .contains("__ZHIKUN_CANCELLED__");
    }

    @Test
    void fileCommandsUseSingleFileNativeDialogs() {
        SystemNativeDirectoryPicker mac = new SystemNativeDirectoryPicker(
                SystemNativeDirectoryPicker.Platform.MACOS, Duration.ofSeconds(1));
        SystemNativeDirectoryPicker windows = new SystemNativeDirectoryPicker(
                SystemNativeDirectoryPicker.Platform.WINDOWS, Duration.ofSeconds(1));

        assertThat(mac.fileCommand().get(2))
                .contains("tell application \"Finder\"")
                .contains("activate")
                .contains("choose file")
                .doesNotContain("choose folder");
        assertThat(windows.fileCommand().getLast())
                .contains("System.Windows.Forms.OpenFileDialog")
                .contains("$dialog.Multiselect = $false")
                .contains("$dialog.CheckFileExists = $true")
                .contains("$dialog.Dispose()");
    }

    @Test
    void unsupportedPlatformHasNoCommandOrCapability() {
        SystemNativeDirectoryPicker picker =
                new SystemNativeDirectoryPicker(
                        SystemNativeDirectoryPicker.Platform.UNSUPPORTED,
                        Duration.ofSeconds(1));

        assertThat(picker.command()).isEmpty();
        assertThat(picker.isAvailable()).isFalse();
    }
}
