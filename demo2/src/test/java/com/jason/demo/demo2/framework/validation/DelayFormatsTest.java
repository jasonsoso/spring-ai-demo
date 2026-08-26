package com.jason.demo.demo2.framework.validation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayFormatsTest {

    @Test
    void tryParse_nullOrBlank_returnsEmpty() {
        assertTrue(DelayFormats.tryParse(null).isEmpty());
        assertTrue(DelayFormats.tryParse("").isEmpty());
        assertTrue(DelayFormats.tryParse("  ").isEmpty());
    }

    @Test
    void tryParse_suffixForms_ok() {
        assertEquals(Optional.of(Duration.ofSeconds(30)), DelayFormats.tryParse("30s"));
        assertEquals(Optional.of(Duration.ofMillis(500)), DelayFormats.tryParse("500ms"));
        assertEquals(Optional.of(Duration.ofMinutes(2)), DelayFormats.tryParse("2m"));
        assertEquals(Optional.of(Duration.ofHours(1)), DelayFormats.tryParse("1h"));
    }

    @Test
    void tryParse_isoDuration_ok() {
        assertEquals(Optional.of(Duration.parse("PT30S")), DelayFormats.tryParse("PT30S"));
        assertEquals(Optional.of(Duration.parse("PT30S")), DelayFormats.tryParse("pt30s"));
    }

    @Test
    void tryParse_invalid_returnsEmpty() {
        assertTrue(DelayFormats.tryParse("abc").isEmpty());
        assertTrue(DelayFormats.tryParse("30x").isEmpty());
    }
}
