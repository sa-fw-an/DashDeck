import java.util.Properties
import java.io.File
import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    id("com.google.android.gms.oss-licenses-plugin")
}

android {
    namespace = "org.dashdeck.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.dashdeck.app"
        minSdk = 35
        targetSdk = 37
        versionCode = 5
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Shields engine is built for arm64 only (every Android 15+ phone).
        ndk { abiFilters += "arm64-v8a" }

        fun quoted(key: String) = "\"" + (project.findProperty(key) as? String).orEmpty() + "\""
        buildConfigField("String", "SHIELDS_ENGINE_PUBLIC_KEY", quoted("dashdeck.shieldsEnginePublicKey"))
        val repo = (project.findProperty("dashdeck.githubRepo") as? String).orEmpty()
        val feed = (project.findProperty("dashdeck.shieldsEngineFeed") as? String).orEmpty()
            .ifBlank { "https://github.com/$repo/releases/download/shields-engine/shields-engine.json" }
        buildConfigField("String", "GITHUB_REPO", "\"$repo\"")
        buildConfigField("String", "SHIELDS_ENGINE_FEED", "\"$feed\"")
    }

    // Release signing comes from local.properties or environment variables
    // (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD).
    // Without them, release builds are left unsigned.
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    }
    fun secret(key: String): String? =
        project.findProperty(key) as? String ?: localProps.getProperty(key) ?: System.getenv(key)
    val releaseStore = secret("RELEASE_STORE_FILE")?.let(rootProject::file)?.takeIf { it.exists() }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }


    lint {
        // The manifest declares a media car app without a media service, and a
        // VIEW filter on the car launcher activity, on purpose: that is how
        // Android Auto lists the browser. Lint flags both patterns.
        disable += setOf(
            "MissingIntentFilterForMediaSearch",
            "MissingMediaBrowserServiceIntentFilter",
            "AppLinkUrlError",
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidComponents {
        onVariants { variant ->
            val vNameStr = android.defaultConfig.versionName ?: "unknown"
            val appNameStr = "DashDeck"
            val isDebug = variant.buildType == "debug"
            val debugSuffixStr = if (isDebug) "_debug" else ""

            val renameTaskProvider = tasks.register<RenameApkTask>("${variant.name}RenameApk") {
                inputDir.set(variant.artifacts.get(SingleArtifact.APK))
                outputDir.set(layout.buildDirectory.dir("renamedApks/${variant.name}"))

                appName.set(appNameStr)
                versionNameProp.set(vNameStr)
                debugSuffixProp.set(debugSuffixStr)
            }

            afterEvaluate {
                val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercase() }}"
                if (tasks.findByName(assembleTaskName) != null) {
                    tasks.named(assembleTaskName).configure {
                        finalizedBy(renameTaskProvider)
                    }
                }
            }
        }
    }
}

// Shields engine: Brave's adblock-rust, built with cargo for arm64 Android.
val braveAdblockTarget = "aarch64-linux-android"
val braveAdblockOutput = layout.buildDirectory.dir("generated/jniLibs/arm64-v8a")
val buildBraveAdblockNative by tasks.registering(Exec::class) {
    val hostTag = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
    val ndk = androidComponents.sdkComponents.ndkDirectory
    // A rustup-managed cargo is needed for the Android target; Homebrew's rust lacks it.
    val cargo = (project.findProperty("dashdeck.cargo") as? String)
        ?: listOf("/opt/homebrew/opt/rustup/bin/cargo", "${System.getProperty("user.home")}/.cargo/bin/cargo")
            .firstOrNull { File(it).canExecute() }
        ?: "cargo"
    workingDir(rootProject.file("native/brave-adblock"))
    // Keep cargo and its rustc from the same toolchain.
    File(cargo).parentFile?.let { environment("PATH", "${it.absolutePath}:${System.getenv("PATH")}") }
    commandLine(cargo, "build", "--release", "--target", braveAdblockTarget)
    doFirst {
        val linker = ndk.get().asFile.resolve("toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android35-clang")
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
    }
    inputs.dir(rootProject.file("native/brave-adblock/src"))
    inputs.files(rootProject.file("native/brave-adblock/Cargo.toml"), rootProject.file("native/brave-adblock/Cargo.lock"))
    outputs.file(rootProject.file("native/brave-adblock/target/$braveAdblockTarget/release/libdashdeck_brave_adblock.so"))
}
val copyBraveAdblockNative by tasks.registering(Copy::class) {
    dependsOn(buildBraveAdblockNative)
    from(rootProject.file("native/brave-adblock/target/$braveAdblockTarget/release/libdashdeck_brave_adblock.so"))
    into(braveAdblockOutput)
}
tasks.named("preBuild") { dependsOn(copyBraveAdblockNative) }
android.sourceSets.getByName("main").jniLibs.srcDir(braveAdblockOutput.get().asFile.parentFile)

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.google.material)
    implementation(libs.androidx.car.app)
    implementation(libs.zxing.core)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.oss.licenses)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

abstract class RenameApkTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val appName: Property<String>

    @get:Input
    abstract val versionNameProp: Property<String>

    @get:Input
    abstract val debugSuffixProp: Property<String>

    @TaskAction
    fun run() {
        val inDir = inputDir.get().asFile
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val app = appName.get()
        val vName = versionNameProp.get()
        val debugSuffix = debugSuffixProp.get()

        inDir.listFiles()?.filter { it.extension == "apk" }?.forEach { f ->
            val newName = "${app}-${vName}${debugSuffix}.apk"
            val dest = File(outDir, newName)
            f.copyTo(dest, overwrite = true)
            println("APK Renamed and Copied to: ${dest.absolutePath}")
        }
    }
}
