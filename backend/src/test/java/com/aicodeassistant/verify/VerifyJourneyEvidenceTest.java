package com.aicodeassistant.verify;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V007_AddEvidenceTables;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.verify.VerifyJourneyTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.http.MediaType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No real browser, server, or application database is used. */
class VerifyJourneyEvidenceTest {
    @TempDir Path workspace;
    EvidenceStore evidence;
    BrowserVerifier verifier;
    VerifyJourneyTool tool;
    DevServerLauncher launcher;

    @BeforeEach void setup() {
        verifier = mock(BrowserVerifier.class);
        evidence = mock(EvidenceStore.class);
        when(evidence.save(any())).thenAnswer(call -> call.getArgument(0));
        when(evidence.saveBlob(any())).thenReturn("ab".repeat(32));
        tool = createTool(evidence);
    }

    private VerifyJourneyTool createTool(EvidenceStore store) {
        return createTool(store, verifier);
    }

    private VerifyJourneyTool createTool(EvidenceStore store, Verifier selectedVerifier) {
        var client = mock(PythonCapabilityAwareClient.class);
        when(client.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);
        when(client.isCapabilityAvailable("HTTP_API")).thenReturn(true);
        var factory = mock(VerifierFactory.class);
        when(factory.selectVerifier(any(), anyString())).thenReturn(selectedVerifier);
        var detector = mock(PreviewStackDetector.class);
        when(detector.detect(any())).thenReturn(new StackInfo("vite", 5173, "unused"));
        launcher = mock(DevServerLauncher.class);
        return new VerifyJourneyTool(client, launcher, factory, detector, store,
                mock(SimpMessagingTemplate.class), mock(FeatureFlagService.class),
                mock(ActivityRepository.class), new ObjectMapper(), mock(NotificationService.class));
    }

    @Test void archivesActualScreenshotBytesAndReferencesBlob() throws Exception {
        byte[] jpeg = image("jpeg");
        ToolResult result = run("verified", List.of(step(0, jpeg)));
        assertFalse(result.isError(), result.content());
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(evidence).saveBlob(bytes.capture());
        assertArrayEquals(jpeg, bytes.getValue());
        EvidenceItem item = saved().items().getFirst();
        assertEquals("ab".repeat(32), item.blobSha256());
        assertEquals("image/jpeg", item.meta().get("mime"));
        assertEquals("stored", item.meta().get("screenshotStatus"));
        assertFalse(item.meta().containsKey("base64"));
    }

    @Test void invalidImageRetainsStepVerdictAndReportsEvidenceGap() {
        var step = new StepResult(0, "click", true, 1, null, List.of(), "%%%not-base64");
        ToolResult result = run("verified", List.of(step));
        assertFalse(result.isError());
        assertTrue(result.content().contains("Screenshot evidence incomplete"), result.content());
        assertEquals("verified", saved().verdict());
        assertEquals("invalid", saved().items().getFirst().meta().get("screenshotStatus"));
        verify(evidence, never()).saveBlob(any());
    }

    @Test void missingHistoricalScreenshotDoesNotInventCaptureReason() {
        ToolResult result = run("verified", List.of(new StepResult(0, "click", true, 1, null, List.of(), null)));
        assertTrue(result.content().contains("Screenshot evidence incomplete"), result.content());
        assertEquals("reason_not_recorded", saved().items().getFirst().meta().get("screenshotReason"));
    }

    @ParameterizedTest
    @MethodSource("blobPersistenceFailures")
    void blobFailureUsesPersistenceErrorAndNeverRunsJourneyAgain(RuntimeException persistenceFailure) throws Exception {
        when(evidence.saveBlob(any())).thenThrow(persistenceFailure);
        ToolResult result = run("verified", List.of(step(0, image("png"))));
        assertEquals("EVIDENCE_PERSIST_FAILED", result.failureCode());
        assertTrue(result.content().contains("verdict 'verified'"));
        assertTrue(result.content().contains("Do not automatically rerun"));
        assertEquals("verified", result.metadata().get("verdict"));
        assertFalse(result.isRetryable());
        verify(verifier, times(1)).verify(any(), anyString());
        verify(evidence, never()).save(any());
    }

    private static Stream<RuntimeException> blobPersistenceFailures() {
        return Stream.of(new IllegalStateException("disk failure"),
                new RuntimeException("Failed to clean up blob temporary file",
                        new IOException("temporary cleanup denied")));
    }

