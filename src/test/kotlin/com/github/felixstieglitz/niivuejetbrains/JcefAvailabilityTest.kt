package com.github.felixstieglitz.niivuejetbrains

import com.intellij.ui.jcef.JBCefApp
import org.junit.Assert.assertEquals
import org.junit.Test

class JcefAvailabilityTest {
    @Test
    fun jbCefAppIsOnClasspath() {
        assertEquals("com.intellij.ui.jcef.JBCefApp", JBCefApp::class.java.name)
    }
}
