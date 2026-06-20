plugins {
    id("com.refinedmods.refinedarchitect.base")
}

refinedarchitect {
    testing()
    mutationTesting()
    javadoc()
    publishing {
        maven = true
    }
}

base {
    archivesName.set("refinedstorage-autocrafting-api")
}

// Exclude legacy implementation folders from compilation (they're kept for reference)
sourceSets {
    named("main") {
        java {
            exclude("**/old_lp/**", "**/old_lp2/**")
        }
    }
}

dependencies {
    api(libs.apiguardian)
    api(project(":refinedstorage-resource-api"))
    api(project(":refinedstorage-core-api"))
    api(project(":refinedstorage-storage-api"))
    api(project(":refinedstorage-query-parser"))
    implementation(libs.slf4j.api)
    api("org.ojalgo:ojalgo:${property("ojalgoVersion")}")
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testImplementation(libs.assertj)
    testImplementation(libs.mockito)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.slf4j.impl)
}
