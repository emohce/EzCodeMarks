import org.jetbrains.intellij.platform.gradle.Constants.Tasks
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "emohce"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledModule("intellij.platform.vcs.impl")
        // 2025.3 ships this base module outside the bundled-module index exposed by Gradle 2.16.
        bundledLibrary("lib/module-intellij.libraries.velocity.jar")
        bundledPlugin("Git4Idea")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Required by Gradle Test Executor to run JUnit Platform tests in this IntelliJ Platform setup.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
}

// IDEA 2025.3 provides a JetBrains-patched coroutines runtime in util-8.jar.
// External test libraries may otherwise shadow it with an ABI-incompatible Maven artifact.
configurations.named("testRuntimeClasspath") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

intellijPlatform {
    autoReload = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    withType<Test> {
        useJUnitPlatform()
    }

    named("buildSearchableOptions") {
        enabled = false
    }

    // The 2.16 Ant instrumenter uses shared task definitions; serialize main/test instrumentation.
    named("instrumentTestCode") {
        mustRunAfter("instrumentCode")
    }

    named<PrepareSandboxTask>(Tasks.PREPARE_SANDBOX) {
        disabledPlugins.addAll(
            "AngularJS",
            "JBoss",
            "JSIntentionPowerPack",
            "JavaScript",
            "Karma",
            "NodeJS",
            "Refactor-X",
            "Tomcat",
            "com.deadlock.scsyntax",
            "com.intellij.LineProfiler",
            "com.intellij.aop",
            "com.intellij.beanValidation",
            "com.intellij.cdi",
            "com.intellij.cron",
            "com.intellij.css",
            "com.intellij.flyway",
            "com.intellij.freemarker",
            "com.intellij.hibernate",
            "com.intellij.javaee.app.servers.integration",
            "com.intellij.javaee.extensions",
            "com.intellij.javaee.jakarta.data",
            "com.intellij.javaee.jpa",
            "com.intellij.javaee.reverseEngineering",
            "com.intellij.javaee.web",
            "com.intellij.jsp",
            "com.intellij.kubernetes",
            "com.intellij.liquibase",
            "com.intellij.micronaut",
            "com.intellij.persistence",
            "com.intellij.plugins.webcomponents",
            "com.intellij.quarkus",
            "com.intellij.react",
            "com.intellij.spring.boot",
            "com.intellij.spring.cloud",
            "com.intellij.spring.data",
            "com.intellij.spring.integration",
            "com.intellij.spring.messaging",
            "com.intellij.spring.modulith",
            "com.intellij.spring.mvc",
            "com.intellij.spring.security",
            "com.intellij.stylelint",
            "com.intellij.tailwindcss",
            "com.intellij.tasks.timeTracking",
            "com.intellij.velocity",
            "com.jetbrains.plugins.webDeployment",
            "com.jetbrains.restWebServices",
            "org.jetbrains.plugins.docker.gateway",
            "org.jetbrains.plugins.javaFX",
            "org.jetbrains.plugins.less",
            "org.jetbrains.plugins.node-remote-interpreter",
            "org.jetbrains.plugins.sass",
            "org.jetbrains.plugins.vue",
            "com.jetbrains.gateway",
            "com.jetbrains.remoteDevServer",
            "intellij.indexing.shared",
            "intellij.nextjs",
            "intellij.prettierJS",
            "intellij.vitejs",
            "intellij.webpack",
            "org.intellij.plugins.postcss",
            "org.jetbrains.plugins.remote-run",
            "tslint",
        )
    }

    named<RunIdeTask>("runIde") {
        coroutinesJavaAgentFile.set(layout.projectDirectory.file(".intellijPlatform/disabled-coroutines-javaagent.jar"))
        systemProperties["idea.suppress.frequent.exception.logging"] = "true"
        systemProperties["kotlinx.coroutines.debug"] = "off"
        jvmArgumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
            listOf(
                "-Didea.suppress.frequent.exception.logging=true",
                "-Dkotlinx.coroutines.debug=off"
            )
        })
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
    jvmToolchain(21)
}
