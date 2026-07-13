import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Wired to the secrets the Release workflow passes as environment
    // variables (see .github/workflows/release.yml). All are lazy providers,
    // so local builds without these variables are unaffected.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        // By default the verifier only fails the build on hard
        // incompatibilities; deprecated/internal/experimental API findings are
        // logged into a report artifact nobody opens while CI stays green.
        // Escalate them to build failures so they surface as a red check and
        // get fixed before a release. (Not included: NOT_DYNAMIC — dynamic
        // reload is nice to have, not a promise — and COMPATIBILITY_WARNINGS,
        // the verifier's own informational noise.)
        failureLevel = listOf(
            FailureLevel.INVALID_PLUGIN,
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.MISSING_DEPENDENCIES,
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
            FailureLevel.NON_EXTENDABLE_API_USAGES,
            FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
        )
    }
}
