import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "2.7.0"
    groovy
}

group = "io.moderne.jenkins"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets {
    register("jenkins")
}

repositories {
    maven {
        url = uri("https://repo.jenkins-ci.org/public")
    }
}

dependencies {
    "jenkinsCompileOnly"("org.jenkins-ci.main:jenkins-core:2.332.3")
    "jenkinsCompileOnly"("javax.servlet:javax.servlet-api:4.0.1")

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.core)
    implementation("info.debatty:java-string-similarity:2.0.0")
    implementation(platform(libs.jackson.bom))
    implementation(libs.bundles.retrofit)
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("com.netflix.graphql.dgs:graphql-dgs-client:latest.release")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("ch.qos.logback:logback-classic")

    testImplementation(platform(testLibs.junit.bom))
    testImplementation(platform(testLibs.mockito.bom))
    testImplementation(testLibs.bundles.junit5)
    testImplementation(testLibs.okhttp.mockwebserver)
    testImplementation(libs.commons.io)

    testRuntimeOnly(testLibs.junit.engine)

    compileOnly("org.projectlombok:lombok:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val fetchedDir = layout.buildDirectory.dir("fetched")
tasks.register<JavaExec>("fetch") {
    classpath = configurations["runtimeClasspath"] + sourceSets.main.get().output
    mainClass.set("net.sghill.jenkins.toolkit.FetchFailed")
    systemProperty("baseUrl", "http://localhost:8080")
    systemProperty("outDir", fetchedDir.get())
    systemProperty("script", layout.projectDirectory.file("src/jenkins/groovy/find-failed.groovy"))
}

tasks.register<JavaExec>("categorize") {
    classpath = configurations["runtimeClasspath"] + sourceSets.main.get().output
    mainClass.set("net.sghill.jenkins.toolkit.CategorizeFailures")
    systemProperty("inputDir", fetchedDir.get())
}
