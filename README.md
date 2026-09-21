# Volume Leveler (Google TV / Android TV)

Listens through a mic (auto-picks USB > wired > Bluetooth > built-in, or choose one) and nudges
the TV's master volume to keep the room level near a target.

## Build the APK without installing anything (GitHub Actions)
1. Create a new GitHub repo and upload/push all of these files (branch `main`).
2. The "Build APK" workflow runs automatically (Actions tab) and publishes a Release.
3. In the Downloader app on the TV, enter:
   https://github.com/<user><repo>releases/latest/download/app-debug.apk
4. Allow "Install unknown apps" for Downloader when prompted, then install.

## Or build locally
Install Android Studio, open this folder, Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk

## Using it
Open the app, press Start leveling (grant mic permission), play content at a comfortable volume,
then press "Set target = current room level".
