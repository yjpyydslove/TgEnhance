plugins {
    // v N1.16：AGP 8.7.3 -> 8.9.1。
    //
    // 不是随便升的：compileSdk = 36 要求 AGP >= 8.9.0（更早的 AGP
    // 压根不知道 Android 16 存在），而 AGP 8.9 又要求 Gradle >= 8.11.1
    // （CI 里同步改了）。这是一条链，缺一环就构建不下去。
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
