# TapTrack Field (Android)

Kotlin + Jetpack Compose field app. Works offline and syncs with the same Supabase database as the web app.

## Build
1. Open the `android` folder in **Android Studio** (or VS Code with the Android/Gradle extensions).
2. Let Gradle sync, then **Run ▶** on a phone, or **Build → Build APK(s)**.
3. The Supabase URL and the *publishable* key are built in. To point at another project, add these to `android/local.properties` (git-ignored):
   ```
   supabase.url=https://YOUR-PROJECT.supabase.co
   supabase.key=sb_publishable_...
   ```
   Never put the `service_role` key in the app.
4. Release signing (optional): create `android/keystore.properties` (git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Without it, release builds use the debug key.

App id `com.taptrack.field`, so it installs beside the old v5 app.

## Sign-in and roles
- The app uses the same accounts as the web app. Sign-up is off; the admin creates the users.
- `viewer` can only view. `field`, `office` and `admin` can edit.

## Sync
- Every change is saved on the phone first, then uploaded automatically: after each save, every minute, or with **More → Sync now**.
- Codes (`SN-TS-001`), application numbers and account numbers are assigned **by the server** when the change uploads.
- An upload the server refuses is marked **⚠**. Open the item to see the reason.
- Signing out deletes this phone's copy of the data. The app warns you if any changes haven't uploaded yet.

## Features (Phase 1)
- **Map:**
  - Status-coloured tapstands, with a toggle to show or hide proposed ones.
  - Satellite view and offline area download.
  - Shared map layers (barangay boundaries, pipelines): added by the office on the web (Settings → Map layers), downloaded on sync, usable offline. Phones can only switch them on/off.
  - Place search, compass, route (OSRM) and Google Maps directions.
  - Long-press to add a tapstand.
- **Tapstands:**
  - Search and filter: Official / Proposed / Cancelled, and by barangay.
  - Add new: as official, or as proposed.
  - Proposed locations:
    - **Verify & make official** gets a code.
    - **Move** it with GPS or by picking on the map; the app shows the distance from the planned (KML) spot.
    - **Cancel** it with a reason, or **Reopen** it later.
  - Photos taken here upload to Storage.

## Phase 2 (next)
- Applicant intake and validation.
- Tapstand and slot assignment: same barangay only, with the capacity warning.
- Meter number by barcode scan.
- House photos.
