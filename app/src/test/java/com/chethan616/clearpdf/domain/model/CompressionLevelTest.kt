package com.chethan616.clearpdf.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CompressionLevelTest {
    @Test fun fromFraction_usesSliderThresholds() {
        assertEquals(CompressionLevel.LOW, CompressionLevel.fromFraction(0f))
        assertEquals(CompressionLevel.LOW, CompressionLevel.fromFraction(0.329f))
        assertEquals(CompressionLevel.MEDIUM, CompressionLevel.fromFraction(0.33f))
        assertEquals(CompressionLevel.MEDIUM, CompressionLevel.fromFraction(0.659f))
        assertEquals(CompressionLevel.HIGH, CompressionLevel.fromFraction(0.66f))
        assertEquals(CompressionLevel.HIGH, CompressionLevel.fromFraction(1f))
    }

    @Test fun fraction_roundTripsToSameLevel() {
        CompressionLevel.entries.forEach { assertEquals(it, CompressionLevel.fromFraction(it.fraction)) }
    }
}
