package com.jason.demo.demo2.framework.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DelayFormatValidator implements ConstraintValidator<DelayFormat, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return DelayFormats.tryParse(value).isPresent();
    }
}
