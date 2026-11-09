plugins {
    id("com.android.application")
}

android {
    namespace = "org.encryptedspaces.android"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "org.encryptedspaces.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += providers.gradleProperty("spacesAbi").getOrElse("arm64-v8a")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.packaging.jniLibs.keepDebugSymbols.add("**/libspaces_android.so")
        // Rust's symbolizer needs an ELF file it can open, not an APK entry.
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Compile through build.sh so the Rust library and APK use the same build mode.
for (mode in listOf("debug", "release")) {
    val capitalized = mode.replaceFirstChar { it.uppercase() }
    val nativeCheck = tasks.register("check${capitalized}NativeLibrary") {
        doLast {
            val abi = providers.gradleProperty("spacesAbi").getOrElse("arm64-v8a")
            check(file("src/$mode/jniLibs/$abi/libspaces_android.so").isFile) {
                "Missing $mode SDK JNI library for $abi. Run ./build.sh $mode $abi first."
            }
        }
    }
    tasks.matching { it.name == "pre${capitalized}Build" }.configureEach {
        dependsOn(nativeCheck)
    }
}
