package com.physiomotionplus.physiomotionplusadminapi.care;

import static org.junit.jupiter.api.Assertions.*;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;

/** Real Firestore transactions against a local emulator. Never connects to a live project. */
@EnabledIfEnvironmentVariable(
    named = "FIRESTORE_EMULATOR_HOST",
    matches = "(localhost|127\\.0\\.0\\.1):[0-9]+")
class CareIntegrationTest {
  Firestore db;
  CareService client, physio;
  CareEventStream clientEvents;
  CareService.Actor alice, bob, pat, other;

  @BeforeEach
  void setup() throws Exception {
    db =
        FirestoreOptions.newBuilder()
            .setProjectId("demo-care-" + UUID.randomUUID().toString().substring(0, 8))
            .setEmulatorHost(System.getenv("FIRESTORE_EMULATOR_HOST"))
            .setCredentials(GoogleCredentials.create(null))
            .build()
            .getService();
    client = new CareService(db, null, null, "client", "", "http://localhost:4200", false, false);
    physio =
        new CareService(
            db, null, null, "physio", "pat,other", "http://localhost:4200", false, false);
    clientEvents = new CareEventStream(db, client);
    alice = client.actor("alice", "alice@example.com", "Alice", true);
    bob = client.actor("bob", "bob@example.com", "Bob", true);
    pat = physio.actor("pat", "pat@example.com", "Pat", true);
    other = physio.actor("other", "other@example.com", "Other", true);
    physio.profile(pat, new CareController.Profile("Pat", "Clinic", true));
    db.collection("exerciseVideos").document("video-1").set(Map.of("exerciseId", "video-1")).get();
    db.collection("modules")
        .document("module-1")
        .set(
            Map.of(
                "status",
                "Published",
                "exercises",
                List.of(Map.of("clientExerciseId", "video-1", "name", "Exercise"))))
        .get();
  }

  @AfterEach
  void close() throws Exception {
    clientEvents.shutdown();
    client.close();
    physio.close();
    db.close();
  }

  @SuppressWarnings("unchecked")
  String request(CareService.Actor actor) throws Exception {
    return (String)
        ((Map<String, Object>) client.request(actor, new CareController.Request("pat", null)))
            .get("id");
  }

  String connect(CareService.Actor actor) throws Exception {
    String id = request(actor);
    physio.decide(pat, id, "active");
    return id;
  }

  @SuppressWarnings("unchecked")
  String open(CareService.Actor actor) throws Exception {
    return (String) ((Map<String, Object>) client.open(actor, "video-1")).get("id");
  }

  void denied(int status, org.junit.jupiter.api.function.Executable action) {
    assertEquals(
        status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
  }

  @Test
  void replacingTheSameLiveSlotDoesNotConsumeMoreAccountCapacity() throws Exception {
    for (int i = 0; i < 12; i++) {
      clientEvents.open(alice, "", "one-tab-workspace", "lease-" + i);
      // Delayed cancellation for the previous connection cannot remove its replacement.
      if (i > 0) clientEvents.release(alice, "one-tab-workspace", "lease-" + (i - 1));
    }
    for (int i = 0; i < 3; i++) clientEvents.open(alice, "", "other-tab-" + i, "lease");
    denied(429, () -> clientEvents.open(alice, "", "fifth-slot", "lease"));
    clientEvents.release(bob, "one-tab-workspace", "lease-11");
    denied(429, () -> clientEvents.open(alice, "", "fifth-slot", "lease"));
    clientEvents.release(alice, "one-tab-workspace", "lease-11");
    assertNotNull(clientEvents.open(alice, "", "fifth-slot", "lease"));
  }

  @Test
  void sameVideoHasIndependentPrivateThreads() throws Exception {
    connect(alice);
    connect(bob);
    String a = open(alice), b = open(bob);
    assertNotEquals(a, b);
    client.send(alice, a, new CareController.Message("one", "Alice private question"));
    denied(404, () -> client.messages(bob, a, 0));
    denied(404, () -> client.send(bob, a, new CareController.Message("two", "intrusion")));
    denied(404, () -> physio.messages(other, a, 0));
    denied(404, () -> clientEvents.open(bob, a));
    assertEquals(1, ((List<?>) physio.messages(pat, a, 0)).size());
    assertEquals(0, ((List<?>) client.messages(bob, b, 0)).size());
  }

  @Test
  void pendingCannotCommentAndClientsCannotApprove() throws Exception {
    String c = request(alice);
    denied(403, () -> client.open(alice, "video-1"));
    denied(403, () -> client.decide(alice, c, "active"));
    denied(404, () -> physio.decide(other, c, "active"));
    physio.decide(pat, c, "active");
    assertNotNull(open(alice));
  }

  @Test
  void endedConnectionRevokesMessagesAndStreams() throws Exception {
    String c = connect(alice), t = open(alice);
    client.send(alice, t, new CareController.Message("one", "Question"));
    physio.decide(pat, c, "ended");
    denied(403, () -> client.messages(alice, t, 0));
    denied(403, () -> physio.send(pat, t, new CareController.Message("two", "Reply")));
    denied(403, () -> clientEvents.open(alice, t));
    connect(alice);
    assertNotEquals(t, open(alice));
  }

  @Test
  void duplicateSendCreatesOneMessageAndOneNotification() throws Exception {
    connect(alice);
    String t = open(alice);
    var message = new CareController.Message("unique-id", "Question");
    assertEquals(client.send(alice, t, message), client.send(alice, t, message));
    assertEquals(1, ((List<?>) client.messages(alice, t, 0)).size());
    assertEquals(
        1, db.collection("careOutbox_physio").whereEqualTo("type", "message").get().get().size());
    denied(
        409,
        () -> client.send(alice, t, new CareController.Message("unique-id", "Different body")));
  }

  @Test
  void concurrentRequestsCreateOnlyOnePendingConnection() throws Exception {
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          pool.invokeAll(
              List.of(
                  () -> {
                    try {
                      request(alice);
                      return true;
                    } catch (ResponseStatusException e) {
                      return false;
                    }
                  },
                  () -> {
                    try {
                      request(alice);
                      return true;
                    } catch (ResponseStatusException e) {
                      return false;
                    }
                  }));
      int successes = 0;
      for (var r : results) if (Boolean.TRUE.equals(r.get())) successes++;
      assertEquals(1, successes);
    }
  }

