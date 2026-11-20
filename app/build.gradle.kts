plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mario.mypdf"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mario.mypdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared", "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    // Importate direttamente dal codice dell'app: dichiararle esplicitamente
    // evita di dipendere dalla presenza accidentale di dipendenze transitive.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.print:print:1.0.0")

    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")

    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("androidx.viewpager2:viewpager2:1.1.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation("org.opencv:opencv:4.12.0")

    implementation("com.google.android.material:material:1.12.0")

    implementation("io.coil-kt:coil:2.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OCR Google ML Kit (testo da immagine)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PDF testuale con iText7 (community)
    implementation("com.itextpdf:itext7-core:7.2.5")

    // Word .docx con Apache POI
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")

    // OpenOffice .odt con ODF Toolkit
    implementation("org.odftoolkit:odfdom-java:0.9.0")

    // Risoluzione conflitti BouncyCastle
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15to18:1.72")
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.72")
        implementation("org.bouncycastle:bcutil-jdk15to18:1.72")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

configurations.all {
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk15to18:1.72")
        force("org.bouncycastle:bcpkix-jdk15to18:1.72")
        force("org.bouncycastle:bcutil-jdk15to18:1.72")
    }
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcutil-jdk15on")
}
