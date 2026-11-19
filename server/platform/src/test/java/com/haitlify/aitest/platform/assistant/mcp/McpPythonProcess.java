package com.haitlify.aitest.platform.assistant.mcp;

import java.io.*;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.*;

/** Test-only owner for a Python process and its pipes. Terminate the child before closing pipes. */
final class McpPythonProcess implements AutoCloseable {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final Duration JOIN_BOUND = Duration.ofSeconds(2);
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService closeExecutor = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private Future<String> activeReader;
    private boolean cleaned;
    private volatile boolean cleanupComplete;

    private McpPythonProcess(Process process, Charset charset) {
        this.process = process;
        this.writer = process.outputWriter(charset);
        this.reader = process.inputReader(charset);
    }

    static McpPythonProcess start(ProcessBuilder builder) throws IOException {
        return new McpPythonProcess(builder.start(), java.nio.charset.StandardCharsets.UTF_8);
    }

    BufferedWriter writer() { return writer; }

    String readLine(Duration timeout) throws Exception {
        Future<String> submitted;
        synchronized (stateLock) {
            if (cleaned) throw new IllegalStateException("process is already cleaned");
            if (activeReader != null && !activeReader.isDone()) throw new IllegalStateException("only one reader is supported");
            submitted = readerExecutor.submit(reader::readLine);
            activeReader = submitted;
        }
        try {
            return submitted.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            submitted.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            submitted.cancel(true);
            throw e;
        } finally {
            synchronized (stateLock) {
                if (activeReader == submitted && submitted.isDone()) activeReader = null;
            }
        }
    }

    boolean processAlive() { return process.isAlive(); }
    boolean readerTerminated() { return readerExecutor.isTerminated(); }
    boolean cleanupComplete() { return cleanupComplete; }
    boolean waitFor(Duration timeout) throws InterruptedException { return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); }
    int exitValue() { return process.exitValue(); }

    @Override
    public void close() throws Exception {
        synchronized (stateLock) {
            if (cleaned) return;
            cleaned = true;
        }
        Throwable failure = null;
        // The process owns the pipe endpoint. Kill it first so a blocked read can observe EOF.
        failure = attempt(failure, this::terminateOwnedProcess);
        failure = attempt(failure, this::cancelAndJoinReader);
        failure = attempt(failure, () -> closeBounded(writer));
        failure = attempt(failure, () -> closeBounded(reader));
        failure = attempt(failure, this::shutdownExecutors);
        if (failure != null) throwUnchecked(failure);
        if (process.isAlive()) throw new IllegalStateException("owned Python process survived bounded cleanup");
        cleanupComplete = true;
    }

    private void terminateOwnedProcess() throws Exception {
        if (!process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS))
                throw new TimeoutException("owned Python process survived forcible termination");
        }
    }

    private synchronized void cancelAndJoinReader() throws Exception {
        Future<String> pending = activeReader;
        if (pending != null && !pending.isDone()) pending.cancel(true);
        if (pending != null) {
            try { pending.get(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS); }
            catch (CancellationException | ExecutionException ignored) { }
        }
        readerExecutor.shutdownNow();
        if (!readerExecutor.awaitTermination(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("reader did not terminate after owned process cleanup");
        }
    }

    private void closeBounded(AutoCloseable closeable) throws Exception {
        Future<?> close = closeExecutor.submit(() -> {
            try { closeable.close(); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        try {
            close.get(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            if (!close.isDone()) close.cancel(true);
        }
    }

    private void shutdownExecutors() throws Exception {
        readerExecutor.shutdownNow();
        closeExecutor.shutdownNow();
        if (!readerExecutor.awaitTermination(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)
            || !closeExecutor.awaitTermination(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS))
            throw new TimeoutException("pipe cleanup executor did not terminate");
    }

    private static Throwable attempt(Throwable primary, ThrowingAction action) {
        try { action.run(); }
        catch (Throwable cleanup) {
            if (primary == null) primary = cleanup;
            else primary.addSuppressed(cleanup);
        }
        return primary;
    }

    @FunctionalInterface private interface ThrowingAction { void run() throws Throwable; }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T { throw (T) failure; }
}
