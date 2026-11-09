import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.projectnuke.fusion"
    testBuildType = "qualification"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.projectnuke.fusion"
        minSdk = 32
        targetSdk = 36
        versionCode = 20400
        versionName = "2.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
        }
        create("qualification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qualification"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.systemProperty(
                "litertlm.golden.path",
                (project.findProperty("litertlm.golden.path") ?: "").toString()
            )
        }
    }
    // Room schema export for migration testing
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    // Room schema assets for instrumentation tests (MigrationTestHelper)
    sourceSets.configureEach {
        if (name == "androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

val verifyKananaTemplateAsset = tasks.register("verifyKananaTemplateAsset") {
    val asset = layout.projectDirectory.file(
        "src/main/assets/kanana-2-1.3b-instruct.bf4786aa.chat_template.jinja"
    )
    inputs.file(asset)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(asset.asFile.readBytes())
            .joinToString("") { value: Byte -> "%02X".format(value) }
        check(actual == "B8EE6B31575EADA17EBBE73D3F1AC65D3EFDE64F0A25FF922031DEC7E1CAE3E3") {
            "Pinned Kanana template asset hash changed: $actual"
        }
    }
}

val verifyPackagedKananaTemplateAsset = tasks.register("verifyPackagedKananaTemplateAsset") {
    val packagedAsset = layout.buildDirectory.file(
        "intermediates/assets/debug/mergeDebugAssets/kanana-2-1.3b-instruct.bf4786aa.chat_template.jinja"
    )
    inputs.file(packagedAsset)
    doLast {
        val file = packagedAsset.get().asFile
        check(file.isFile) { "Packaged Kanana template asset is missing: ${file.absolutePath}" }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { value: Byte -> "%02X".format(value) }
        check(actual == "B8EE6B31575EADA17EBBE73D3F1AC65D3EFDE64F0A25FF922031DEC7E1CAE3E3") {
            "Packaged Kanana template asset hash changed: $actual"
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(verifyKananaTemplateAsset) }
tasks.matching { it.name == "mergeDebugAssets" }
    .configureEach { finalizedBy(verifyPackagedKananaTemplateAsset) }

val verifyKoEnE5Assets = tasks.register("verifyKoEnE5Assets") {
    val model = layout.projectDirectory.file("src/main/assets/koen-e5/model_qint8_arm64.onnx")
    val tokenizer = layout.projectDirectory.file("src/main/assets/koen-e5/tokenizer.json")
    inputs.file(model)
    inputs.file(tokenizer)
    doLast {
        fun sha256Hex(file: java.io.File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { value: Byte -> "%02x".format(value) }
        val modelFile = model.asFile
        val tokenizerFile = tokenizer.asFile
        check(modelFile.isFile) { "KoEn-E5 model asset is missing: ${modelFile.absolutePath}" }
        check(tokenizerFile.isFile) { "KoEn-E5 tokenizer asset is missing: ${tokenizerFile.absolutePath}" }
        check(sha256Hex(modelFile) == "d463c9b1c29f3202d510e2836265d60f9e6e6dd518c5930ba865700646489bd4") {
            "Pinned KoEn-E5 model asset hash changed: ${sha256Hex(modelFile)}"
        }
        check(sha256Hex(tokenizerFile) == "a6dd38d692ac1caa6d5dbc195d92f1f978b5c74ec60e02ed15fdf04404742fe3") {
            "Pinned KoEn-E5 tokenizer asset hash changed: ${sha256Hex(tokenizerFile)}"
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(verifyKoEnE5Assets) }

dependencies {
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation(
        platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1")
    )
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    implementation(libs.org.json)
    testImplementation(libs.flatbuffers.java)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("qualificationImplementation", libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.14.0")
    // S1 optional Ko/En semantic encoder (ONNX, CPU, offline). Encoder-only;
    // never an autoregressive LLM runtime. Backend fails closed when assets
    // are not provisioned, so Evidence keeps deterministic behavior.
    implementation(libs.onnxruntime.android)
    // Isolated, guarded DNS transport for web clips. Generic search keeps its
    // existing bounded HttpURLConnection transport.
    implementation(libs.okhttp)
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
    // Official AndroidX PDF text-layer stack (sandboxed service).
    implementation(libs.androidx.pdf.core)
    implementation(libs.androidx.pdf.document.service)
    // Phase F: real in-memory SQLite for JVM-side migration tests.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
