package com.hermes.client.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageStagingTest {
    @Test
    fun filename_is_sanitized_without_paths() {
        assertEquals("cat.png", sanitizedImageFilename("../weird/dir/cat.png"))
        assertEquals("cat.png", sanitizedImageFilename("..\\dir\\cat.png"))
        // A name that is only the extension after the cut falls back...
        assertEquals(FALLBACK_FILENAME, sanitizedImageFilename("  a:?b/*#.png "))
        assertEquals(FALLBACK_FILENAME, sanitizedImageFilename("..\\dir\\.png"))
        // ...punctuation is dropped, inner spaces (from real names) survive.
        assertEquals("ab.png", sanitizedImageFilename(" a:!b?.png "))
        assertEquals("a b.png", sanitizedImageFilename("a b!.png "))
    }

    @Test
    fun blank_or_odd_names_fall_back() {
        assertEquals(FALLBACK_FILENAME, sanitizedImageFilename(null))
        assertEquals(FALLBACK_FILENAME, sanitizedImageFilename("   "))
        assertEquals(FALLBACK_FILENAME, sanitizedImageFilename("."))
        assertEquals(FALLBACK_FILENAME, sanitizedImageFilename("/"))
    }

    @Test
    fun names_are_capped() {
        assertEquals(FILENAME_MAX_LENGTH, sanitizedImageFilename("x".repeat(FILENAME_MAX_LENGTH + 10)).length)
    }

    @Test
    fun resolver_mimetype_wins_when_it_is_an_image() {
        assertEquals("image/webp", imageMimetype("photo.bin", "image/webp"))
        assertEquals("image/jpeg", imageMimetype("photo.jpg", "image/jpeg"))
    }

    @Test
    fun unknown_resolver_mimetype_falls_back_to_the_extension() {
        assertEquals("image/png", imageMimetype("shot.png", "application/octet-stream"))
        assertNull(imageMimetype("shot.xyz", null))
    }

    @Test
    fun known_image_extensions_are_mapped() {
        assertEquals(".jpg", imageExtensionFor("image/jpeg"))
        assertEquals(".avif", imageExtensionFor("image/avif"))
        assertNull(imageExtensionFor("application/pdf"))
        assertNull(imageExtensionFor(null))
    }

    @Test
    fun filename_extends_a_stub_with_the_right_type() {
        assertEquals("IMG_1.jpg", filenameFor("IMG_1", "image/jpeg"))
        assertEquals("IMG_1.png", filenameFor("IMG_1.jpg", "image/png"))
        assertEquals("IMG_1.jpg", filenameFor("IMG_1.jpg", "image/jpeg"))
        assertEquals("IMG_1.bin", filenameFor("IMG_1.bin", null))
        assertEquals(FALLBACK_FILENAME, filenameFor("   ", "image/jpeg"))
    }
}
