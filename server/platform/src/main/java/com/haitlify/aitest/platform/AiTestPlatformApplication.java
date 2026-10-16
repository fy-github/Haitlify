package com.haitlify.aitest.platform;

import com.haitlify.aitest.platform.document.FigmaDesignProperties;
import com.haitlify.aitest.platform.jira.JiraProperties;
import com.haitlify.aitest.platform.webautomation.WebAutomationProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({JiraProperties.class, WebAutomationProperties.class, FigmaDesignProperties.class})
@EnableScheduling
public class AiTestPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTestPlatformApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
