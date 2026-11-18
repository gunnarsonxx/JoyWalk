// Compile-time stand-ins for the Xposed API (de.robv.android.xposed:api:82).
// The app uses this with compileOnly, so none of it ends up in the APK: on the phone
// LSPosed/Xposed provides the real classes. Only the members JoyWalk calls are declared,
// with signatures identical to the real API.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
