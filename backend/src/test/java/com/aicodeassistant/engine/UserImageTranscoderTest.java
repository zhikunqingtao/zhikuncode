package com.aicodeassistant.engine;

import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class UserImageTranscoderTest {
    static final String URL = "https://bucket.oss-cn-beijing.aliyuncs.com/zhikuncode-artifacts/clipboard/";
    static OssPublishProperties properties() {
        var props = new OssPublishProperties(); props.setBucket("bucket"); props.setEndpoint("https://oss-cn-beijing.aliyuncs.com"); return props;
    }
    static ModelCapabilities caps(int count) {
        return new ModelCapabilities("kimi-k3", "Kimi", 65536, 1000000, true, true, true, count,
                true, 0, 0, 3.5, false, ModelCapabilities.ImageInputMode.BASE64_ONLY);
    }
    static Message.UserMessage message(String id, String url) {
        return new Message.UserMessage(id, Instant.now(), List.of(new ContentBlock.TextBlock("look"),
                ContentBlock.ImageBlock.fromUrl("image/png", url)), null, null, Map.of("preserved", true));
    }
    static byte[] png(int size) throws IOException {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB); var random = new Random(19);
        for (int y=0;y<size;y++) for (int x=0;x<size;x++) image.setRGB(x,y,random.nextInt(0x1000000));
        var output = new ByteArrayOutputStream(); ImageIO.write(image,"png",output); return output.toByteArray();
    }
    static class Stub extends UserImageTranscoder {
        int downloads; byte[] bytes; boolean fail;
        Stub(byte[] bytes) { super(properties()); this.bytes=bytes; }
        @Override protected DownloadedImage fetchImage(String url, CancellationSignal signal) throws IOException {
            downloads++; if(fail) throw new IOException("unavailable"); return new DownloadedImage(bytes,"image/png");
        }
    }
    static long images(Message message) { return ((Message.UserMessage)message).content().stream().filter(ContentBlock.ImageBlock.class::isInstance).count(); }

    @Test void legalLargeImageIsConvertedWithoutChangingHistoryAndUsesSuccessCache() throws Exception {
        byte[] bytes=png(640); assertThat(bytes.length).isGreaterThan(1_125_000);
        var transcoder=new Stub(bytes);var original=message("current",URL+"current.png");
        var first=transcoder.transcode(List.of(original),caps(8));
        transcoder.transcode(List.of(original),caps(8));
        assertThat(transcoder.downloads).isEqualTo(1);
        var copy=(Message.UserMessage)first.messages().getFirst();
        var image=(ContentBlock.ImageBlock)copy.content().get(1);
        assertThat(Base64.getDecoder().decode(image.base64Data())).isEqualTo(bytes);
        assertThat(image.width()).isEqualTo(640);assertThat(image.url()).isNull();
        assertThat(copy.meta()).isEqualTo(original.meta());
        assertThat(((ContentBlock.ImageBlock)original.content().get(1)).url()).isEqualTo(URL+"current.png");
    }
    @Test void currentAttachmentSurvivesFullHistoryEvenAfterNewerToolMessage() throws Exception {
        var transcoder=new Stub(png(8));var messages=new ArrayList<Message>();
        for(int i=0;i<8;i++)messages.add(message("old"+i,URL+i+".png"));
        messages.add(message("current",URL+"current.png"));
        messages.add(new Message.UserMessage("tool",Instant.now(),List.of(new ContentBlock.ImageBlock("image/png",
                Base64.getEncoder().encodeToString(png(8)))),null,"tool-assistant"));
        var result=transcoder.transcode(messages,caps(8),"current",CancellationSignal.none(),100000);
        assertThat(images(result.messages().get(8))).isEqualTo(1);
        assertThat(result.messages().stream().mapToLong(UserImageTranscoderTest::images).sum()).isEqualTo(8);
        assertThat(images(result.messages().getFirst())).isZero();
        assertThat(transcoder.downloads).isEqualTo(7); // Inline tool image shares the same count.
        assertThat(result.warnings()).hasSize(2);
    }
    @Test void currentFailureStopsAndIsNotCachedButHistoryFailureIsVisible() throws Exception {
        var transcoder=new Stub(png(8));transcoder.fail=true;
        var original=message("current",URL+"current.png");
        assertThatThrownBy(()->transcoder.transcode(List.of(original),caps(8))).isInstanceOf(LlmApiException.class).hasMessageContaining("本轮已停止");
        var historical=transcoder.transcode(List.of(original),caps(8),"new-text-request",CancellationSignal.none(),100000);
        assertThat(historical.warnings()).hasSize(1);assertThat(images(historical.messages().getFirst())).isZero();
        transcoder.fail=false;assertThat(transcoder.transcode(List.of(original),caps(8)).convertedCount()).isEqualTo(1);
        assertThat(transcoder.downloads).isEqualTo(3);
    }
    @Test void externalCurrentUrlIsRejectedWithoutFetch() throws Exception {
        var transcoder=new Stub(png(8));
        assertThatThrownBy(()->transcoder.transcode(List.of(message("current","https://untrusted.example/x")),caps(8)))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("受信任");
        assertThat(transcoder.downloads).isZero();
    }
    @Test void mandatoryImagesAreNeverPartiallyAccepted() throws Exception {
        var transcoder=new Stub(png(8));var user=new Message.UserMessage("current",Instant.now(),
                List.of(ContentBlock.ImageBlock.fromUrl("image/png",URL+"a.png"),ContentBlock.ImageBlock.fromUrl("image/png",URL+"b.png")),null,null);
        assertThatThrownBy(()->transcoder.transcode(List.of(user),caps(1))).isInstanceOf(LlmApiException.class);
        assertThat(transcoder.downloads).isZero();
    }
    @Test void historyUsesOnlyBudgetLeftAfterCurrentImage() throws Exception {
        var transcoder=new Stub(png(16));
        var result=transcoder.transcode(List.of(message("old",URL+"old.png"),message("current",URL+"new.png")),
                caps(8),"current",CancellationSignal.none(),1500);
        assertThat(images(result.messages().get(1))).isEqualTo(1);assertThat(images(result.messages().getFirst())).isZero();
        assertThat(result.warnings()).hasSize(1);
    }
    @Test void corruptCurrentImageFailsAndCorruptHistoryIsOmitted() {
        var transcoder = new Stub(new byte[]{(byte)0x89, 'P', 'N', 'G'});
        var messages = List.<Message>of(message("current", URL + "broken.png"));
        assertThatThrownBy(() -> transcoder.transcode(messages, caps(8)))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("图片头部无法读取");
        var result = transcoder.transcode(messages, caps(8), "new-request", CancellationSignal.none(), 100000);
        assertThat(result.warnings()).hasSize(1);
        assertThat(images(result.messages().getFirst())).isZero();
    }

    @Test void uploadLimitIsInclusiveAndOverLimitStops() throws Exception {
        byte[] bytes=Arrays.copyOf(png(8),(int)UserImageTranscoder.MAX_DOWNLOAD_BYTES);
        var transcoder=new Stub(bytes);
        assertThat(transcoder.transcode(List.of(message("current",URL+"a.png")),caps(8)).convertedCount()).isEqualTo(1);
        transcoder.bytes=Arrays.copyOf(bytes,bytes.length+1);
        assertThatThrownBy(()->transcoder.transcode(List.of(message("current",URL+"b.png")),caps(8)))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("5 MiB");
    }
    @Test void inlineAttachmentsRetainTenMiBLimitWithoutRelaxingDownloadLimit() throws Exception {
        byte[] png = png(1500);
        assertThat(png.length).isGreaterThan((int) UserImageTranscoder.MAX_DOWNLOAD_BYTES)
                .isLessThan(UserImageTranscoder.MAX_INLINE_BYTES);
        var transcoder = new Stub(png);
        for (byte[] bytes : List.of(png, Arrays.copyOf(png, UserImageTranscoder.MAX_INLINE_BYTES))) {
            String data = Base64.getEncoder().encodeToString(bytes);
            var user = new Message.UserMessage("current", Instant.now(),
                    List.of(new ContentBlock.ImageBlock("image/png", data)), null, null);
            var prepared = transcoder.transcode(List.of(user), caps(8));
            var image = (ContentBlock.ImageBlock) ((Message.UserMessage) prepared.messages().getFirst()).content().getFirst();
            assertThat(image.base64Data()).isEqualTo(data);
        }
        var oversized = new Message.UserMessage("current", Instant.now(), List.of(new ContentBlock.ImageBlock(
                "image/png", Base64.getEncoder().encodeToString(Arrays.copyOf(png, UserImageTranscoder.MAX_INLINE_BYTES + 1)))), null, null);
        assertThatThrownBy(() -> transcoder.transcode(List.of(oversized), caps(8)))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("10 MiB");
        assertThat(transcoder.downloads).isZero();
        assertThatThrownBy(() -> transcoder.transcode(List.of(message("current", URL + "large.png")), caps(8)))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("5 MiB");
    }

    @Test void downloaderRejectsRedirectAndStreamsBeyondLimitAndCancelsActiveCall() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();var transcoder=new UserImageTranscoder(properties());String url=server.url("/image").toString();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location",server.url("/other")));
            assertThatThrownBy(()->transcoder.fetchImage(url,CancellationSignal.none())).isInstanceOf(IOException.class);
            assertThat(server.getRequestCount()).isEqualTo(1);
            server.enqueue(new MockResponse().addHeader("Content-Type","image/png")
                    .setChunkedBody(new Buffer().write(new byte[(int)UserImageTranscoder.MAX_DOWNLOAD_BYTES+1]),8192));
            assertThatThrownBy(()->transcoder.fetchImage(url,CancellationSignal.none())).isInstanceOf(IOException.class);
            server.takeRequest();server.takeRequest();
            server.enqueue(new MockResponse().addHeader("Content-Type","image/png").setBody("waiting").setBodyDelay(2,TimeUnit.SECONDS));
            var signal=new AbortContext();
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var future=executor.submit(()->transcoder.fetchImage(url,signal));
                assertThat(server.takeRequest(2,TimeUnit.SECONDS)).isNotNull();signal.abort(AbortReason.USER_INTERRUPT);
                assertThatThrownBy(()->future.get(1,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            }
        }
    }
    @Test void unsafeHistoricalHeaderCanBeOmittedInsteadOfPoisoningPhaseOne() throws Exception {
        byte[] bytes = png(16);
        java.nio.ByteBuffer.wrap(bytes).putInt(16, 100_000).putInt(20, 100_000);
        var old = new Message.UserMessage("old", Instant.now(), List.of(
                new ContentBlock.ImageBlock("image/png", Base64.getEncoder().encodeToString(bytes))), null, null);
        var guarded = new TokenBudgetGuard().enforcePhase1(List.of(old), 100000, 3.5, "new-request");
        var prepared = new Stub(png(8)).transcode(guarded.messages(), caps(8), "new-request", CancellationSignal.none(), 100000);
        assertThat(prepared.warnings()).hasSize(1);
        assertThat(images(prepared.messages().getFirst())).isZero();
    }

    @Test void phaseOneCannotStripCurrentAttachmentAfterSeveralToolTurns() throws Exception {
        var required = new Message.UserMessage("current", Instant.now(),
                List.of(new ContentBlock.ImageBlock("image/png", Base64.getEncoder().encodeToString(png(16)))), null, null);
        var messages = new ArrayList<Message>(); messages.add(required);
        for (int i = 0; i < 4; i++) messages.add(new Message.UserMessage("tool" + i, Instant.now(),
                List.of(new ContentBlock.TextBlock("tool response")), null, "assistant"));
        var cleaned = new TokenBudgetGuard().enforcePhase1(messages, 10, 3.5, "current");
        assertThat(images(cleaned.messages().getFirst())).isEqualTo(1);
    }

    @Test void urlCapableModelIsUnchanged() throws Exception {
        var transcoder=new Stub(png(8));var original=List.<Message>of(message("current",URL+"image.png"));
        var caps=new ModelCapabilities("url","url",8192,200000,true,false,true,8,true,0,0);
        assertThat(transcoder.transcode(original,caps).messages()).isSameAs(original);assertThat(transcoder.downloads).isZero();
    }
}
