# Backstream

An Android app for watching live streams published on Nostr. It plays HLS streams in the background (screen off or app closed), shows read-only live chat, and lists who is live, ranked by whether you follow them. It can also ask a recording server to record a stream.

## What it runs on

- Android 9 or newer (minSdk 28, targetSdk 34).
- Talks to Nostr relays (defaults: relay.damus.io, nos.lol, relay.primal.net, relay.snort.social, plus purplepag.es for profile lookups) and to whatever stream URL you play.
- Recording and signing in go through a Nostr signer app (Keykin or Amber) via the `nostrsigner:` scheme. Recording posts to a "tube" server, `https://tube.syb.lol` by default, changeable in the app.
- Opens and accepts shared zap.stream links, naddr, nevent, npub and direct .m3u8 URLs.

## Install

Every push to `main` runs `.github/workflows/build.yml`, which builds a debug APK and publishes it as a GitHub release (tag `v0.1.<run number>`, file `backstream.apk`). Download it from the Releases page and sideload it. There is no Play Store listing.

To build from source (JDK 17, Android SDK 34, Gradle 8.9):

    gradle assembleDebug

The APK lands in `app/build/outputs/apk/debug/`. The repo has no Gradle wrapper, so use an installed Gradle.

## Status

Built for my own use. It works on my setup. No support promised.
