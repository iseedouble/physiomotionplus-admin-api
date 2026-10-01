package com.physiomotionplus.physiomotionplusadminapi.api;

import com.google.cloud.firestore.Firestore;
import com.physiomotionplus.physiomotionplusadminapi.security.firebase.FirebasePrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Modules are versioned aggregates: exercise array order is the playback order. */
@RestController
@RequestMapping("/api/admin/modules")
@PreAuthorize("hasRole('PHYSIO')")
public class ModuleController {
    private final Firestore firestore;
    private final Set<String> admins;
    public ModuleController(Firestore firestore, @Value("${videos.allowed-admin-uids:}") String admins) {
        this.firestore = firestore;
        this.admins = new HashSet<>(Arrays.asList(admins.split(",")));
    }
    public record Exercise(String id, String name, String description, String clientExerciseId) {}
    public record ModuleInput(String name, String description, String area, String status, List<Exercise> exercises, long version) {}

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal FirebasePrincipal principal) {
        authorize(principal);
        try {
            return firestore.collection("modules").get().get(15, TimeUnit.SECONDS).getDocuments().stream()
                    .map(doc -> { Map<String,Object> data = new HashMap<>(doc.getData()); data.put("id", doc.getId()); return data; })
                    .sorted(Comparator.comparing(data -> String.valueOf(data.get("name")))) .toList();
        } catch (Exception failure) { throw failure(failure); }
    }

    @PutMapping("/{id}")
    public Map<String, Object> save(@PathVariable String id, @RequestBody ModuleInput input,
                                   @AuthenticationPrincipal FirebasePrincipal principal) {
        authorize(principal);
        validateId(id);
        if (input == null || input.version() < 0 || input.name() == null || input.name().isBlank() || input.name().length() > 100
                || Objects.toString(input.description(), "").length() > 2000
                || !Set.of("Ankle", "Shoulder", "Back", "Knee").contains(Objects.toString(input.area(), ""))
                || !Set.of("Draft", "Published").contains(Objects.toString(input.status(), ""))
                || input.exercises() == null || input.exercises().size() > 100
                || (input.status().equals("Published") && input.exercises().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid module details");
        }
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> exercises = new ArrayList<>();
        for (Exercise e : input.exercises()) {
            if (e == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid exercise");
            validateId(e.id());
            validateId(e.clientExerciseId());
            if (!ids.add(e.id()) || e.name() == null || e.name().isBlank() || e.name().length() > 100
                    || e.description() == null || e.description().length() > 5000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid exercise details");
            }
            exercises.add(Map.of("id", e.id(), "name", e.name().trim(), "description", e.description(),
                    "clientExerciseId", e.clientExerciseId()));
        }
        try {
            return firestore.runTransaction(tx -> {
                var ref = firestore.collection("modules").document(id);
                var previous = tx.get(ref).get();
                for (Exercise exercise : input.exercises()) {
                    if (!exercise.id().equals(exercise.clientExerciseId()) &&
                            !tx.get(firestore.collection("exerciseVideos").document(exercise.clientExerciseId())).get().exists()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected video no longer exists");
                    }
                }
                long version = previous.exists() && previous.getLong("version") != null ? previous.getLong("version") : 0;
                if (version != input.version()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This module changed in another session. Reload before saving.");
                String now = Instant.now().toString();
                Map<String,Object> data = new HashMap<>();
                data.put("id", id); data.put("name", input.name().trim()); data.put("description", Objects.toString(input.description(), "").trim()); data.put("area", input.area());
                data.put("status", input.status()); data.put("exercises", exercises); data.put("version", version + 1);
                data.put("createdAt", previous.exists() ? previous.get("createdAt") : now);
                data.put("createdBy", previous.exists() ? previous.get("createdBy") : principal.uid());
                data.put("updatedAt", now); data.put("updatedBy", principal.uid());
                tx.set(ref, data);
                return data;
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception failure) { throw failure(failure); }
    }

    private void authorize(FirebasePrincipal principal) {
        if (principal == null || admins.stream().map(String::trim).noneMatch(principal.uid()::equals))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account cannot manage modules");
    }
    private void validateId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,79}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier");
    }
    private RuntimeException failure(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof ResponseStatusException status) return status;
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Firestore module request failed", failure);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not access modules. Please retry.");
    }
}
