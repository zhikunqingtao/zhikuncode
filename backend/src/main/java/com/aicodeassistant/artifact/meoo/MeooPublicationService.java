package com.aicodeassistant.artifact.meoo;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

@Service
public class MeooPublicationService {
    private final MeooPublicationPolicy policy;
    private final MeooPublicationStore store;
    private final MeooCliClient cli;
    private final MeooAccessVerifier verifier;
    public MeooPublicationService(MeooPublicationPolicy policy,MeooPublicationStore store,MeooCliClient cli,MeooAccessVerifier verifier) { this.policy=policy;this.store=store;this.cli=cli;this.verifier=verifier; }
    public MeooPublicationStore.Publication publish(ToolInput input,ToolUseContext context) {
        var snapshot=policy.inspect(input,context,true);
        if(!snapshot.sha256().equals(input.getString("_approved_sha256","")) || !snapshot.account().equals(input.getString("_approved_account",""))) throw new MeooException("MEOO_APPROVED_SNAPSHOT_REQUIRED");
        var claim=store.claim(context,snapshot);
        if(!claim.created()) return claim.publication();
        String id=claim.publication().id(); Path stage=null; boolean remoteStarted=false;
        String version=null,url=null;
        try {
            stage=Files.createTempDirectory("zhikun-meoo-");
            Path control=Files.createDirectory(stage.resolve("control"));
            Path home=Files.createDirectory(stage.resolve("home"));
            Path payload=Files.createDirectory(stage.resolve("payload"));
            Path contents=snapshot.runtime().equals("static")?payload.resolve("dist"):payload;
            policy.stage(snapshot,contents);
            var rechecked=policy.inspect(input,context,true);
            if(!snapshot.facts().equals(rechecked.facts())) throw new MeooException("MEOO_SNAPSHOT_CHANGED");
            cli.checkVersion(control,home,context);
            progress(context,"正在创建独立秒悟站点");
            remoteStarted=true;
            var created=cli.execute(List.of("projects","create",snapshot.name()),control,home,context);
            String project=created.path("urlId").asText();
            if(!project.matches("[A-Za-z0-9_-]{1,128}")) throw new MeooException("MEOO_INVALID_RESPONSE");
            store.projectCreated(id,project);
            progress(context,snapshot.runtime().equals("static")?"正在发布静态网站":"正在远程构建并发布全栈应用");
            List<String> args=new ArrayList<>(List.of("deploy","--project",project,"--runtime",snapshot.runtime(),"--force"));
            if(snapshot.runtime().equals("static")) args.addAll(List.of("--skip-build","--skip-push"));
            var result=cli.execute(args,payload,home,context);
            version=result.path("version").asText();
            url=result.path("accessUrl").asText();
            MeooAccessVerifier.trustedSite(url);
            if(version.isBlank()) throw new MeooException("MEOO_INVALID_RESPONSE");
            String expected=snapshot.runtime().equals("static")?MeooAccessVerifier.entryHash(Files.readAllBytes(contents.resolve("index.html"))):null;
            progress(context,"部署已完成，正在检查匿名公网访问");
            boolean verified=verifier.verify(url,expected);
            store.finish(id,verified?"public_verified":"deployed_unverified",version,url,verified?null:"MEOO_ACCESS_UNVERIFIED");
        } catch(Exception failure) {
            String code=failure instanceof MeooException m?m.code():"MEOO_INTERNAL_ERROR";
            boolean unknown=remoteStarted && !Set.of("MEOO_QUOTA_EXCEEDED","MEOO_AUTH_FAILED","MEOO_BUILD_FAILED").contains(code);
            // Untrusted provider URLs are never persisted or sent to the UI.
            if(url!=null) try { MeooAccessVerifier.trustedSite(url); } catch(MeooException e) { url=null; }
            store.finish(id,unknown?"unknown":"failed",version,url,code);
        } finally {
            if(stage!=null) try(var paths=Files.walk(stage)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } catch(Exception ignored) { }
        }
        return store.get(id);
    }
    private static void progress(ToolUseContext context,String text) {
        if(context.onProgress()!=null) try { context.onProgress().accept(text); } catch(RuntimeException ignored) { }
    }
}
