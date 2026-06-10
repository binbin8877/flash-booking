plugins {
    // Java toolchain 자동 다운로드 (로컬에 JDK 21 없어도 gradle 이 자동 설치)
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "flash-booking"
