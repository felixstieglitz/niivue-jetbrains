import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
        intellijIdea("2026.2")
        // Since 262 the JCEF API is no longer part of the platform core but of
        // the bundled "Web Browser (JCEF)" plugin. These two modules carry the
        // classes the viewer uses -- com.intellij.ui.jcef.* and org.cef.* --
        // and mirror the <dependencies> block in plugin.xml; without them the
        // editor sources do not compile.
        bundledModule("intellij.platform.ui.jcef")
        bundledModule("intellij.libraries.jcef")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Keep the Marketplace compatibility floor stable when the
            // development platform is upgraded to a newer IDE release.
            sinceBuild = "262"
            // Do not reject future IDE releases solely because of an
            // artificial upper bound; the verifier checks API compatibility.
            untilBuild = provider { null }
        }

        // Marketplace change notes, rendered from CHANGELOG.md. During
        // development the [Unreleased] section is used; in the Release
        // workflow patchChangelog runs before publishPlugin, so the freshly
        // stamped [x.y.z] section exists by the time this provider is read.
        changeNotes = provider {
            val version = project.version.toString()
            with(changelog) {
                renderItem(
                    (getOrNull(version) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

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
        // Check both the minimum supported release and the newest available
        // IntelliJ IDEA/PyCharm releases. This keeps API regressions visible
        // without imposing an until-build that would reject a new IDE upfront.
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.PyCharm, "2026.2")
            latest {
                types = listOf(
                    IntelliJPlatformType.IntellijIdea,
                    IntelliJPlatformType.PyCharm,
                )
            }
        }

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
