# Dual-SIM Messenger

A default SMS app for dual-SIM Android phones. Conversations are separated by SIM (SIM 1 blue, SIM 2 green), and a reply always goes out from the SIM the conversation arrived on. I use it every day on my own phone.

Written in **Kotlin** with **Jetpack Compose (Material 3)**: a single module with ViewModel + StateFlow. Requires Android 10 or later. The first version was written in Flutter; this is the native rewrite, built with Claude Code.

## Features

- **Split by SIM:** an "All" tab plus one tab per SIM, each with its unread count. The SIM badge appears only in the "All" tab and in search.
- **SIM name and color:** set in Menu → Settings.
- **MMS:** send photos with the **+** button in a conversation; received photos show inline. MMS needs mobile data on that SIM.
- **New message:** the contact list narrows as you type a name or number in "To".
- **Search:** across contact names, numbers and message text.
- **Call:** the phone icon in a conversation opens the number in the dialer.
- **Blocking:** block a number from the conversation menu or from Settings. It uses the phone's system block list, so blocked numbers can neither text nor call.
- **Message actions:** long-press to copy, delete or resend a failed message.
- **Notifications:** contact and SIM name in the title; reply and mark as read from the notification.

Not in this version: group MMS, sending video or audio, keyword spam filtering, delivery reports.

## Download the APK

GitHub Actions builds a debug APK on every push.

1. Open the **Actions** tab of this repository.
2. Open the latest successful run of the **SMS APK** workflow.
3. Download `cift-sim-mesajlar-debug` from **Artifacts** and unzip it to get `app-debug.apk`.

To build it yourself (JDK 17+ and the Android SDK required):

```
cd sms-uygulamasi
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install and set as default

1. Copy `app-debug.apk` to the phone and open it (allow installs from unknown sources if asked), or run `adb install -r app-debug.apk`.
2. Open the app and tap **Make default SMS app**, then choose this app.
3. Tap **Grant permissions** for SMS, phone, contacts and notifications.

If the dialog does not appear: **Settings → Apps → Default apps → SMS app**. Switching back to your old SMS app is safe; messages live in the phone's shared SMS database.

## Tech

Kotlin · Jetpack Compose (Material 3) · ViewModel + StateFlow · android-smsmms · GitHub Actions