  @Test
  void invitationIsEmailBoundSingleUseAndNeedsApproval() throws Exception {
    @SuppressWarnings("unchecked")
    var invitation = (Map<String, Object>) physio.invite(pat, "alice@example.com");
    String token = invitation.get("url").toString().split("#invite=")[1];
    denied(400, () -> client.request(bob, new CareController.Request(null, token)));
    client.request(alice, new CareController.Request(null, token));
    denied(403, () -> client.open(alice, "video-1"));
    var link = db.collection("careClientLinks").document("alice").get().get();
    client.decide(alice, link.getString("connectionId"), "ended");
    denied(400, () -> client.request(alice, new CareController.Request(null, token)));
  }

  @Test
  void expiredInvitationIsRejected() throws Exception {
    db.collection("careInvitations")
        .document(CareService.hash("expired"))
        .set(Map.of("email", alice.email(), "physioId", "pat", "used", false, "expiresAt", 1L))
        .get();
    denied(400, () -> client.request(alice, new CareController.Request(null, "expired")));
  }

  @Test
  void membershipIsEnforcedServerSide() throws Exception {
    connect(alice);
    String t = open(alice);
    try {
      var gated =
          new CareService(db, null, null, "client", "", "http://localhost:4200", false, true);
      try {
        denied(403, () -> gated.send(alice, t, new CareController.Message("one", "Question")));
      } finally {
        gated.close();
      }
    } finally {
      assertEquals(0, ((List<?>) client.messages(alice, t, 0)).size());
    }
  }

  @Test
  void draftVideoAndUnverifiedOrUnauthorizedAccountsAreDenied() throws Exception {
    denied(403, () -> client.actor("unverified", "a@example.com", "A", false));
    denied(403, () -> physio.actor("rogue", "r@example.com", "R", true));
    connect(alice);
    db.collection("modules").document("module-1").update("status", "Draft").get();
    denied(403, () -> client.open(alice, "video-1"));
  }

  @Test
  void devicesAreScopedAndCannotBeRemovedByAnotherClient() throws Exception {
    client.device(alice, "device-token", false);
    client.device(bob, "device-token", true);
    assertEquals(
        "alice",
        db.collection("careDevices_client")
            .document(CareService.hash("device-token"))
            .get()
            .get()
            .getString("uid"));
    assertFalse(
        db.collection("careDevices_physio")
            .document(CareService.hash("device-token"))
            .get()
            .get()
            .exists());
    client.device(alice, "device-token", true);
    assertFalse(
        db.collection("careDevices_client")
            .document(CareService.hash("device-token"))
            .get()
            .get()
            .exists());
  }
}
