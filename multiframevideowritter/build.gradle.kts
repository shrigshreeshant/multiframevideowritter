plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id ("maven-publish")
}

android {
    namespace = "com.fishtechy.multiframevideowritter"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation("io.github.crow-misia.libyuv:libyuv-android:0.43.2")

}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.github.shrigshreeshant" // 👈 must match your GitHub username
                artifactId = "multiframevideowritter"
                version = "1.0.0"

                pom {
                    name.set("MultiframeVideoWritter")
                    description.set("A lightweight Android utility for writing multiple NV12/YUV frames into MP4 videos.")
                    url.set("https://github.com/shrigshreeshant/multiframevideowritter")
                }
            }
        }
    }
}