plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

fun signProp(name: String, def: String): String =
    (project.findProperty(name) as? String) ?: def

android {
    namespace = "com.example.txtreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.txtreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            val pwd = signProp("txtStorePassword", "")
            storeFile = rootProject.file(signProp("txtStoreFile", "txtreader.jks"))
            storePassword = pwd
            keyAlias = signProp("txtKeyAlias", "txtreader")
            keyPassword = signProp("txtKeyPassword", pwd)
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Phase 7：google-auth 那幾包都有 INDEX.LIST，打包會撞，排除掉
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Phase 7：Google 登入＋Drive REST（版本已對過 POM／release notes，別亂升）
    // httpclient 在 Android 用不到（走 HttpURLConnection），源頭排除免得 META-INF 打架
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20260712-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.api-client:google-api-client-android:2.7.2") {
        exclude(group = "org.apache.httpcomponents")
    }
}

// 產物檔名：release 輸出叫 TxtReader.apk（不用每次找 app-release.apk）
android.applicationVariants.all {
    if (buildType.name == "release") {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "TxtReader.apk"
        }
    }
}
