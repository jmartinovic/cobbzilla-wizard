package org.cobbzilla.wizard.validation;

import org.cobbzilla.util.time.ImprovedTimezone;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class TimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    @Override public void initialize(ValidTimezone constraintAnnotation) {}

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ImprovedTimezone.getTimeZoneByGmtOffset(value) != null;
    }
}
