package com.example.basilience;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background execution for report processing and export.
 *
 * Workers never create or touch Android Views. The one step that needs a
 * View (rendering an export chart to a Bitmap) is handed to the main thread
 * with callOnMain(), and only the finished Bitmap comes back.
 *
 * Tasks are started with execute(), not submit(), so an uncaught error in a
 * task reaches the default handler instead of being stored and lost in a Future.
 */
final class ReportWorker {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "report-worker-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private ReportWorker() {}

    static void run(Runnable task) {
        EXECUTOR.execute(task);
    }

    static void postToMain(Runnable task) {
        MAIN.post(task);
    }

    /**
     * Runs the task on the main thread and blocks the calling worker until it
     * finishes. Must not be called from the main thread. A RuntimeException or
     * Error from the task is rethrown here unchanged, so an out-of-memory
     * error is not swallowed on the way back.
     */
    static <T> T callOnMain(Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        MAIN.post(future);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the main thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        }
    }
}
