import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.SHORT
    }
    // One "FAILED <TestClass> > <test name>" line per failure so general-verify can map
    // failures back to contract cases.
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (result.resultType == TestResult.ResultType.FAILURE) {
            println("FAILED ${desc.className?.substringAfterLast('.')} > ${desc.displayName}")
        }
    }))
}
