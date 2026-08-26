package com.jason.demo.demo2.framework.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayFormatValidatorTest {

    private DelayFormatValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DelayFormatValidator();
    }

    @Test
    void nullOrBlank_isValid() {
        assertTrue(validator.isValid(null, null));
        assertTrue(validator.isValid("", null));
        assertTrue(validator.isValid("  ", null));
    }

    @Test
    void validForms_ok() {
        assertTrue(validator.isValid("30s", null));
        assertTrue(validator.isValid("PT30S", null));
    }

    @Test
    void invalid_fails() {
        assertFalse(validator.isValid("nope", null));
    }
}
