plugins {
    application
}

// Windows에서 프로젝트 경로에 한글이 있으면 테스트 JVM이 클래스 경로를 못 읽는다 (미니레디스와 같은 우회).
// Docker · 리눅스에서는 그대로 demo/build.
if (projectDir.absolutePath.any { it.code > 127 }) {
    layout.buildDirectory = file("${System.getProperty("user.home")}/.gradle/project-builds/mini-stack-demo")
}

repositories {
    mavenCentral()
}

dependencies {
    // Q12-B: 레디스 클라이언트는 Jedis (미니레디스 기능 테스트의 판정자와 같은 것). 나머지는 JDK만
    implementation("redis.clients:jedis:5.2.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "demo.Main"
    applicationName = "demo"
}

tasks.withType<JavaCompile> {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
