package com.aicodeassistant.artifact.meoo;

import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@Service
public class MeooAccessVerifier {
    public static String trustedProject(String value,String expectedProject) {
        try {
            URI u=URI.create(value);
            if(!"https".equals(u.getScheme()) || !"meoo.com".equalsIgnoreCase(u.getHost())
                    || u.getPort()!=-1 || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null
                    || !u.getPath().equals("/chat/"+expectedProject)) throw new IllegalArgumentException();
            return u.toString();
        } catch(Exception e) { throw new MeooException("MEOO_PROJECT_URL_INVALID"); }
    }
    public static URI trustedSite(String value) {
        try {
            URI u=URI.create(value);
            if(!"https".equals(u.getScheme()) || u.getHost()==null || !u.getHost().matches("[a-zA-Z0-9-]+\\.meoo\\.(?:fun|pub)")
                    || u.getPort()!=-1 || u.getUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null
                    || !(u.getPath().isEmpty() || u.getPath().equals("/"))) throw new IllegalArgumentException();
            return u;
        } catch(Exception e) { throw new MeooException("MEOO_ACCESS_URL_INVALID"); }
    }
    /** The platform reserializes HTML and adds its favicon, watermark and security script. */
    public static String entryHash(byte[] html) {
        var document=org.jsoup.Jsoup.parse(new String(html,java.nio.charset.StandardCharsets.UTF_8));
        document.outputSettings().prettyPrint(false);
        document.select("#meoo-brand-watermark,script#meoo-baxia-web-security,link[rel=icon][href='/favicon.ico?v=0']").remove();
        prune(document);
        return MeooPublicationPolicy.hash(document.selectFirst("html").outerHtml().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void prune(org.jsoup.nodes.Node node) {
        for(var child:new java.util.ArrayList<>(node.childNodes())) {
            if(child instanceof org.jsoup.nodes.Comment || child instanceof org.jsoup.nodes.TextNode text && text.isBlank()) child.remove();
            else prune(child);
        }
    }
    public boolean verify(String url,String expectedHash) {
        try {
            URI uri=trustedSite(url);
            for(InetAddress address:InetAddress.getAllByName(uri.getHost()))
                if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress() || (address.getAddress().length==16 && (address.getAddress()[0] & 0xfe)==0xfc)) return false;
            return verifyResponse(uri,expectedHash,Duration.ofSeconds(30));
        } catch(Exception e) { return false; }
    }
    /** Bound headers and body together; request timeout alone ends when headers arrive. */
    static boolean verifyResponse(URI uri,String expectedHash,Duration timeout) {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        var request=HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent","ZhikunCode-Meoo-Publication/1.0").GET().build();
        var task=new FutureTask<Boolean>(() -> {
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream body=response.body()) {
                if(response.statusCode()<200 || response.statusCode()>=300) return false;
                byte[] bytes=body.readNBytes(10*1024*1024+1);
                if(bytes.length>10*1024*1024) return false;
                String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                if(text.contains("meoo.com/login") || text.contains("meoo.com/auth")) return false;
                return expectedHash==null || expectedHash.equals(entryHash(bytes));
            }
        });
        Thread.ofVirtual().start(task);
        try {
            return task.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch(Exception e) { return false; }
        finally {
            task.cancel(true);
            client.shutdownNow();
        }
    }
}
