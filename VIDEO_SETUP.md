# Private exercise videos

Both APIs use the same Firestore database (`physiomotionplusdb`) and private Cloud Storage bucket. The admin API stores MP4/WebM files and Firestore metadata in `exerciseVideos/{exerciseId}`. The customer API accepts only a valid customer Firebase ID token, then returns a short-lived V4 GET URL for that video's object. The browser streams the bytes directly from Cloud Storage; the customer API does not relay the video.

## Local environment

- Admin API (`:8081`): use `GOOGLE_APPLICATION_CREDENTIALS` pointing to the **admin** service-account JSON.
- Admin API: set `VIDEO_ALLOWED_ADMIN_UIDS` to your Firebase **admin-project user UID** (comma-separate additional UIDs). Video endpoints fail closed with 403 when this is unset. Find your UID under Firebase Console → Authentication → Users in `physiomotionplusapp-admin`. Keep this allowlist in the process environment, not the repo.
- Customer API (`:8080`): use `GOOGLE_APPLICATION_CREDENTIALS` pointing to the **customer** service-account JSON.
- Both APIs: set `VIDEO_BUCKET=physiomotionplus-videos-dev` (the development default is already configured in `application.properties`). Do not point production at this bucket; use a separate private production bucket and set `VIDEO_BUCKET` in each production process.
- Keep the JSON keys outside source control. In IntelliJ's environment-variable field, enter `GOOGLE_APPLICATION_CREDENTIALS=C:\path\to\key.json` once. The variable's *value* must be the file path only.

## Required Google Cloud permissions

- Admin service account: **Storage Object Admin** on the selected bucket, plus Firestore read/write access in `physiomotionplusdb`.
- Customer service account: **Storage Object Viewer** on the selected bucket, plus Firestore read access in `physiomotionplusdb`.
- Keep Public Access Prevention and uniform bucket-level access enabled. Do not grant `allUsers` access.
- Service-account JSON credentials sign URLs locally. For a keyless production runtime, set `VIDEO_SIGNER_SERVICE_ACCOUNT` on the customer API to its signing service account email, enable IAM Service Account Credentials API, and grant the runtime identity `iam.serviceAccounts.signBlob` on that signing account (for example, Service Account Token Creator). The signing account also needs Storage Object Viewer on the bucket.

## Endpoints

- `GET /api/admin/videos` lists video metadata, not playable URLs.
- `PUT /api/admin/videos/{exerciseId}` takes multipart field `file` (MP4/WebM, max 250 MB) and replaces that exercise's prior video.
- `DELETE /api/admin/videos/{exerciseId}` removes the Firestore reference and the private object.
- `GET /api/exercises/{exerciseId}/video` returns `{url, expiresAt, contentType}` for authenticated customers. An absent video returns 404. The customer API currently allows every authenticated customer; tier and assignment checks must be added here before restricting plans.

The signed URL can be copied and used by anyone until it expires. Its response is marked `no-store`, but do not treat the URL as tied to the logged-in user's identity. The admin app's module/exercise editor is still sample data; the real video metadata persists in Firestore and is matched to the customer app's current exercise IDs.
