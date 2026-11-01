package com.physiomotionplus.physiomotionplusadminapi.care;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Same protocol in both APIs; authentication remains in each app's Firebase project. */
@Service
public class CareService {
  private final Firestore db;
  private final FirebaseApp app;
  private final InvitationMail mail;
  private final String audience, clientUrl;
  private final Set<String> admins;
  private final boolean pushEnabled, requireSubscription;
  private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CareService.class);

  public CareService(
      Firestore db,
      FirebaseApp app,
      InvitationMail mail,
      @Value("${care.audience:client}") String audience,
      @Value("${care.physio-uids:${videos.allowed-admin-uids:}}") String admins,
      @Value("${care.client-url:http://localhost:4200}") String clientUrl,
      @Value("${care.push-enabled:false}") boolean pushEnabled,
      @Value("${care.require-subscription:true}") boolean requireSubscription) {
    this.db = db;
    this.app = app;
    this.mail = mail;
    this.audience = audience;
    this.clientUrl = clientUrl.replaceAll("/+$", "");
    this.admins = new HashSet<>(Arrays.asList(admins.trim().split("\\s*,\\s*")));
    this.pushEnabled = pushEnabled;
    this.requireSubscription = requireSubscription;
    if (!Set.of("client", "physio").contains(audience))
      throw new IllegalArgumentException("Invalid care audience");
    if (pushEnabled) timers.scheduleWithFixedDelay(this::deliver, 10, 15, TimeUnit.SECONDS);
  }

  public record Actor(String uid, String email, String name, String role) {
    String field() {
      return role + "Id";
    }
  }

  Actor actor(String uid, String email, String name, boolean verified) {
    if (!verified || email == null)
      throw error(403, "Verify your email before using private care.");
    if (audience.equals("physio") && !admins.contains(uid))
      throw error(403, "This account is not an authorized physio.");
    return new Actor(uid, email.toLowerCase(Locale.ROOT), name == null ? email : name, audience);
  }

  static ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }

  static <T> T await(ApiFuture<T> f) throws Exception {
    try {
      return f.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      for (Throwable c = e; c != null; c = c.getCause())
        if (c instanceof ResponseStatusException r) throw r;
      throw e;
    }
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw error(400, "Invalid input.");
    return value.trim();
  }

  static String id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
      throw error(400, "Invalid identifier.");
    return value;
  }

  void role(Actor a, String role) {
    if (!a.role.equals(role)) throw error(403, "Action is not available for this account.");
  }

  static Map<String, Object> data(DocumentSnapshot d) {
    var m = new HashMap<String, Object>();
    if (d.exists()) m.putAll(d.getData());
    m.put("id", d.getId());
    return m;
  }

  static List<Map<String, Object>> data(QuerySnapshot q) {
    return q.getDocuments().stream().map(CareService::data).toList();
  }

  CollectionReference connections() {
    return db.collection("careConnections");
  }

  Query own(CollectionReference collection, Actor a) {
    return collection.whereEqualTo(a.field(), a.uid);
  }

  Object directory(Actor a, String after) throws Exception {
    Query q =
        db.collection("carePhysios")
            .whereEqualTo("discoverable", true)
            .orderBy(FieldPath.documentId())
            .limit(50);
    if (!after.isEmpty()) q = q.startAfter(id(after));
    return data(await(q.get()));
  }

  Object profile(Actor a) throws Exception {
    role(a, "physio");
    return data(await(db.collection("carePhysios").document(a.uid).get()));
  }

  Object profile(Actor a, CareController.Profile p) throws Exception {
    role(a, "physio");
    var m =
        Map.<String, Object>of(
            "name",
            text(p.name(), 100),
            "clinic",
            p.clinic() == null ? "" : p.clinic().trim(),
            "discoverable",
            p.discoverable());
    if (p.clinic() != null && p.clinic().length() > 200)
      throw error(400, "Clinic name is too long.");
    await(db.collection("carePhysios").document(a.uid).set(m));
    return m;
  }

  Object connections(Actor a) throws Exception {
    return data(
        await(
            own(connections(), a)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .limit(100)
                .get()));
  }

  Object page(Actor a, String collection, String after) throws Exception {
    Query query =
        own(db.collection(collection), a)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(100);
    if (!after.isBlank()) {
      var cursor = await(db.collection(collection).document(id(after)).get());
      participant(a, cursor);
      query = query.startAfter(cursor);
    }
    return data(await(query.get()));
  }

  Object request(Actor a, CareController.Request body) throws Exception {
    role(a, "client");
    String invitation = body.invitation() == null ? "" : body.invitation();
    if (invitation.length() > 200) throw error(400, "Invalid invitation.");
    String connectionId = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    return await(
        db.runTransaction(
            tx -> {
              var lock = db.collection("careClientLinks").document(a.uid);
              var previous = tx.get(lock).get();
              if (previous.exists()
                  && Set.of("pending", "active").contains(previous.getString("status")))
                throw error(409, "You already have a connection or pending request.");
              long day = now / 86400000L;
              long requests =
                  previous.exists() && Objects.equals(previous.getLong("requestDay"), day)
                      ? Objects.requireNonNullElse(previous.getLong("requestCount"), 0L)
                      : 0;
              if (requests >= 20) throw error(429, "Daily connection request limit reached.");
              DocumentSnapshot invite = null;
              String physio = body.physioId();
              if (!invitation.isEmpty()) {
                invite = tx.get(db.collection("careInvitations").document(hash(invitation))).get();
                if (!invite.exists()
                    || !a.email.equals(invite.getString("email"))
                    || Boolean.TRUE.equals(invite.getBoolean("used"))
                    || invite.getLong("expiresAt") < now)
                  throw error(
                      400, "Invitation is invalid, expired, or belongs to another email address.");
                physio = invite.getString("physioId");
              }
              var profile = tx.get(db.collection("carePhysios").document(id(physio))).get();
              if (!profile.exists()
                  || (invite == null && !Boolean.TRUE.equals(profile.getBoolean("discoverable"))))
                throw error(404, "Physio is not available.");
              var record = new HashMap<String, Object>();
              record.put("clientId", a.uid);
              record.put("physioId", physio);
              record.put("clientName", a.name);
              record.put("physioName", profile.getString("name"));
              record.put("status", "pending");
              record.put("createdAt", now);
              record.put("updatedAt", now);
              tx.set(connections().document(connectionId), record);
              tx.set(
                  lock,
                  Map.of(
                      "connectionId",
                      connectionId,
                      "status",
                      "pending",
                      "requestDay",
                      day,
                      "requestCount",
                      requests + 1));
              if (invite != null) tx.update(invite.getReference(), "used", true);
              enqueue(tx, "physio", physio, connectionId, "connection");
              record.put("id", connectionId);
              return record;
            }));
  }

  Object decide(Actor a, String connectionId, String status) throws Exception {
    if (!Set.of("active", "declined", "ended").contains(status == null ? "" : status))
      throw error(400, "Invalid decision.");
    return await(
        db.runTransaction(
            tx -> {
              var ref = connections().document(id(connectionId));
              var c = tx.get(ref).get();
              participant(a, c);
              var lock = db.collection("careClientLinks").document(c.getString("clientId"));
              var l = tx.get(lock).get();
              String old = c.getString("status");
              if (status.equals("active") || status.equals("declined")) {
                role(a, "physio");
                if (!"pending".equals(old)) throw error(409, "Request is no longer pending.");
              } else if (!Set.of("pending", "active").contains(old))
                throw error(409, "Connection is already closed.");
              tx.update(ref, Map.of("status", status, "updatedAt", System.currentTimeMillis()));
              if (l.exists() && connectionId.equals(l.getString("connectionId")))
                tx.update(lock, "status", status);
              enqueue(
                  tx,
                  a.role.equals("physio") ? "client" : "physio",
                  c.getString(a.role.equals("physio") ? "clientId" : "physioId"),
                  connectionId,
                  "connection");
              return Map.of("status", status);
            }));
  }

  Object invite(Actor a, String email) throws Exception {
    role(a, "physio");
    email = text(email, 254).toLowerCase(Locale.ROOT);
    if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw error(400, "Enter a valid email address.");
    if (!await(db.collection("carePhysios").document(a.uid).get()).exists())
      throw error(409, "Save your physio profile first.");
    // Random bearer secret is stored only as a hash and is bound to a verified email.
    String token = UUID.randomUUID() + "-" + UUID.randomUUID();
    String recipient = email;
    await(
        db.runTransaction(
            tx -> {
              var rateRef = db.collection("careInviteLimits").document(a.uid);
              var rate = tx.get(rateRef).get();
              long now = System.currentTimeMillis();
              if (rate.exists() && now - rate.getLong("lastSent") < 10000)
                throw error(429, "Please wait before creating another invitation.");
              tx.set(rateRef, Map.of("lastSent", now));
              tx.set(
                  db.collection("careInvitations").document(hash(token)),
                  Map.of(
                      "physioId",
                      a.uid,
                      "email",
                      recipient,
                      "used",
                      false,
                      "expiresAt",
                      now + 7 * 86400000L));
              return true;
            }));
    String url = clientUrl + "/messages#invite=" + token;
    return Map.of(
        "url", url, "expiresInDays", 7, "emailSent", mail != null && mail.send(email, url));
  }

  void participant(Actor a, DocumentSnapshot c) {
    if (!c.exists() || !a.uid.equals(c.getString(a.field())))
      throw error(404, "Conversation or connection not found.");
  }

  DocumentSnapshot thread(Actor a, String threadId) throws Exception {
    var t = await(db.collection("careThreads").document(id(threadId)).get());
    participant(a, t);
    var c = await(connections().document(t.getString("connectionId")).get());
    participant(a, c);
    if (!"active".equals(c.getString("status")))
      throw error(403, "This connection is no longer active.");
    return t;
  }

  Object threads(Actor a) throws Exception {
    return data(
        await(
            own(db.collection("careThreads"), a)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .limit(100)
                .get()));
  }

  Object open(Actor a, String videoId) throws Exception {
    role(a, "client");
    id(videoId);
    // Published module membership is checked when a thread is opened and when a message is sent.
    String title = publishedVideo(videoId);
    return await(
        db.runTransaction(
            tx -> {
              var link = tx.get(db.collection("careClientLinks").document(a.uid)).get();
              if (!link.exists() || !"active".equals(link.getString("status")))
                throw error(403, "Connect with a physio before commenting.");
              String connectionId = link.getString("connectionId");
              var c = tx.get(connections().document(connectionId)).get();
              participant(a, c);
              if (!"active".equals(c.getString("status")))
                throw error(403, "Connection is not active.");
              String threadId = hash(connectionId + ":" + videoId);
              var ref = db.collection("careThreads").document(threadId);
              var t = tx.get(ref).get();
              if (t.exists()) return data(t);
              var record = new HashMap<String, Object>();
              record.put("connectionId", connectionId);
              record.put("videoId", videoId);
              record.put("title", title);
              record.put("clientId", a.uid);
              record.put("physioId", c.getString("physioId"));
              record.put("clientName", c.getString("clientName"));
              record.put("physioName", c.getString("physioName"));
              record.put("updatedAt", System.currentTimeMillis());
              record.put("sequence", 0L);
              tx.set(ref, record);
              tx.update(c.getReference(), "updatedAt", System.currentTimeMillis());
              record.put("id", threadId);
              return record;
            }));
  }

  String publishedVideo(String videoId) throws Exception {
    if (!await(db.collection("exerciseVideos").document(videoId).get()).exists())
      throw error(404, "Video is unavailable.");
    for (var module :
        await(db.collection("modules").whereEqualTo("status", "Published").get()).getDocuments()) {
      if (module.get("exercises") instanceof List<?> exercises)
        for (Object item : exercises)
          if (item instanceof Map<?, ?> e && videoId.equals(e.get("clientExerciseId")))
            return String.valueOf(e.get("name"));
    }
    throw error(403, "This video is not published.");
  }

  Object messages(Actor a, String threadId, long before) throws Exception {
    thread(a, threadId);
    Query q =
        db.collection("careThreads")
            .document(threadId)
            .collection("messages")
            .orderBy("sequence", Query.Direction.DESCENDING)
            .limit(50);
    if (before > 0) q = q.startAfter(before);
    return data(await(q.get()));
  }

  Object send(Actor a, String threadId, CareController.Message message) throws Exception {
    String body = text(message.body(), 4000);
    id(message.id());
    var t = thread(a, threadId);
    publishedVideo(t.getString("videoId"));
    long now = System.currentTimeMillis();
    return await(
        db.runTransaction(
            tx -> {
              var ref = t.getReference();
              var current = tx.get(ref).get();
              var c = tx.get(connections().document(current.getString("connectionId"))).get();
              participant(a, c);
              if (!"active".equals(c.getString("status")))
                throw error(403, "Connection is not active.");
              var msgRef = ref.collection("messages").document(message.id());
              var duplicate = tx.get(msgRef).get();
              if (duplicate.exists()) {
                if (!a.uid.equals(duplicate.getString("senderId"))
                    || !a.role.equals(duplicate.getString("senderRole"))
                    || !body.equals(duplicate.getString("body")))
                  throw error(409, "Message identifier was already used.");
                return data(duplicate);
              }
              var rateRef = db.collection("careRateLimits").document(hash(a.role + ":" + a.uid));
              var rate = tx.get(rateRef).get();
              if (rate.exists() && now - rate.getLong("lastSent") < 1000)
                throw error(429, "Please wait a moment before sending another message.");
              if (requireSubscription && a.role.equals("client")) {
                var sub = tx.get(db.collection("subscriptions").document(a.uid)).get();
                if (!sub.exists()
                    || !Boolean.TRUE.equals(sub.getBoolean("hasAccess"))
                    || !Set.of("TIER_2", "TIER_3")
                        .contains(Objects.toString(sub.getString("tier"), ""))
                    || sub.getLong("currentPeriodEnd") == null
                    || sub.getLong("currentPeriodEnd") <= now / 1000)
                  throw error(403, "Your membership does not include comments.");
              }
              long seq = current.getLong("sequence") + 1;
              var m =
                  Map.<String, Object>of(
                      "body",
                      body,
                      "senderId",
                      a.uid,
                      "senderRole",
                      a.role,
                      "createdAt",
                      now,
                      "sequence",
                      seq);
              tx.set(msgRef, m);
              tx.set(rateRef, Map.of("lastSent", now));
              tx.update(ref, Map.of("updatedAt", now, "sequence", seq));
              tx.update(c.getReference(), "updatedAt", now);
              String target = a.role.equals("client") ? "physio" : "client";
              enqueue(tx, target, current.getString(target + "Id"), threadId, "message");
              var result = new HashMap<>(m);
              result.put("id", message.id());
              return result;
            }));
  }

  void enqueue(Transaction tx, String target, String uid, String context, String type) {
    tx.set(
        db.collection("careOutbox_" + target).document(),
        Map.of(
            "uid",
            uid,
            "context",
            context,
            "type",
            type,
            "createdAt",
            System.currentTimeMillis(),
            "nextAttempt",
            System.currentTimeMillis(),
            "attempts",
            0L));
  }

  void device(Actor a, String token, boolean remove) throws Exception {
    text(token, 4096);
    var ref = db.collection("careDevices_" + audience).document(hash(token));
    await(
        db.runTransaction(
            tx -> {
              var old = tx.get(ref).get();
              if (remove) {
                if (old.exists() && a.uid.equals(old.getString("uid"))) tx.delete(ref);
              } else {
                // Serialize registrations per account and cap retained devices to bound fan-out.
                var lock = db.collection("careDeviceLocks_" + audience).document(a.uid);
                tx.get(lock).get();
                var devices =
                    tx.get(db.collection("careDevices_" + audience).whereEqualTo("uid", a.uid))
                        .get()
                        .getDocuments();
                if (devices.size() >= 10
                    && devices.stream().noneMatch(d -> d.getId().equals(ref.getId()))) {
                  var oldest =
                      devices.stream()
                          .min(Comparator.comparingLong(d -> d.getLong("updatedAt")))
                          .orElseThrow();
                  tx.delete(oldest.getReference());
                }
                tx.set(lock, Map.of("updatedAt", System.currentTimeMillis()));
                tx.set(
                    ref,
                    Map.of("uid", a.uid, "token", token, "updatedAt", System.currentTimeMillis()));
              }
              return true;
            }));
  }

  private void deliver() {
    if (!pushEnabled) return;
    try {
      var jobs =
          await(
              db.collection("careOutbox_" + audience)
                  .whereLessThanOrEqualTo("nextAttempt", System.currentTimeMillis())
                  .limit(30)
                  .get());
      for (var job : jobs.getDocuments()) {
        String lease = UUID.randomUUID().toString();
        boolean claimed =
            await(
                db.runTransaction(
                    tx -> {
                      var current = tx.get(job.getReference()).get();
                      if (!current.exists()
                          || current.getLong("nextAttempt") > System.currentTimeMillis())
                        return false;
                      tx.update(
                          job.getReference(),
                          Map.of(
                              "nextAttempt", System.currentTimeMillis() + 120000, "lease", lease));
                      return true;
                    }));
        if (!claimed) continue;
        try {
          String uid = job.getString("uid");
          // Recheck membership before dispatch; ended relationships never send message alerts.
          boolean permitted = job.getLong("createdAt") > System.currentTimeMillis() - 7L * 86400000;
          if (audience.equals("physio") && !admins.contains(uid)) permitted = false;
          if (permitted) {
            try {
              var user = FirebaseAuth.getInstance(app).getUser(uid);
              permitted = !user.isDisabled() && user.isEmailVerified();
            } catch (com.google.firebase.auth.FirebaseAuthException e) {
              if (e.getAuthErrorCode() == com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND)
                permitted = false;
              else throw e;
            }
          }
          if ("message".equals(job.getString("type"))) {
            var t = await(db.collection("careThreads").document(job.getString("context")).get());
            permitted = permitted && t.exists() && uid.equals(t.getString(audience + "Id"));
            if (permitted)
              permitted =
                  "active"
                      .equals(
                          await(connections().document(t.getString("connectionId")).get())
                              .getString("status"));
          }
          if (permitted)
            for (var device :
                await(db.collection("careDevices_" + audience).whereEqualTo("uid", uid).get())
                    .getDocuments()) {
              boolean ownsLease =
                  await(
                      db.runTransaction(
                          tx -> {
                            var c = tx.get(job.getReference()).get();
                            if (!c.exists() || !lease.equals(c.getString("lease"))) return false;
                            tx.update(
                                job.getReference(),
                                "nextAttempt",
                                System.currentTimeMillis() + 120000);
                            return true;
                          }));
              if (!ownsLease) break;
              var sent = await(job.getReference().get()).get("deliveredDevices");
              if (sent instanceof List<?> list && list.contains(device.getId())) continue;
              if (device.getLong("updatedAt") < System.currentTimeMillis() - 30L * 86400000) {
                await(device.getReference().delete());
                continue;
              }
              try {
                FirebaseMessaging.getInstance(app)
                    .send(
                        Message.builder()
                            .setToken(device.getString("token"))
                            .setNotification(
                                Notification.builder()
                                    .setTitle("Physiomotion Plus")
                                    .setBody(
                                        "You have a new private update. / Nouvelle mise à jour"
                                            + " privée.")
                                    .build())
                            .setWebpushConfig(
                                WebpushConfig.builder()
                                    .putHeader("TTL", "3600")
                                    .setNotification(
                                        WebpushNotification.builder()
                                            .setTag("care-" + job.getId())
                                            .build())
                                    .build())
                            .putData("path", "/messages")
                            .build());
                await(
                    job.getReference()
                        .update("deliveredDevices", FieldValue.arrayUnion(device.getId())));
              } catch (FirebaseMessagingException e) {
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED)
                  await(device.getReference().delete());
                else throw e;
              }
            }
          await(
              db.runTransaction(
                  tx -> {
                    var c = tx.get(job.getReference()).get();
                    if (c.exists() && lease.equals(c.getString("lease")))
                      tx.delete(job.getReference());
                    return true;
                  }));
        } catch (Exception failure) {
          long attempts = job.getLong("attempts") + 1;
          await(
              db.runTransaction(
                  tx -> {
                    var c = tx.get(job.getReference()).get();
                    if (c.exists() && lease.equals(c.getString("lease")))
                      tx.update(
                          job.getReference(),
                          Map.of(
                              "attempts",
                              attempts,
                              "failed",
                              attempts >= 20,
                              "nextAttempt",
                              attempts >= 20
                                  ? Long.MAX_VALUE
                                  : System.currentTimeMillis()
                                      + Math.min(
                                          3600000L, 15000L * (1L << Math.min(8, attempts)))));
                    return true;
                  }));
          log.warn("Care push job failed; queued for retry: {}", job.getId());
        }
      }
    } catch (Exception e) {
      log.error("Care notification worker could not process its queue", e);
    }
  }

  @PreDestroy
  void close() {
    timers.shutdownNow();
  }
}
