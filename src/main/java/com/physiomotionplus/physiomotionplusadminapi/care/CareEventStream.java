package com.physiomotionplus.physiomotionplusadminapi.care;

import com.google.cloud.firestore.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated push updates while the app is open. FCM remains optional for background alerts. */
@Service
public class CareEventStream {
  private final Firestore db;
  private final CareService care;
  private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
  private final ConcurrentMap<String, Set<SseEmitter>> accounts = new ConcurrentHashMap<>();
  private final ConcurrentMap<SseEmitter, Runnable> active = new ConcurrentHashMap<>();
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CareEventStream.class);

  public CareEventStream(Firestore db, CareService care) { this.db = db; this.care = care; }

  SseEmitter open(CareService.Actor actor, String threadId) throws Exception {
    DocumentSnapshot thread = threadId.isEmpty() ? null : care.thread(actor, threadId);
    String key = actor.role() + ":" + actor.uid();
    SseEmitter emitter = new SseEmitter(240_000L);
    accounts.compute(key, (ignored, existing) -> {
      Set<SseEmitter> streams = existing == null ? ConcurrentHashMap.newKeySet() : existing;
      if (streams.size() >= 4 || active.size() >= 500) throw CareService.error(429, "Too many open conversations. Close another tab.");
      streams.add(emitter); return streams;
    });
    AtomicBoolean closed = new AtomicBoolean();
    List<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();
    List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();
    Runnable stop = () -> {
      if (!closed.compareAndSet(false, true)) return;
      listeners.forEach(ListenerRegistration::remove);
      tasks.forEach(task -> task.cancel(false));
      accounts.computeIfPresent(key, (ignored, streams) -> { streams.remove(emitter); return streams.isEmpty() ? null : streams; });
      active.remove(emitter);
    };
    active.put(emitter, stop);
    emitter.onCompletion(stop);
    emitter.onError(ignored -> stop.run());
    emitter.onTimeout(() -> { stop.run(); emitter.complete(); });
    java.util.function.BiConsumer<String, Object> send = (event, payload) -> {
      if (closed.get()) return;
      try { emitter.send(SseEmitter.event().name(event).data(payload)); }
      catch (Exception ignored) { stop.run(); emitter.complete(); }
    };
    Runnable revoked = () -> { send.accept("closed", Map.of()); stop.run(); emitter.complete(); };
    java.util.function.Consumer<Exception> failed = error -> {
      log.warn("Private care live listener failed: {}", error.getClass().getSimpleName());
      send.accept("stream-error", Map.of()); stop.run(); emitter.complete();
    };
    java.util.function.Consumer<ListenerRegistration> register = listener -> { listeners.add(listener); if (closed.get()) listener.remove(); };
    try {
      if (thread == null) {
        register.accept(care.own(care.connections(), actor).orderBy("updatedAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener((snapshot, error) -> { if (error != null) failed.accept(error); else send.accept("connections", CareService.data(snapshot)); }));
        register.accept(care.own(db.collection("careThreads"), actor).orderBy("updatedAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener((snapshot, error) -> { if (error != null) failed.accept(error); else send.accept("threads", CareService.data(snapshot)); }));
      } else {
        register.accept(care.connections().document(thread.getString("connectionId")).addSnapshotListener((snapshot, error) -> {
          if (error != null) failed.accept(error);
          else if (snapshot == null || !snapshot.exists() || !"active".equals(snapshot.getString("status"))) revoked.run();
        }));
        register.accept(thread.getReference().collection("messages").orderBy("sequence", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener((snapshot, error) -> {
              if (error != null) { failed.accept(error); return; }
              try { care.thread(actor, threadId); send.accept("messages", CareService.data(snapshot)); }
              catch (org.springframework.web.server.ResponseStatusException denied) { revoked.run(); }
              catch (Exception failure) { failed.accept(failure); }
            }));
      }
      tasks.add(timer.scheduleAtFixedRate(() -> send.accept("heartbeat", Map.of()), 20, 20, TimeUnit.SECONDS));
      tasks.add(timer.schedule(() -> { stop.run(); emitter.complete(); }, 240, TimeUnit.SECONDS));
      if (closed.get()) tasks.forEach(task -> task.cancel(false));
      send.accept("ready", Map.of());
      return emitter;
    } catch (Exception error) { stop.run(); emitter.complete(); throw error; }
  }

  @PreDestroy void shutdown() {
    active.forEach((emitter, stop) -> { stop.run(); emitter.complete(); });
    timer.shutdownNow();
  }
}
