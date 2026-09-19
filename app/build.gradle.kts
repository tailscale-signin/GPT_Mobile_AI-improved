dependencies {
    // Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation("com.google.android.material:material:1.12.0")

    // SplashScreen
    implementation(libs.splashscreen)

    // DataStore
    implementation(libs.androidx.datastore)

    // Dependency Injection
    implementation(libs.hilt)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    // Ktor
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.serialization)
    implementation(libs.mcp.kotlin.sdk.client) {
        // The SDK bytecode targets Kotlin 2.1, but its 2.4 stdlib confuses Hilt's 2.3 metadata reader.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    // OAuth browser flow
    implementation(libs.androidx.browser)
    implementation(libs.openid.appauth)

    // On-device LiteRT-LM serving
    implementation(libs.litertlm)

    // Qualcomm AI Engine Direct (QNN) SDK and LiteRT Delegate
    implementation(libs.qnn.runtime)
    implementation(libs.qnn.litert.delegate)

    // License page UI
    implementation(libs.auto.license.core)
    implementation(libs.auto.license.ui)

    // Markdown
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)

    // Navigation
    implementation(libs.hilt.navigation)
    implementation(libs.androidx.navigation)

    // Room
    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Serialization
    implementation(libs.kotlin.serialization)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}