# Project-specific ProGuard / R8 rules.

# Preserve enum names used in SharedPreferences persistence
-keep enum com.soundtrigger.videorecorder.data.** {
    *;
}
