package com.physiomotionplus.physiomotionplusadminapi.api;

import com.google.cloud.firestore.Firestore;
import com.physiomotionplus.physiomotionplusadminapi.security.firebase.FirebasePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/test")
public class TestController {
    private final Firestore firestore;
    public TestController(Firestore firestore) { this.firestore = firestore; }
    @GetMapping
    public Map<String, Object> test(@AuthenticationPrincipal FirebasePrincipal principal) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Admin Firebase JWT validated by Spring Security");
        response.put("uid", principal.uid());
        response.put("email", principal.email() == null ? "" : principal.email());
        try {
            firestore.collection("system").document("health").get().get();
            response.put("firestoreConnected", true);
        } catch (Exception exception) {
            response.put("firestoreConnected", false);
            response.put("firestoreError", exception.getClass().getSimpleName());
        }
        return response;
    }
}
