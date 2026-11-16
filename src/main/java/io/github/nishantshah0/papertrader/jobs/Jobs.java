package io.github.nishantshah0.papertrader.jobs;

import io.github.nishantshah0.papertrader.NotFoundException;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Long work (a bot run takes about a minute, an evaluation many) runs here instead of
// inside an HTTP request: the request gets a job id at once, and GET /api/jobs/{id}
// answers how it is going and, once it is done, its result. Jobs run one at a time, in
// the order they came, since each one resets the exchange. Their times are wall-clock
// times, not the simulated clock. The last 100 are kept, in memory only.
@Service
public class Jobs {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    public record Job(long id, String kind, Status status, Instant submittedAt, Instant startedAt,
            Instant finishedAt, Object result, String error) {
    }

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);
    private static final int KEEP = 100;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("jobs").daemon().factory());
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Job> jobs = new LinkedHashMap<>();

    public Job submit(String kind, Supplier<?> work) {
        Job job = new Job(ids.incrementAndGet(), kind, Status.QUEUED, Instant.now(), null, null, null, null);
        put(job);
        worker.submit(() -> {
            put(new Job(job.id(), kind, Status.RUNNING, job.submittedAt(), Instant.now(), null, null, null));
            Instant started = get(job.id()).startedAt();
            try {
                Object result = work.get();
                put(new Job(job.id(), kind, Status.DONE, job.submittedAt(), started, Instant.now(), result, null));
            } catch (RuntimeException e) {
                log.warn("Job {} ({}) failed", job.id(), kind, e);
                put(new Job(job.id(), kind, Status.FAILED, job.submittedAt(), started, Instant.now(), null,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
        return job;
    }

    public synchronized Job get(long id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new NotFoundException("job " + id + " not found");
        }
        return job;
    }

    private synchronized void put(Job job) {
        jobs.put(job.id(), job);
        while (jobs.size() > KEEP) {
            jobs.remove(jobs.keySet().iterator().next());
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
