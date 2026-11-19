plugins {
    java
}

// Windows에서 프로젝트 경로에 한글이 있으면 테스트 JVM이 클래스 경로를 못 읽는다 (ClassNotFoundException).
// 그때만 빌드 출력을 ASCII 경로로 옮긴다. Docker · 리눅스에서는 그대로 server/build.
if (projectDir.absolutePath.any { it.code > 127 }) {
    layout.buildDirectory = file("${System.getProperty("user.home")}/.gradle/project-builds/minired")
}

repositories {
    mavenCentral()
}

dependencies {
    // 본체는 JDK만. 아래는 테스트 전용 (ADR-0002)
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("redis.clients:jedis:5.2.0")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.16")
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName = "minired.jar"
    manifest {
        attributes("Main-Class" to "minired.Main")
    }
}
