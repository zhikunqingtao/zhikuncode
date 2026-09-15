package com.aicodeassistant.authorization;

import com.aicodeassistant.artifact.meoo.*;
import com.aicodeassistant.config.meoo.MeooPublishProperties;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Read-only cloud reconciliation for a previously created acceptance site; never deploys. */
@EnabledIfSystemProperty(named="meoo.live.recheck",matches="campus|node-first|node-second")
class MeooLiveAccessIT {
    @Test void recoverExistingReleaseAndVerifyAnonymousAccessWithoutPublishing() throws Exception {
        String name=System.getProperty("meoo.live.recheck");
        Path base=Path.of(System.getProperty("meoo.live.base")).toRealPath();
        Path dir=base.resolve(name+"-publication");
        var ds=new SingleConnectionDataSource("jdbc:sqlite:"+dir.resolve(".ai-code-assistant/data.db"),true);
        Path control=Files.createTempDirectory("meoo-access-control-");
        try {
            var jdbc=new JdbcTemplate(ds);var rows=jdbc.queryForList("SELECT * FROM meoo_publications");assertThat(rows).hasSize(1);
            var row=rows.getFirst();String project=(String)row.get("project_id");assertThat(project).matches("[A-Za-z0-9_-]{1,128}");
            var context=ToolUseContext.of(base.toString(),(String)row.get("session_id")).withCurrentRunId((String)row.get("run_id")).withToolUseId("recheck-access");
            var props=new MeooPublishProperties();props.setEnabled(true);
            assertThat(props.credential().account()).isEqualTo(row.get("account_id"));
            var json=new ObjectMapper().findAndRegisterModules();
            var cli=new MeooCliClient(props,new ManagedProcessRunner(),json);
            Path home=Files.createDirectory(control.resolve("home"));
            Files.writeString(control.resolve(".env"),"MEOO_PROJECT_URL_ID="+project+"\n");
            var current=cli.execute(List.of("projects","current"),control,home,context);
            assertThat(current.path("isDeployed").asBoolean()).isTrue();
            assertThat(current.path("latestVersion").asText()).isEqualTo(row.get("version"));
            String url=current.path("accessUrl").asText();MeooAccessVerifier.trustedSite(url);
            String expected="static".equals(row.get("runtime"))?MeooAccessVerifier.entryHash(Files.readAllBytes(base.resolve("campus/index.html"))):null;
            boolean verified=new MeooAccessVerifier().verify(url,expected);
            var store=new MeooPublicationStore(jdbc);
            store.finish((String)row.get("id"),verified?"public_verified":"deployed_unverified",(String)row.get("version"),url,verified?null:"MEOO_ACCESS_UNVERIFIED");
            Files.writeString(dir.resolve("access-recheck.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(store.get((String)row.get("id"))));
            System.out.println("MEOO_ACCESS_RECHECK "+url+" verified="+verified);
            assertThat(verified).isTrue();
        } finally {
            ds.destroy();try(var paths=Files.walk(control)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
