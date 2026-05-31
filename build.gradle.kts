plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<UpdateResourceFileAssetsTask>("updateResourceFileAssets") {
    xrayCoreVersion.set(ProjectConfig.XRAY_CORE_VERSION)
    hevSocks5TunnelVersion.set(ProjectConfig.HEV_SOCKS5_TUNNEL_VERSION)
    xrayCoreFile.set(layout.projectDirectory.file("app/build/generated/xrayCoreJniLibs/arm64-v8a/libxray.so"))
    hevSocks5TunnelJniLibsDir.set(layout.projectDirectory.dir("app/build/generated/hevSocks5TunnelJniLibs"))
}
