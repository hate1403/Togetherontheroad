plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "io.togetherroad.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.togetherroad.app"
        minSdk = 28
        targetSdk = 35 // Bench prototype; store target policy must be reviewed before distribution.
        versionCode = 1
        versionName = "0.1.0"
    }
    flavorDimensions += "platform"
    productFlavors {
        create("replay") { dimension = "platform"; applicationIdSuffix = ".replay" }
        create("pleos") { dimension = "platform"; applicationIdSuffix = ".pleos" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
}
