package com.physiomotionplus.physiomotionplusadminapi.security.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;

@Configuration
public class FirebaseInitConfig {
    @Value("${firebase.project-id}")
    private String projectId;

    @Bean
    FirebaseApp firebaseApp() throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) return FirebaseApp.getInstance();
        return FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .build());
    }

    @Bean
    Firestore firestore(@Value("${firestore.project-id}") String firestoreProjectId,
                        @Value("${firestore.database-id:(default)}") String databaseId) throws IOException {
        return FirestoreOptions.getDefaultInstance().toBuilder()
                .setProjectId(firestoreProjectId)
                .setDatabaseId(databaseId)
                .build()
                .getService();
    }

    @Bean
    Storage storage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
