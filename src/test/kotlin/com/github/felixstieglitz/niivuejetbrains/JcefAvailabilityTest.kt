package com.github.felixstieglitz.niivuejetbrains

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class JcefAvailabilityTest : BasePlatformTestCase() {
    fun testJcefDependenciesAreResolvedByPluginClassLoader() {
        val descriptor = PluginManagerCore.getPlugin(
            PluginId.getId("com.github.felixstieglitz.niivuejetbrains")
        )
        assertNotNull("Niivue plugin descriptor is not loaded", descriptor)

        val moduleDependencies = (descriptor as IdeaPluginDescriptorImpl).moduleDependencies
        val moduleNames = moduleDependencies.modules.map { it.name }.toSet()
        val pluginIds = moduleDependencies.plugins.map { it.idString }.toSet()
        assertTrue(
            "JCEF plugin dependency is missing from the parsed plugin descriptor",
            "com.intellij.modules.jcef" in pluginIds,
        )
        assertTrue(
            "JCEF library module dependency is missing from the parsed plugin descriptor",
            "intellij.libraries.jcef" in moduleNames,
        )
        assertTrue(
            "JCEF UI module dependency is missing from the parsed plugin descriptor",
            "intellij.platform.ui.jcef" in moduleNames,
        )

        val pluginClassLoader = requireNotNull(descriptor.pluginClassLoader) {
            "Niivue plugin classloader is not available"
        }
        val jbCefApp = pluginClassLoader.loadClass(
            "com.intellij.ui.jcef.JBCefApp"
        )
        assertEquals("com.intellij.ui.jcef.JBCefApp", jbCefApp.name)
    }
}
