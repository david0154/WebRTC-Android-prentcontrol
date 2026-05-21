// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // FIX Bug 2 — google-services plugin required for Firebase
    id("com.google.gms.google-services") version "4.4.2" apply false
}
