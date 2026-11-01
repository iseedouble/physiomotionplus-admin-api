package com.physiomotionplus.physiomotionplusadminapi.care;

import com.physiomotionplus.physiomotionplusadminapi.security.firebase.FirebasePrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/care")
public class CareController {
  private final CareService care;

  public CareController(CareService care) {
    this.care = care;
  }

  @ModelAttribute
  void privateResponse(HttpServletResponse response) {
    response.setHeader("Cache-Control", "private, no-store");
  }

  private CareService.Actor actor(FirebasePrincipal p) {
    return care.actor(p.uid(), p.email(), p.displayName(), p.token().isEmailVerified());
  }

  @GetMapping("/directory")
  Object directory(
      @AuthenticationPrincipal FirebasePrincipal p, @RequestParam(defaultValue = "") String after)
      throws Exception {
    return care.directory(actor(p), after);
  }

  @GetMapping("/profile")
  Object profile(@AuthenticationPrincipal FirebasePrincipal p) throws Exception {
    return care.profile(actor(p));
  }

  public record Profile(String name, String clinic, boolean discoverable) {}

  @PutMapping("/profile")
  Object profile(@AuthenticationPrincipal FirebasePrincipal p, @RequestBody Profile body)
      throws Exception {
    return care.profile(actor(p), body);
  }

  @GetMapping("/connections")
  Object connections(
      @AuthenticationPrincipal FirebasePrincipal p, @RequestParam(defaultValue = "") String after)
      throws Exception {
    return care.page(actor(p), "careConnections", after);
  }

  public record Request(String physioId, String invitation) {}

  @PostMapping("/connections")
  Object request(@AuthenticationPrincipal FirebasePrincipal p, @RequestBody Request body)
      throws Exception {
    return care.request(actor(p), body);
  }

  public record Decision(String status) {}

  @PatchMapping("/connections/{id}")
  Object decide(
      @AuthenticationPrincipal FirebasePrincipal p,
      @PathVariable String id,
      @RequestBody Decision body)
      throws Exception {
    return care.decide(actor(p), id, body.status());
  }

  public record Invitation(String email) {}

  @PostMapping("/invitations")
  Object invite(@AuthenticationPrincipal FirebasePrincipal p, @RequestBody Invitation body)
      throws Exception {
    return care.invite(actor(p), body.email());
  }

  @GetMapping("/threads")
  Object threads(
      @AuthenticationPrincipal FirebasePrincipal p, @RequestParam(defaultValue = "") String after)
      throws Exception {
    return care.page(actor(p), "careThreads", after);
  }

  public record OpenThread(String videoId) {}

  @PostMapping("/threads")
  Object open(@AuthenticationPrincipal FirebasePrincipal p, @RequestBody OpenThread body)
      throws Exception {
    return care.open(actor(p), body.videoId());
  }

  @GetMapping("/threads/{id}/messages")
  Object messages(
      @AuthenticationPrincipal FirebasePrincipal p,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long before)
      throws Exception {
    return care.messages(actor(p), id, before);
  }

  public record Message(String id, String body) {}

  @PostMapping("/threads/{id}/messages")
  Object send(
      @AuthenticationPrincipal FirebasePrincipal p,
      @PathVariable String id,
      @RequestBody Message body)
      throws Exception {
    return care.send(actor(p), id, body);
  }

  public record Device(String token) {}

  @PutMapping("/device")
  Object device(@AuthenticationPrincipal FirebasePrincipal p, @RequestBody Device body)
      throws Exception {
    care.device(actor(p), body.token(), false);
    return Map.of("ok", true);
  }

  @DeleteMapping("/device")
  Object removeDevice(@AuthenticationPrincipal FirebasePrincipal p, @RequestBody Device body)
      throws Exception {
    care.device(actor(p), body.token(), true);
    return Map.of("ok", true);
  }
}
