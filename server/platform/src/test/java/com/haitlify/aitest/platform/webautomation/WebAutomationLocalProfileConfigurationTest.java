package com.haitlify.aitest.platform.webautomation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class WebAutomationLocalProfileConfigurationTest {

    @Test
    void localProfileEnablesTheWebRunnerProxyByDefault() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
            "application-local",
            new ClassPathResource("application-local.yml")
        )) {
            sources.addLast(source);
        }

        WebAutomationProperties properties = new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources)
        )
            .bind("app.web-automation", WebAutomationProperties.class)
            .orElse(new WebAutomationProperties(null));

        assertTrue(properties.runner().enabled());
        assertEquals("http://127.0.0.1:19323", properties.runner().baseUrl());
    }

    @Test
    void localProfileUsesReopenableH2FileSettings() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
            "application-local",
            new ClassPathResource("application-local.yml")
        )) {
            sources.addLast(source);
        }

        String url = new Binder(
            ConfigurationPropertySources.from(sources),
            new PropertySourcesPlaceholdersResolver(sources)
        )
            .bind("spring.datasource.url", String.class)
            .orElse("");

        assertTrue(url.contains("AUTO_SERVER=TRUE"));
        assertFalse(url.contains("DB_CLOSE_ON_EXIT=FALSE"));
    }
}
