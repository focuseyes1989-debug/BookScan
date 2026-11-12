# BookScan

BookScan captures paper documents on Android and exports the ordered scans as a PDF.

## Updating on Android

Tap **Update** in BookScan. If a newer APK is available, download it and open it. Confirm **Update** in the Android package installer. Install over the existing app to keep its scanned pages and settings.

## Publishing an update

1. Increase `versionCode` and update `versionName` in `app/build.gradle`.
2. Set `BOOKSCAN_KEYSTORE`, `BOOKSCAN_STORE_PASSWORD`, `BOOKSCAN_KEY_ALIAS`, and `BOOKSCAN_KEY_PASSWORD` in the build environment. Use the same signing key as the installed APK. Keep the keystore and passwords outside Git and keep a secure backup of the keystore.
3. Run `./gradlew :app:assembleRelease` (Windows uses `gradlew.bat`). Verify the APK's signature and test installing it over the prior version.
4. Publish a GitHub Release tagged `v<versionName>` with the signed APK named `BookScan-<versionCode>.apk`. BookScan checks the latest published release for this asset.
