package dev.codecomp.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.JobView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes job updates to browsers over Server-Sent Events. Every 250 ms one database query fetches all the jobs this
 * instance's browsers are waiting on, so the database load does not grow with the number of waiting browsers, and
 * any number of run-service instances can serve updates for jobs finished by any worker.
 */
@Component
public class JobEvents {

    private static final Logger log = LoggerFactory.getLogger(JobEvents.class);

    /** Below nginx's 60 s proxy read timeout. The browser reconnects if its job is still running. */
    private static final long STREAM_TIMEOUT_MS = 45_000;

    private static final class Watch {
        final SseEmitter emitter;
        volatile String lastSent;

        Watch(SseEmitter emitter) { this.emitter = emitter; }
    }

    private final JobService jobs;
    private final ObjectMapper json;
    private final Map<UUID, List<Watch>> watching = new ConcurrentHashMap<>();

    JobEvents(JobService jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    public SseEmitter watch(UUID user, UUID id) {
        var view = jobs.view(user, id);  // 404 unless this user owns the job
        var watch = new Watch(new SseEmitter(STREAM_TIMEOUT_MS));
        if (!send(watch, view) || finished(view)) {
            watch.emitter.complete();
            return watch.emitter;
        }
        watching.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(watch);
        Runnable forget = () -> watching.computeIfPresent(id, (k, list) -> {
            list.remove(watch);
            return list.isEmpty() ? null : list;
        });
        watch.emitter.onCompletion(forget);
        watch.emitter.onTimeout(forget);
        watch.emitter.onError(e -> forget.run());
        return watch.emitter;
    }

    @Scheduled(fixedDelay = 250)
    void push() {
        if (watching.isEmpty()) return;
        Map<UUID, JobView> views;
        try {
            views = jobs.views(List.copyOf(watching.keySet()));
        } catch (RuntimeException e) {
            log.warn("Could not load job updates: {}", e.getMessage());
            return;
        }
        views.forEach((id, view) -> {
            var list = watching.get(id);
            if (list == null) return;
            for (var watch : list) {
                if (!send(watch, view) || finished(view)) watch.emitter.complete();
            }
        });
    }

    /** Sends the view if it changed since the last event on this stream. False when the browser has gone. */
    private boolean send(Watch watch, JobView view) {
        try {
            var data = json.writeValueAsString(view);
            if (data.equals(watch.lastSent)) return true;
            watch.emitter.send(SseEmitter.event().name("job").data(data, MediaType.APPLICATION_JSON));
            watch.lastSent = data;
            return true;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private static boolean finished(JobView view) {
        return "DONE".equals(view.status()) || "FAILED".equals(view.status());
    }
}
