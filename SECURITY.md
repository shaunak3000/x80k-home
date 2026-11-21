# Security

## What the app can and can't do

- **One permission: internet.** Frame uses it only to download featured landscape photos from Wikimedia Commons.
  With photos switched off in Frame's settings, it never goes online.
- **One destination.** The download code refuses any address that isn't Wikimedia's API or image servers, and doesn't
  follow redirects. `build.sh` fails the build if a web address other than Wikimedia's appears anywhere in the app.
- **Nothing about you is sent.** No app list, no viewing history, no identifiers. Wikimedia sees the TV's IP address
  and a User-Agent naming this project, as with any website visit.
- **No other code.** No libraries: `build.sh` fails the build if any class in the APK comes from outside this repo.
- **What it stores on the TV:** your row's order, which apps are hidden, Frame's settings, and the last 20 photos
  with their credits. All of it is in the app's private storage and is removed when the app is uninstalled.

## What `tv.sh` does to the TV

It talks to the TV over adb, Google's debugging connection, and only runs these commands:

- `pm disable-user` and `pm enable` on the packages in `models/<platform>/disable.txt`, plus Google TV's two home
  screens for `home` and `stock`. Nothing is ever uninstalled. It refuses any package that's also in `keep.txt`.
- `cmd package set-home-activity` to choose the home screen, and `install` for the app itself.

**USB debugging is unencrypted on the network** and lets an approved computer control the TV. Turn it off when
you're done. If the TV ever asks "Allow USB debugging?" when you didn't start anything, press Cancel.

## Verifying a release

Each release lists the APK's SHA-256, the signing certificate's SHA-256, and the SHA-256 of its code
(`classes.dex`). To confirm a release is built from this repo's code:

```sh
git checkout v1.0.0 && ./build.sh
unzip -p build/unsigned.apk classes.dex | shasum -a 256
unzip -p x80k-home-1.0.0.apk classes.dex | shasum -a 256    # the same
```

Builds are reproducible across machines, so the two match byte for byte. Every release is signed with the same
key, so updates install over earlier versions.

## Reporting a problem

Use **Report a vulnerability** on the repo's Security tab to report privately, or open an issue for anything that
isn't sensitive.
