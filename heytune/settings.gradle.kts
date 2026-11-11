@file:Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "HeyTune"
include(":app")
include(":handsfree")
include(":material-color-utilities")

// Use a local copy of ffMetadataEx by uncommenting the lines below.
// From:
//      add("fullImplementation", files("../prebuilt/ffMetadataEx-release.aar"))
// To:
//       add("fullImplementation", "wah.mikooomich:ffmetadataex")

//includeBuild("../ffMetadataEx") {
//    dependencySubstitution {
//        substitute(module("wah.mikooomich:ffmetadataex")).using(project(":ffMetadataEx"))
//    }
//}
