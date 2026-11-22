# Sound Trigger Cam

An Android app that starts recording video automatically when the microphone level goes
above a threshold you choose. It also has a **Sound Level Test** screen for tuning that threshold.

## Download & Install

You can download the pre-built, signed release APK directly from this repository:

- **[Download SoundTriggerCam-v1.0.apk](release/SoundTriggerCam-v1.0.apk)** (v1.0 Release)

### Installation instructions

- **Direct sideload**: Transfer or download `SoundTriggerCam-v1.0.apk` onto your Android device (Android 8.0+ / API 26+). When prompted, tap the notification or file in your file manager and allow "Install unknown apps" for that source.
- **Via ADB**: Connect your phone with USB debugging enabled and run:
  ```powershell
  adb install release/SoundTriggerCam-v1.0.apk
  ```

---

## Features

- **Monitor tab**: camera preview, live mic meter, threshold control, and an **Arm** button.
  While the app is armed, a sound that stays above the threshold for the *hold time* starts a recording.
  Recording stops after the set number of seconds of quiet (it respects the min/max clip length).
  There is also a manual record button.
- **Level Test tab**: shows the level in large text, a meter with 1.5 s peak hold, a 10-second
  history graph with the threshold line, min/avg/max stats, and a **"would trigger" lamp and counter**.
  Nothing is recorded on this tab. You can set the threshold by tapping or dragging on the meter,
  with the slider, by putting it just above the session max, or with the **5-second background calibration**
  (background max + margin).
- **Detection mode**: RMS (overall loudness) or Peak (sharp sounds such as claps or knocks).
- **Settings tab**: post-trigger time, min/max clip length, cooldown, audio on/off,
  front/back camera, and video quality (SD/HD/FHD/UHD).
- Clips are saved to `Movies/SoundTriggerCam/STC_yyyyMMdd_HHmmss.mp4` (MediaStore).
- Settings are saved between launches.

Levels are in **dBFS** (0 dB = loudest level the mic can record; −90 dB = silence). Mic noise on a phone
is usually −70 to −50 dBFS. Speech close to the phone is roughly −35 to −20 dBFS.

## Build & run

Requirements: JDK 17 or 21 and the Android SDK (platform 34).

```powershell
# Build debug APK
.\gradlew.bat assembleDebug

# Build optimized and signed release APK
.\gradlew.bat assembleRelease

# Install directly to a connected device via adb
.\gradlew.bat installDebug
```

Or open the repository in Android Studio.

## Implementation notes

| File | Role |
|---|---|
| `audio/SoundLevelMonitor.kt` | `AudioRecord` at 44.1 kHz, outputs RMS/peak dBFS every 50 ms. Uses the `UNPROCESSED` source when the device supports it, otherwise `VOICE_RECOGNITION`, so automatic gain control doesn't change the levels. |
| `camera/CameraController.kt` | CameraX `Preview` + `VideoCapture<Recorder>`. Saves to MediaStore. |
| `MainViewModel.kt` | Trigger engine (threshold, hold time, post-trigger timeout, min/max clip length, cooldown), level-test stats, and calibration. |
| `ui/*` | Jetpack Compose screens and the meter/graph components. |

### Limitations

- The app only listens while it is **in the foreground**. The screen stays on while the app is armed.
  If you leave the app, it disarms and stops any recording in progress. Listening in the background
  would need a foreground service of type `camera|microphone`.
- No pre-roll: the clip begins when the trigger fires, so the first moment of the sound may be missed.
  Lower the hold time to start sooner.
- The level meter and the video's audio track both use the mic at the same time. This works on most
  devices running Android 10+. If a device silences the meter during recording, clips end after the
  minimum clip length / post-trigger timeout. Turning off "Record audio in video" avoids the conflict entirely.

## License

This project is licensed under the terms of the GNU General Public License v3.0 (GNU GPLv3). See the [LICENSE](LICENSE) file for the full license text.