    @Test void mapsOptionalMetadataWithoutInventingLegacyMethod() throws Exception {
        var mapper = new ObjectMapper();
        JourneyResponse oldResponse = mapper.readValue("""
                {"passed":true,"session_id":"s","final_url":"/","step_results":[
                {"index":0,"action":"click","ok":true,"duration_ms":1}]}
                """, JourneyResponse.class);
        assertNull(JourneyResult.from(oldResponse).stepResults().getFirst().method());
        JourneyResponse response = mapper.readValue("""
                {"passed":true,"session_id":"s","final_url":"/","step_results":[
                {"index":0,"action":"type","ok":true,"method":"js_fallback",
                "warning":"native input timed out","screenshot_error":"capture timed out"}]}
                """, JourneyResponse.class);
        var step = JourneyResult.from(response).stepResults().getFirst();
        ToolResult result = run("verified", List.of(step));
        assertFalse(result.isError());
        assertTrue(result.content().contains("js_fallback"));
        assertTrue(result.content().contains("does not prove native user interaction works"));
        var meta = saved().items().getFirst().meta();
        assertEquals("js_fallback", meta.get("method"));
        assertEquals("native input timed out", meta.get("warning"));
        assertEquals("capture timed out", meta.get("screenshotReason"));
        // Existing construction API also remains source-compatible.
        assertNull(new JourneyResponse.JourneyStepResponse(0, "click", true, 1, null, List.of(), null).method());
    }

