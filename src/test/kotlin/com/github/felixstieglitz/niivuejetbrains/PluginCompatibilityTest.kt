package com.github.felixstieglitz.niivuejetbrains

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginCompatibilityTest : BasePlatformTestCase() {
    fun testCompatibilityRangeStaysOpenEndedFromBuild262() {
        val descriptor = requireNotNull(
            PluginManagerCore.getPlugin(
                PluginId.getId("com.github.felixstieglitz.niivuejetbrains")
            )
        ) {
            "Niivue plugin descriptor is not loaded"
        }

        assertEquals("262", descriptor.sinceBuild)
        assertNull(
            "An until-build would make the plugin incompatible with a future IDE update",
            descriptor.untilBuild,
        )
    }
}
