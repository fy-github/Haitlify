package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Regression coverage for real Python pipe cleanup; no mock Future stands in for the reader. */
@EnabledIfSystemProperty(named = "mcp.sdk.python", matches = ".+")
class McpPythonProcessCleanupTest {
    private static final String FIXTURE = """
        import sys, time
        mode = sys.argv[1]
        if mode == 'no-output':
            time.sleep(30)
        elif mode == 'partial-line':
            sys.stdout.write('partial')
            sys.stdout.flush()
            time.sleep(30)
        elif mode == 'eof':
            pass
        elif mode == 'interrupt':
            time.sleep(30)
        else:
            raise SystemExit(2)
        """;

    @Test
    @Timeout(15)
    void noOutputReaderIsTerminatedBeforePipesAreClosed() throws Exception {
        assertCleanupBounded("no-output");
    }

    @Test
    @Timeout(15)
    void partialLineReaderIsTerminatedBeforePipesAreClosed() throws Exception {
        assertCleanupBounded("partial-line");
    }

    @Test
    @Timeout(15)
    void eofReaderCompletesAndStillCleansUpRealProcess() throws Exception {
        Path scratch = Files.createTempDirectory("mcp-process-eof-");
        McpPythonProcess process = start(scratch, "eof");
        try {
            assertThat(process.readLine(Duration.ofSeconds(2))).isNull();
        } finally {
            process.close();
            deleteScratch(scratch);
        }
        assertThat(process.processAlive()).isFalse();
        assertThat(process.readerTerminated()).isTrue();
    }

    @Test
    @Timeout(15)
    void interruptedReaderStillHasBoundedOwnedProcessCleanup() throws Exception {
        Path scratch = Files.createTempDirectory("mcp-process-interrupt-");
        McpPythonProcess process = start(scratch, "interrupt");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();
        Future<?> read = caller.submit(() -> {
            try {
                process.readLine(Duration.ofSeconds(10));
                fail("blocking real reader unexpectedly returned");
            } catch (InterruptedException e) {
                observed.set(true);
                interrupted.countDown();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                fail("reader failed for the wrong reason", e);
            }
        });
        try {
            Thread.sleep(100);
            read.cancel(true);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(observed).isTrue();
        } finally {
            process.close();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            deleteScratch(scratch);
        }
        assertThat(process.processAlive()).isFalse();
        assertThat(process.readerTerminated()).isTrue();
    }

    private static void assertCleanupBounded(String mode) throws Exception {
        Path scratch = Files.createTempDirectory("mcp-process-" + mode + "-");
        McpPythonProcess process = start(scratch, mode);
        try {
            assertThatThrownBy(() -> process.readLine(Duration.ofMillis(100)))
                .isInstanceOf(TimeoutException.class);
        } finally {
            process.close();
            deleteScratch(scratch);
        }
        assertThat(process.processAlive()).isFalse();
        assertThat(process.readerTerminated()).isTrue();
    }

    private static McpPythonProcess start(Path scratch, String mode) throws IOException {
        Path script = scratch.resolve("fixture.py");
        Files.writeString(script, FIXTURE, StandardCharsets.UTF_8);
        String python = System.getProperty("mcp.sdk.python");
        if (python == null || python.isBlank()) python = "/usr/bin/python3";
        ProcessBuilder builder = new ProcessBuilder(python, "-I", script.toString(), mode);
        builder.directory(scratch.toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("HOME", scratch.toString(), "TMPDIR", scratch.toString(), "PATH", "/usr/bin:/bin"));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return McpPythonProcess.start(builder);
    }

    private static void deleteScratch(Path scratch) throws IOException {
        try (var paths = Files.walk(scratch)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            });
        }
    }
}
