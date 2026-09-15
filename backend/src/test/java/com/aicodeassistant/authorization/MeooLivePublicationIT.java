package com.aicodeassistant.authorization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Opt-in cloud acceptance. Never automatically answers a publication permission card. */
@EnabledIfSystemProperty(named="meoo.live.case",matches="campus|node-first|node-second")
class MeooLivePublicationIT {
    @Test void publishOnlyAfterExplicitOnceDecision() throws Exception {
        Path base=Path.of(System.getProperty("meoo.live.base")).toRealPath();
        String name=System.getProperty("meoo.live.case");
        boolean campus=name.equals("campus");
        Path output=base.resolve(name+"-publication");Files.createDirectory(output);
        // CREATE_NEW directory intentionally prevents accidental fresh resource creation on a rerun.
        try(var f=new MeooAuthorizationFlowTest.Fixture(output,true,
                base.resolve(campus?"local-campus/report.json":"local-node/report.json"),base.resolve(campus?"campus":"node"))) {
            var future=f.request("publish-"+name);
            var request=f.delivered();
            var prompt=f.json.readTree(request.promptJson());
            var review=Map.of("interactionId",request.interactionId(),"sessionId",request.sessionId(),"prompt",prompt);
            Files.writeString(output.resolve("authorization-request.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(review));
            System.out.println("MEOO_AUTHORIZATION_PENDING "+output.resolve("authorization-request.json"));
            Path decision=output.resolve("user-decision.json");
            long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
            while(!Files.exists(decision) && System.nanoTime()<deadline) Thread.sleep(500);
            if(!Files.exists(decision)) { f.decide(request,false);throw new AssertionError("Publication awaits explicit user authorization; no cloud write performed"); }
            var response=f.json.readTree(Files.readString(decision));
            assertThat(response.path("operationHash").asText()).isEqualTo(prompt.path("operationHash").asText());
            boolean allow="allow_once".equals(response.path("optionId").asText());
            f.decide(request,allow);
            var result=future.get(45,TimeUnit.MINUTES);
            Files.writeString(output.resolve("tool-result.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println("MEOO_PUBLICATION_RESULT "+output.resolve("tool-result.json"));
            assertThat(result.isError()).as("See persistent project ID and failure state; do not retry automatically").isFalse();
        }
    }
}