    @Test void rejectsEmptyAndNonImageBytesWithoutChangingFailedVerdict() {
        var steps = List.of(step(0, new byte[0]), step(1, "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ToolResult result = run("failed", steps);
        assertEquals("VERIFY_JOURNEY_ASSERTION_FAILED", result.failureCode());
        assertTrue(result.content().contains("Screenshot evidence incomplete"));
        assertEquals("failed", saved().verdict());
        assertTrue(saved().items().stream().allMatch(s -> "invalid".equals(s.meta().get("screenshotStatus"))));
        verify(evidence, never()).saveBlob(any());
    }

    @Test void singleLimitChecksDecodedBytesEvenWhenEncodedLengthFits() throws Exception {
        int limit = 5 * 1024 * 1024;
        byte[] atLimit = pngWithSize(limit);
        byte[] overLimit = pngWithSize(limit + 1);
        assertEquals(Base64.getEncoder().encodeToString(atLimit).length(),
                Base64.getEncoder().encodeToString(overLimit).length());
        var result = run("verified", List.of(step(0, atLimit), step(1, overLimit), step(2, image("png"))));
        assertFalse(result.isError());
        var items = saved().items();
        assertEquals("stored", items.get(0).meta().get("screenshotStatus"));
        assertEquals("single_image_limit_5_mib", items.get(1).meta().get("screenshotReason"));
        assertEquals("stored", items.get(2).meta().get("screenshotStatus"));
        verify(evidence, times(2)).saveBlob(any());
    }

    @Test void oversizedEncodingIsRejectedBeforeDecodeAndDoesNotConsumeBudget() throws Exception {
        var tooLong = new StepResult(0, "click", true, 0, null, List.of(), "!".repeat(7 * 1024 * 1024));
        run("verified", List.of(tooLong, step(1, image("png"))));
        assertEquals("single_image_limit_5_mib", saved().items().getFirst().meta().get("screenshotReason"));
        verify(evidence, times(1)).saveBlob(any());
    }

    @Test void totalLimitCountsDuplicateReferencesAndStillAcceptsLaterSmallerImage() throws Exception {
        int limit = 5 * 1024 * 1024;
        byte[] large = pngWithSize(limit);
        byte[] almostLarge = pngWithSize(limit - 1000);
        byte[] small = pngWithSize(1000);
        // Exactly 20 MiB archived: duplicate 5 MiB x3 + (5 MiB-1000) + 1000.
        var result = run("verified", List.of(step(0, large), step(1, large), step(2, large),
                step(3, almostLarge), step(4, large), step(5, small), step(6, image("png"))));
        assertFalse(result.isError());
        var items = saved().items();
        assertEquals("journey_image_limit_20_mib", items.get(4).meta().get("screenshotReason"));
        assertEquals("stored", items.get(5).meta().get("screenshotStatus"));
        assertEquals("journey_image_limit_20_mib", items.get(6).meta().get("screenshotReason"));
        verify(evidence, times(5)).saveBlob(any());
    }

    @Test void databaseFailureKeepsRealVerdictAndAssistedInteractionWarning() throws Exception {
        when(evidence.save(any())).thenThrow(new IllegalStateException("database failure"));
        var assisted = new StepResult(0, "type", true, 1, null, List.of(),
                Base64.getEncoder().encodeToString(image("png")), "js_fallback", "assisted", null);
        ToolResult result = run("verified", List.of(assisted));
        assertEquals("EVIDENCE_PERSIST_FAILED", result.failureCode());
        assertTrue(result.content().contains("verdict 'verified'"));
        assertTrue(result.content().contains("js_fallback"));
        assertFalse(result.isRetryable());
        verify(evidence).saveBlob(any());
        verify(verifier, times(1)).verify(any(), anyString());
    }

    @Test void httpJourneyWithoutScreenshotsKeepsCommandEvidenceAndHasNoImageGap() {
        var http = mock(HttpApiVerifier.class);
        when(http.verify(any(), anyString())).thenReturn(new JourneyResult("verified", null,
                List.of(new StepResult(0, "http_get", true, 1, null, List.of(), null)), Map.of()));
        tool = createTool(evidence, http);
        var result = tool.call(ToolInput.from(Map.of("journey", List.of(Map.of("action", "http_get", "url", "/")),
                        "verification_mode", "http_api", "base_url", "http://127.0.0.1:8080")),
                ToolUseContext.of(workspace.toString(), "test-http"));
        assertFalse(result.isError(), result.content());
        assertFalse(result.content().contains("Screenshot evidence incomplete"));
        assertEquals("command", saved().items().getFirst().type());
        assertFalse(saved().items().getFirst().meta().containsKey("screenshotStatus"));
        verify(evidence, never()).saveBlob(any());
        verifyNoInteractions(launcher);
    }

    @Test void actualBytesRoundTripThroughTemporaryDatabaseBlobAndPreview() throws Exception {
        var resolver = new DatabaseResolver("", workspace.toString());
        var sqlite = new SqliteConfig(resolver);
        try {
            var source = sqlite.getProjectDataSource(workspace);
            var jdbc = new JdbcTemplate(source);
            new V007_AddEvidenceTables(jdbc).execute();
            if (jdbc.queryForList("PRAGMA table_info(evidence_bundles)").stream()
                    .noneMatch(row -> "run_id".equals(row.get("name")))) {
                jdbc.execute("ALTER TABLE evidence_bundles ADD COLUMN run_id TEXT");
            }
            var store = new EvidenceStore(jdbc, sqlite, resolver, new DataSourceTransactionManager(source),
                    new ObjectMapper(), new SensitiveDataFilter(), workspace.resolve("blobs"));
            tool = createTool(store);
            byte[] jpeg = image("jpeg");
            byte[] png = image("png");
            var outcome = run("verified", List.of(step(0, jpeg), step(1, png)));
            assertFalse(outcome.isError(), outcome.content());
            var bundle = store.findBySession("test-evidence").getFirst();
            assertEquals("verified", bundle.verdict());
            assertEquals("test-run", bundle.runId());
            var controller = new EvidenceController(store);
            for (int i = 0; i < 2; i++) {
                byte[] original = i == 0 ? jpeg : png;
                var item = bundle.items().get(i);
                String expectedHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
                assertEquals(expectedHash, item.blobSha256());
                var preview = controller.getBlob(item.blobSha256(), true);
                assertArrayEquals(original, preview.getBody());
                assertEquals(i == 0 ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG, preview.getHeaders().getContentType());
                var download = controller.getBlob(item.blobSha256());
                assertEquals(MediaType.APPLICATION_OCTET_STREAM, download.getHeaders().getContentType());
                assertArrayEquals(original, download.getBody());
            }
        } finally {
            sqlite.destroy();
        }
    }

    private ToolResult run(String verdict, List<StepResult> steps) {
        when(verifier.verify(any(), anyString())).thenReturn(new JourneyResult(verdict,
                verdict.equals("failed") ? "assertion failed" : null, steps, Map.of()));
        return tool.call(ToolInput.from(Map.of("journey", List.of(Map.of("action", "navigate", "url", "/")),
                        "verification_mode", "browser")),
                ToolUseContext.of(workspace.toString(), "test-evidence").withCurrentRunId("test-run"));
    }

    private EvidenceBundle saved() {
        var capture = ArgumentCaptor.forClass(EvidenceBundle.class);
        verify(evidence).save(capture.capture());
        return capture.getValue();
    }

    private static StepResult step(int index, byte[] image) {
        return new StepResult(index, "click", true, 1, null, List.of(), Base64.getEncoder().encodeToString(image));
    }

    private static byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }

    /** Valid tiny PNG with an uncompressed ancillary text chunk to exercise exact byte limits. */
    private static byte[] pngWithSize(int size) throws Exception {
        byte[] png = image("png");
        byte[] data = new byte[size - png.length - 12];
        Arrays.fill(data, (byte) 'a');
        data[0] = 'k'; data[1] = 0;
        byte[] type = {'t', 'E', 'X', 't'};
        var crc = new CRC32(); crc.update(type); crc.update(data);
        var output = new ByteArrayOutputStream(size);
        var stream = new DataOutputStream(output);
        stream.write(png, 0, 33); // after IHDR, before IDAT
        stream.writeInt(data.length); stream.write(type); stream.write(data); stream.writeInt((int) crc.getValue());
        stream.write(png, 33, png.length - 33);
        return output.toByteArray();
    }
}
