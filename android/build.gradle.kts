// RIVEM Android. `core` is the Kotlin port of `ios/Sources/RiverCore` (pure JVM, no Android APIs), tested with the
// same cases and `ci/testdata`; `app` (Jetpack Compose) is the Android app on top of it.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
}
