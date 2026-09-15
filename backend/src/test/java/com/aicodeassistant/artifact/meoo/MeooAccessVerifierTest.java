package com.aicodeassistant.artifact.meoo;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MeooAccessVerifierTest {
    @Test void bodyStallReturnsWithinDeadlineAfterHeadersArrive() throws Exception {
        var headersSent=new CountDownLatch(1);
        var releaseBody=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try {
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write('A');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseBody.await();
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        try {
            var result=executor.submit(()->MeooAccessVerifier.verifyResponse(
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()),null,Duration.ofSeconds(1)));
            assertThat(headersSent.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(result.get(3,TimeUnit.SECONDS)).isFalse();
        } finally {
            releaseBody.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }
    @Test void completeBodyStillChecksStatusAndEntryContent() throws Exception {
        byte[] html="<html><body>App</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            try {
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/error")?503:200,html.length);
                exchange.getResponseBody().write(html);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var timeout=Duration.ofSeconds(3);
            assertThat(MeooAccessVerifier.verifyResponse(uri,MeooAccessVerifier.entryHash(html),timeout)).isTrue();
            assertThat(MeooAccessVerifier.verifyResponse(uri,"different-entry",timeout)).isFalse();
            assertThat(MeooAccessVerifier.verifyResponse(uri.resolve("/error"),null,timeout)).isFalse();
        } finally { server.stop(0); }
    }
}
