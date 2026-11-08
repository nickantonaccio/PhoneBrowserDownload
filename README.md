# Phone Browser Download — Android

An Android 8.0+ app that serves **read-only** files from one user-approved folder to another computer's ordinary web browser on the **same private LAN/Wi-Fi network**.

## Build / APK

**Android Studio**: Open this project, allow Android Gradle Plugin 8.6.1 and SDK 35 to install, then choose **Build > Build APK(s)**. The debug APK will be `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub Actions**: Create a GitHub repository and upload/push the contents of this project (including `.github`). Open **Actions > Build Android APK > Run workflow**, then download the `PhoneBrowserDownload-debug-apk` artifact. Unzip the artifact to get the APK. No paid service is needed for a normal public repository (GitHub usage limits apply).

The APK is debug-signed for personal sideloading. Production distribution should use a controlled release signing key.

## Usage

1. Install the APK on your phone, opening it and allowing notifications if desired.
2. Both phone and computer must be on the same Wi-Fi/LAN, with device-to-device connections allowed.
3. Tap **Choose folder to share** and approve the folder in Android's system picker.
4. Tap **Start sharing**. Note the URL and password shown on the phone.
5. Open the URL (e.g., `http://192.168.1.42:8765/`) in a browser on the computer. When prompted, enter username **phone** and the password displayed on the phone.
6. Browse folders and click files to download them to the computer. Stop sharing on the phone when done.

The browser may show a 'Not secure' label because this tool is HTTP-only. **Use only a trusted private network**, not public Wi-Fi, guest Wi-Fi, or a network shared with untrusted users. No internet forwarding, router port forwarding, or public IP exposure. HTTP Basic credentials and transferred files are **not encrypted** in transit. Don't expose sensitive or regulated data this way.

## Design and limitations

- Uses Android's Storage Access Framework; no all-files-storage permission or external-storage legacy permission.
- Supports selected folder and its descendants, where Android's document provider permits access. Some Android system folders cannot be selected.
- Browse and download only. No uploads, deletions, writes, or renames.
- New random 14-character password on each start; credentials required for all endpoints.
- Random unguessable per-session document handles assigned to files discovered while browsing; no document ID supplied by the browser is trusted.
- Server binds port **8765** on available interfaces, only while sharing is enabled. A foreground service shows an ongoing notification.
- If the network blocks peer connections (AP/client isolation), use another private network or phone hotspot.
- Foreground services may be stopped by Android/battery controls; restart sharing as needed.
- The displayed addresses may include VPN or mobile interfaces: use the **local Wi-Fi IPv4 address** appropriate to your network.
- One folder per session. Choose a different folder and restart sharing to change it.
- Very large directories are capped at 10,000 entries per listing; this is a lightweight single-device app, not a cloud sync or remote access service.
- No TLS, Wi-Fi interface binding, or HTTPS reverse proxy included; do not use across the public internet.

## Dependencies

Only Android SDK platform APIs, Java 17, Android Gradle Plugin 8.6.1, Gradle 8.9. No third-party runtime libraries.
