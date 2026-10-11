package com.serena.syncora.youtube;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs YouTube downloads one or two at a time and tells the UI how they are going. */
public final class DownloadQueue {

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    /** One download the user asked for. Fields are read on the UI thread; the worker updates them. */
    public static final class Job {
        public final int id;
        public final String kind;
        public final String title;
        private final DownloadQueue owner;
        final AtomicBoolean cancelled = new AtomicBoolean();
        public volatile State state = State.QUEUED;
        public volatile float progress;
        public volatile String message = "";
        public volatile String location = "";

        Job(int id, String kind, String title, DownloadQueue owner) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.owner = owner;
        }

        /** Called by the worker. Notifies the UI when the value changed by at least one percent. */
        public void setProgress(float value) {
            float v = Math.max(0f, Math.min(1f, value));
            if (Math.abs(v - progress) >= 0.01f || v >= 1f) {
                progress = v;
                owner.changed();
            }
        }

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean finished() {
            return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
        }
    }

    /** The work of one job. Runs on a worker thread and may throw. */
    public interface Work {
        void run(Job job) throws Exception;
    }

    public interface Listener {
        void onQueueChanged();
    }

    private static DownloadQueue instance;

    public static synchronized DownloadQueue get(Context context) {
        if (instance == null) instance = new DownloadQueue();
        return instance;
    }

    /** Bound queued plus running work so fast taps cannot create an unbounded pile of network downloads. */
    private static final int MAX_ACTIVE_JOBS = 8;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Job> jobs = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private int nextId = 1;

    private DownloadQueue() {
    }

    public synchronized Job submit(final String kind, String title, final Work work) {
        final Job job = new Job(nextId++, kind, title == null ? "" : title, this);
        int active = 0;
        for (Job existing : jobs) {
            if (existing.state == State.QUEUED || existing.state == State.RUNNING) active++;
        }
        jobs.add(0, job);
        if (active >= MAX_ACTIVE_JOBS) {
            job.state = State.FAILED;
            job.message = "The download queue is full. Wait for a download to finish before adding another.";
            changed();
            return job;
        }
        changed();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (job.isCancelled()) {
                    job.state = State.CANCELLED;
                    changed();
                    return;
                }
                job.state = State.RUNNING;
                changed();
                try {
                    work.run(job);
                    job.state = job.isCancelled() ? State.CANCELLED : State.DONE;
                    job.progress = 1f;
                    if (job.state == State.DONE && job.message.isEmpty()) job.message = "Saved";
                } catch (Exception e) {
                    if (job.isCancelled()) {
                        job.state = State.CANCELLED;
                        job.message = "Cancelled";
                    } else {
                        job.state = State.FAILED;
                        String text = e.getMessage();
                        job.message = text == null || text.isEmpty() ? "Something went wrong" : text;
                    }
                }
                changed();
            }
        });
        return job;
    }

    /** A copy of the jobs, newest first. */
    public List<Job> jobs() {
        return Collections.unmodifiableList(new ArrayList<>(jobs));
    }

    public void clearFinished() {
        for (Job j : jobs) if (j.finished()) jobs.remove(j);
        changed();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onQueueChanged();
            }
        });
    }
}
