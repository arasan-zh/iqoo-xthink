plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :guidance is PURE KOTLIN. No Android plugin, no Android dependencies, ever.
// If you find yourself wanting `android.util.Log` or `org.json.JSONObject`
// here, the code belongs in :app instead.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

sourceSets {
    test {
        // Parse the exact JSON the app ships in its assets, so :guidance and
        // :app cannot drift apart. Test-only: no Android asset API is used.
        resources.srcDir(rootProject.file("app/src/main/assets"))
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
