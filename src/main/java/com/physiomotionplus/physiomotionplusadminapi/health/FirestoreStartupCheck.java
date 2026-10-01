package com.physiomotionplus.physiomotionplusadminapi.health;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "firestore.startup-check.enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreStartupCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FirestoreStartupCheck.class);
    private final Firestore firestore;
    private final String projectId;
    private final long timeoutSeconds;
    private final boolean failFast;

    public FirestoreStartupCheck(Firestore firestore,
            @Value("${firestore.project-id}") String projectId,
            @Value("${firestore.startup-check.timeout-seconds:10}") long timeoutSeconds,
            @Value("${firestore.startup-check.fail-fast:true}") boolean failFast) {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("Firestore check timeout must be positive");
        this.firestore = firestore;
        this.projectId = projectId;
        this.timeoutSeconds = timeoutSeconds;
        this.failFast = failFast;
    }

    @Override
    public void run(ApplicationArguments args) {
        ApiFuture<DocumentSnapshot> read = null;
        try {
            // A missing document is a successful read; no health document needs to be created.
            read = firestore.collection("system").document("health").get();
            read.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("Firestore startup check PASSED: project={}, database={}, read access confirmed",
                    projectId, firestore.getOptions().getDatabaseId());
        } catch (Exception failure) {
            if (read != null) read.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Firestore startup check FAILED: project={}. Check credentials, IAM permissions and network connectivity.",
                    projectId, failure);
            if (failFast || failure instanceof InterruptedException) {
                throw new IllegalStateException("Firestore startup check failed for project " + projectId, failure);
            }
        }
    }
}
