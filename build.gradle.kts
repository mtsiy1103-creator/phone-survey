// The Android Gradle plugin needs Google's Maven repository; load it only where an Android SDK
// is present (see settings.gradle.kts) so the core module can be built and tested anywhere.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.0")
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            File(rootDir, "local.properties").exists()
        ) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
