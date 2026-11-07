plugins {
    id("webshell.android-application")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Local HTTP + WebSocket server (Ktor CIO) serving the xterm.js page
    // and bridging WebSocket frames to a shell on the phone.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    // Tiny JSON control messages (terminal resize) on the WebSocket.
    implementation(libs.kotlinx.serialization.json)
    // Immutable lists in UiState, so Compose skips unchanged rows.
    implementation(libs.kotlinx.collections.immutable)
    // ContextCompat / NotificationCompat, used directly.
    implementation(libs.androidx.core.ktx)
    // Unit tests (JUnit 4).
    testImplementation(libs.junit)
}
