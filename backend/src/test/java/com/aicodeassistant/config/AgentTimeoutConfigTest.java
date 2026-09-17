package com.aicodeassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTimeoutConfigTest {
    @Test
    void shippedWaitBudgetCoversChildDeadlineAndGracefulExit() throws Exception {
        var config = bind(new MockEnvironment());
        assertThat(config.getMaxWaitMinutes() * 60)
                .isGreaterThan(config.getMaxSeconds() + config.getGracefulShutdownSeconds());
    }

    @Test
    void explicitWaitBudgetRemainsConfigurable() throws Exception {
        var config = bind(new MockEnvironment().withProperty("agent.timeout.max-wait-minutes", "2"));
        assertThat(config.getMaxWaitMinutes()).isEqualTo(2);
    }

    private AgentTimeoutConfig bind(MockEnvironment environment) throws Exception {
        for (var source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("agent.timeout", Bindable.of(AgentTimeoutConfig.class)).get();
    }
}
