package com.haitlify.aitest.platform;

import com.haitlify.aitest.platform.document.FigmaDesignProperties;
import com.haitlify.aitest.platform.jira.JiraProperties;
import com.haitlify.aitest.platform.startup.IsolatedDataSourcePostProcessor;
import com.haitlify.aitest.platform.startup.IsolatedEnvironmentStartupGuard;
import com.haitlify.aitest.platform.webautomation.WebAutomationProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({JiraProperties.class, WebAutomationProperties.class, FigmaDesignProperties.class})
@EnableScheduling
public class AiTestPlatformApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AiTestPlatformApplication.class);
        // 计划 §7.1：服务启动入口复验目标环境与资源命名，缺失或不匹配时拒绝启动。
        application.addListeners(new IsolatedEnvironmentStartupGuard());
        // 环境属性只能证明「声明的目标」；数据源 bean 完成初始化时再校验一次「实际生效的目标」，
        // 挡住通过挂载配置/间接属性（dataSourceClassName、dataSourceProperties 等）改写连接目标的路径。
        application.addInitializers(dataSourceGuardInitializer());
        application.run(args);
    }

    static ApplicationContextInitializer<ConfigurableApplicationContext> dataSourceGuardInitializer() {
        return context -> context.getBeanFactory().addBeanPostProcessor(new IsolatedDataSourcePostProcessor());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
