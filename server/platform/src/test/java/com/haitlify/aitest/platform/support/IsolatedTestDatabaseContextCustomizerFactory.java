package com.haitlify.aitest.platform.support;

import java.util.List;
import java.util.Locale;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.support.TestPropertySourceUtils;

/**
 * Gives every test class that runs on the H2 {@code test} profile its own in-memory database.
 *
 * <p>Why: the {@code test} profile points at a single {@code jdbc:h2:mem:aitest} database that lives for
 * the whole JVM ({@code DB_CLOSE_DELAY=-1}). All test classes therefore shared one schema and one data
 * set, so a class that left rows behind - {@code project_members} pointing at {@code platform_users}, or
 * {@code ai_coverage_summaries} pointing at {@code ai_analysis_runs} - made whichever class ran next fail
 * on a foreign key violation, or assert against stale content. The class order differs between macOS and
 * the Linux CI runner, so the same commit could be green locally and red in CI.
 *
 * <p>With one database per top-level test class, classes can no longer contaminate each other and the
 * suite result stops depending on execution order. Nested classes share their top-level class's database.
 * Pass {@code -Dai.test.shared-db=true} to get the old shared database back, for example when hunting
 * cross-class leakage on purpose.
 *
 * <p>Only classes with {@code @ActiveProfiles("test")} are isolated: the opt-in {@code live} gates keep
 * the real datasource they are documented to run against.
 */
public class IsolatedTestDatabaseContextCustomizerFactory implements ContextCustomizerFactory {

    private static final String SHARED_DATABASE_SWITCH = "ai.test.shared-db";
    private static final String TEST_PROFILE = "test";
    private static final String H2_URL_TEMPLATE =
        "jdbc:h2:mem:aitest-%s;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        if (Boolean.getBoolean(SHARED_DATABASE_SWITCH) || !usesTestProfile(testClass)) {
            return null;
        }
        String url = H2_URL_TEMPLATE.formatted(databaseSuffix(testClass));
        return (context, clazz) -> TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
            context, "spring.datasource.url=" + url
        );
    }

    private boolean usesTestProfile(Class<?> testClass) {
        for (Class<?> current = testClass; current != null; current = current.getEnclosingClass()) {
            ActiveProfiles profiles = AnnotatedElementUtils.findMergedAnnotation(current, ActiveProfiles.class);
            if (profiles != null && List.of(profiles.value()).contains(TEST_PROFILE)) {
                return true;
            }
        }
        return false;
    }

    private String databaseSuffix(Class<?> testClass) {
        Class<?> topLevel = testClass;
        while (topLevel.getEnclosingClass() != null) {
            topLevel = topLevel.getEnclosingClass();
        }
        return topLevel.getSimpleName().toLowerCase(Locale.ROOT)
            + "-" + Integer.toHexString(topLevel.getName().hashCode());
    }
}
