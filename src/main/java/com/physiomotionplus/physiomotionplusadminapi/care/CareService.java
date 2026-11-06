package com.physiomotionplus.physiomotionplusadminapi.care;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
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
  private final InvitationMail mail;
  private final String audience, clientUrl;
  private final Set<String> admins;
  private final boolean requireSubscription;

  public CareService(
      Firestore db,
      InvitationMail mail,
      @Value("${care.audience:client}") String audience,
      @Value("${care.physio-uids:${videos.allowed-admin-uids:}}") String admins,
      @Value("${care.client-url:http://localhost:4200}") String clientUrl,
      @Value("${care.require-subscription:true}") boolean requireSubscription) {
    this.db = db;
    this.mail = mail;
    this.audience = audience;
    this.clientUrl = clientUrl.replaceAll("/+$", "");
    this.admins = new HashSet<>(Arrays.asList(admins.trim().split("\\s*,\\s*")));
    this.requireSubscription = requireSubscription;
    if (!Set.of("client", "physio").contains(audience))
      throw new IllegalArgumentException("Invalid care audience");
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
    var rows = data(await(query.get()));
    // Message excerpts are returned only by endpoints that recheck the active connection.
    if ("careThreads".equals(collection)) rows.forEach(row -> {
      row.remove("lastMessagePreview"); row.remove("lastMessageSenderRole"); row.remove("lastMessageAt");
    });
    return rows;
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

  Object inbox(Actor a, String after) throws Exception {
    role(a, "physio");
    Query query = own(db.collection("careThreads"), a)
        .orderBy("updatedAt", Query.Direction.DESCENDING).limit(20);
    if (!after.isBlank()) {
      var cursor = await(db.collection("careThreads").document(id(after)).get());
      participant(a, cursor);
      query = query.startAfter(cursor);
    }
    var page = await(query.get());
    var candidates = page.getDocuments().stream()
        .filter(t -> Objects.requireNonNullElse(t.getLong("sequence"), 0L) > 0).toList();
    var connectionRefs = candidates.stream().map(t -> connections().document(t.getString("connectionId")))
        .distinct().toArray(DocumentReference[]::new);
    Set<String> active = new HashSet<>();
    if (connectionRefs.length > 0) {
      for (var connection : await(db.getAll(connectionRefs)))
        if (connection.exists() && a.uid.equals(connection.getString("physioId"))
            && "active".equals(connection.getString("status"))) active.add(connection.getId());
    }
    var items = candidates.stream().filter(t -> active.contains(t.getString("connectionId")))
        .map(CareService::data).toList();
    String next = page.size() == 20 ? page.getDocuments().getLast().getId() : "";
    return Map.of("items", items, "nextCursor", next);
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
    var thread = thread(a, threadId);
    Query q =
        db.collection("careThreads")
            .document(threadId)
            .collection("messages")
            .orderBy("sequence", Query.Direction.DESCENDING)
            .limit(50);
    if (before > 0) q = q.startAfter(before);
    return await(q.get()).getDocuments().stream().map(message -> messageData(message, thread)).toList();
  }

  static Map<String, Object> messageData(DocumentSnapshot message, DocumentSnapshot thread) {
    var result = data(message);
    if (!(result.get("senderName") instanceof String name) || name.isBlank()) {
      String field = "physio".equals(result.get("senderRole")) ? "physioName" : "clientName";
      result.put("senderName", Objects.toString(thread.get(field), ""));
    }
    return result;
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
                return messageData(duplicate, current);
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
                      "senderName",
                      a.name,
                      "createdAt",
                      now,
                      "sequence",
                      seq);
              tx.set(msgRef, m);
              tx.set(rateRef, Map.of("lastSent", now));
              tx.update(ref, Map.of("updatedAt", now, "sequence", seq,
                  "lastMessagePreview", body.substring(0, body.offsetByCodePoints(0, Math.min(160, body.codePointCount(0, body.length())))),
                  "lastMessageSenderRole", a.role, "lastMessageAt", now));
              tx.update(c.getReference(), "updatedAt", now);
              var result = new HashMap<>(m);
              result.put("id", message.id());
              return result;
            }));
  }

}
