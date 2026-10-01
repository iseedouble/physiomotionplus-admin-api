package com.physiomotionplus.physiomotionplusadminapi.api;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.physiomotionplus.physiomotionplusadminapi.security.firebase.FirebasePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/videos")
@PreAuthorize("hasRole('PHYSIO')")
public class VideoController {
    private static final Logger log = LoggerFactory.getLogger(VideoController.class);
    private static final Pattern EXERCISE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");
    private static final String COLLECTION = "exerciseVideos";
    private final Firestore firestore;
    private final Storage storage;
    private final String bucket;
    private final Set<String> allowedAdminUids;

    public VideoController(Firestore firestore, Storage storage, @Value("${videos.bucket}") String bucket,
                           @Value("${videos.allowed-admin-uids:}") String allowedAdminUids) {
        this.firestore = firestore;
        this.storage = storage;
        this.bucket = bucket;
        this.allowedAdminUids = Arrays.stream(allowedAdminUids.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    public record VideoMetadata(String exerciseId, String filename, String contentType, long size,
                                String uploadedAt) { }

    @GetMapping
    public List<VideoMetadata> list(@AuthenticationPrincipal FirebasePrincipal principal) {
        requireVideoAdmin(principal);
        try {
            List<VideoMetadata> videos = new ArrayList<>();
            for (DocumentSnapshot doc : firestore.collection(COLLECTION).get().get(15, TimeUnit.SECONDS).getDocuments()) {
                videos.add(metadata(doc));
            }
            videos.sort(Comparator.comparing(VideoMetadata::exerciseId));
            return videos;
        } catch (Exception failure) {
            throw unavailable("Could not list videos", failure);
        }
    }

    @PutMapping(path = "/{exerciseId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VideoMetadata upload(@PathVariable String exerciseId, @RequestPart("file") MultipartFile file,
                                @AuthenticationPrincipal FirebasePrincipal principal) {
        validateExerciseId(exerciseId);
        requireVideoAdmin(principal);
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty video");
        if (file.getSize() > 250L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Video exceeds 250 MB");
        }

        String filename = file.getOriginalFilename() == null ? "video" : file.getOriginalFilename().replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank()) filename = "video";

        String contentType;
        try (PushbackInputStream input = new PushbackInputStream(file.getInputStream(), 12)) {
            byte[] header = input.readNBytes(12);
            input.unread(header);
            boolean mp4 = header.length >= 8 && header[4] == 'f' && header[5] == 't'
                    && header[6] == 'y' && header[7] == 'p';
            boolean webm = header.length >= 4 && (header[0] & 0xff) == 0x1a
                    && (header[1] & 0xff) == 0x45 && (header[2] & 0xff) == 0xdf
                    && (header[3] & 0xff) == 0xa3;
            if (!mp4 && !webm) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only MP4 and WebM videos are supported");
            }
            contentType = mp4 ? "video/mp4" : "video/webm";

            DocumentSnapshot previous = firestore.collection(COLLECTION).document(exerciseId)
                    .get().get(15, TimeUnit.SECONDS);
            String objectName = "exercises/" + exerciseId + "/" + UUID.randomUUID() + (mp4 ? ".mp4" : ".webm");
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                    .setContentType(contentType)
                    .setCacheControl("private, no-store")
                    .build();
            storage.createFrom(info, input, 8 * 1024 * 1024, Storage.BlobWriteOption.doesNotExist());

            String uploadedAt = Instant.now().toString();
            Map<String, Object> data = Map.of(
                    "exerciseId", exerciseId,
                    "bucket", bucket,
                    "objectName", objectName,
                    "filename", filename,
                    "contentType", contentType,
                    "size", file.getSize(),
                    "uploadedAt", uploadedAt,
                    "uploadedBy", principal.uid());
            try {
                firestore.collection(COLLECTION).document(exerciseId).set(data).get(15, TimeUnit.SECONDS);
            } catch (Exception failure) {
                storage.delete(bucket, objectName);
                throw failure;
            }
            if (previous.exists()) deleteOldObject(previous);
            return new VideoMetadata(exerciseId, filename, contentType, file.getSize(), uploadedAt);
        } catch (ResponseStatusException invalidFile) {
            throw invalidFile;
        } catch (Exception failure) {
            throw unavailable("Could not upload video", failure);
        }
    }

    @DeleteMapping("/{exerciseId}")
    public void delete(@PathVariable String exerciseId, @AuthenticationPrincipal FirebasePrincipal principal) {
        validateExerciseId(exerciseId);
        requireVideoAdmin(principal);
        try {
            var docRef = firestore.collection(COLLECTION).document(exerciseId);
            DocumentSnapshot doc = docRef.get().get(15, TimeUnit.SECONDS);
            if (!doc.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No video for this exercise");
            // Remove the playable reference first. A failed object deletion leaves only a private orphan.
            docRef.delete().get(15, TimeUnit.SECONDS);
            deleteOldObject(doc);
        } catch (ResponseStatusException missing) {
            throw missing;
        } catch (Exception failure) {
            throw unavailable("Could not delete video", failure);
        }
    }

    private void deleteOldObject(DocumentSnapshot previous) {
        String oldBucket = previous.getString("bucket");
        String oldName = previous.getString("objectName");
        if (bucket.equals(oldBucket) && oldName != null && oldName.startsWith("exercises/")) {
            try {
                storage.delete(oldBucket, oldName);
            } catch (Exception failure) {
                log.warn("Old private video object could not be deleted: {}", oldName, failure);
            }
        }
    }

    private VideoMetadata metadata(DocumentSnapshot doc) {
        Long size = doc.getLong("size");
        return new VideoMetadata(doc.getId(), doc.getString("filename"), doc.getString("contentType"),
                size == null ? 0 : size, doc.getString("uploadedAt"));
    }

    private void validateExerciseId(String exerciseId) {
        if (!EXERCISE_ID.matcher(exerciseId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid exercise ID");
        }
    }

    private void requireVideoAdmin(FirebasePrincipal principal) {
        if (principal == null || !allowedAdminUids.contains(principal.uid())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This admin account cannot manage videos");
        }
    }

    private ResponseStatusException unavailable(String message, Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        log.error(message, failure);
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, failure);
    }
}
