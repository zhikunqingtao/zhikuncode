package com.aicodeassistant.history;

import com.aicodeassistant.service.FileSnapshotRepository;
import com.aicodeassistant.session.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileHistoryServiceFailureTest {
    @Test
    void snapshotFailureDoesNotEscapeAndAppliedEditCachesStillAdvance() {
        FileSnapshotRepository snapshots = mock(FileSnapshotRepository.class);
        doThrow(new IllegalStateException("db unavailable")).when(snapshots).save(any());
        FileHistoryService history = new FileHistoryService(snapshots, mock(SessionManager.class));
        history.beginTransaction("session", "message", 3);

        FileHistoryService.HistoryRecordResult result = history.trackAppliedEdit(
                "/workspace/file.txt", "old", "session", "message", "edit");
        history.commitTransaction("session");

        assertFalse(result.recorded());
        assertEquals("HISTORY_SNAPSHOT_PERSIST_FAILED", result.errorCode());
        assertEquals(1, history.getTrackedFileCount());
        assertEquals(java.util.List.of("/workspace/file.txt"),
                history.getLastTransaction("session").orElseThrow().changedFiles());
    }
}
