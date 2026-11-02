package com.aicodeassistant.prompt;

import com.aicodeassistant.memdir.MemdirService;
import com.aicodeassistant.service.ProjectMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryPromptServiceTest {

    @TempDir Path temp;

    private MemoryPromptService service(Path personalDir) {
        return new MemoryPromptService(new MemdirService(personalDir), new ProjectMemoryService());
    }

    @Test
    void injectsAllThreeSourcesInPrecedenceOrder() throws Exception {
        Path personal = Files.createDirectories(temp.resolve("personal"));
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Files.writeString(personal.resolve("MEMORY.md"), "PERSONAL_MARK");
        Files.writeString(project.resolve("zhikun.md"), "SHARED_MARK");
        Files.writeString(project.resolve("zhikun.local.md"), "LOCAL_MARK");

        String prompt = service(personal).buildMemoryPrompt(project);

        int p = prompt.indexOf("PERSONAL_MARK");
        int s = prompt.indexOf("SHARED_MARK");
        int l = prompt.indexOf("LOCAL_MARK");
        assertTrue(p >= 0 && s > p && l > s, "顺序应为 MEMORY → zhikun.md → zhikun.local.md");
        assertTrue(prompt.contains("<personal_memory>"));
        assertTrue(prompt.contains("<project_memory>"));
        assertTrue(prompt.contains("<project_local_memory>"));
        assertTrue(prompt.contains("内容冲突时以优先级高者为准"));
    }

    @Test
    void returnsEmptyWhenNoMemory() throws Exception {
        Path personal = Files.createDirectories(temp.resolve("personal-empty"));
        Path project = Files.createDirectories(temp.resolve("project-empty")).toRealPath();
        assertEquals("", service(personal).buildMemoryPrompt(project));
    }

    @Test
    void rereadsFilesOnEveryCall() throws Exception {
        Path personal = Files.createDirectories(temp.resolve("personal-v"));
        Path project = Files.createDirectories(temp.resolve("project-v")).toRealPath();
        MemoryPromptService svc = service(personal);

        Files.writeString(project.resolve("zhikun.md"), "PROJECT_V1");
        assertTrue(svc.buildMemoryPrompt(project).contains("PROJECT_V1"));

        Files.writeString(project.resolve("zhikun.md"), "PROJECT_V2");
        String second = svc.buildMemoryPrompt(project);
        assertTrue(second.contains("PROJECT_V2"));
        assertFalse(second.contains("PROJECT_V1"));
    }

    @Test
    void nullProjectRootInjectsOnlyPersonal() throws Exception {
        Path personal = Files.createDirectories(temp.resolve("personal-only"));
        Files.writeString(personal.resolve("MEMORY.md"), "PERSONAL_ONLY");
        String prompt = service(personal).buildMemoryPrompt(null);
        assertTrue(prompt.contains("PERSONAL_ONLY"));
        assertFalse(prompt.contains("<project_memory>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"personal", "shared", "local"})
    void failedSourceDoesNotSuppressOtherMemories(String failedSource) {
        MemdirService personal = mock(MemdirService.class);
        ProjectMemoryService project = mock(ProjectMemoryService.class);
        when(personal.readMemoriesForPrompt()).thenReturn("PERSONAL_MARK");
        when(project.loadMemoryFile(temp, ProjectMemoryService.SHARED_FILE)).thenReturn("SHARED_MARK");
        when(project.loadMemoryFile(temp, ProjectMemoryService.LOCAL_FILE)).thenReturn("LOCAL_MARK");
        RuntimeException unavailable = new IllegalStateException("source unavailable");
        switch (failedSource) {
            case "personal" -> when(personal.readMemoriesForPrompt()).thenThrow(unavailable);
            case "shared" -> when(project.loadMemoryFile(temp, ProjectMemoryService.SHARED_FILE))
                    .thenThrow(unavailable);
            case "local" -> when(project.loadMemoryFile(temp, ProjectMemoryService.LOCAL_FILE))
                    .thenThrow(unavailable);
            default -> throw new IllegalArgumentException(failedSource);
        }

        String prompt = new MemoryPromptService(personal, project).buildMemoryPrompt(temp);

        assertEquals(!failedSource.equals("personal"), prompt.contains("PERSONAL_MARK"));
        assertEquals(!failedSource.equals("shared"), prompt.contains("SHARED_MARK"));
        assertEquals(!failedSource.equals("local"), prompt.contains("LOCAL_MARK"));
        assertTrue(prompt.startsWith("<memory>"));
        assertTrue(prompt.endsWith("</memory>"));
        verify(personal).readMemoriesForPrompt();
        verify(project).loadMemoryFile(temp, ProjectMemoryService.SHARED_FILE);
        verify(project).loadMemoryFile(temp, ProjectMemoryService.LOCAL_FILE);
    }

    @Test
    void allSourcesFailWithoutBlockingPromptConstruction() {
        MemdirService personal = mock(MemdirService.class);
        ProjectMemoryService project = mock(ProjectMemoryService.class);
        when(personal.readMemoriesForPrompt()).thenThrow(new IllegalStateException("personal unavailable"));
        when(project.loadMemoryFile(temp, ProjectMemoryService.SHARED_FILE))
                .thenThrow(new IllegalStateException("shared unavailable"));
        when(project.loadMemoryFile(temp, ProjectMemoryService.LOCAL_FILE))
                .thenThrow(new IllegalStateException("local unavailable"));

        assertEquals("", new MemoryPromptService(personal, project).buildMemoryPrompt(temp));
    }
}
