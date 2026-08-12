package com.github.felixstieglitz.niivuejetbrains

import org.junit.Assert.assertNotNull
import org.junit.Test

class WebviewResourcesTest {
    @Test
    fun requiredWebviewResourcesArePackaged() {
        val resources = listOf(
            "index.html",
            "viewer.js",
            "dicom-worker.js",
            "niivue.umd.js",
            "dcm2niix.js",
            "dcm2niix.wasm",
        )

        resources.forEach { name ->
            assertNotNull(
                "Missing packaged webview resource: $name",
                javaClass.getResource("/webview/$name"),
            )
        }
    }
}
