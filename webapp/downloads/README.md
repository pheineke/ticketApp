# APK drop folder

Put the built Android APK here (e.g. `myVRN-Lite-1.0.apk`, from
`android/app/build/outputs/apk/release/`). The web app's **App** tab (`/app`) offers the
newest `*.apk` in this folder for download and lists older ones below it. No restart needed.

APKs are git-ignored. With Docker, this folder is mounted into the container (see
`docker-compose.yml`); elsewhere set `APK_DIR` to use another folder.
