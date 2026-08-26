package com.jason.demo.demo2.framework.web.exception;

import com.jason.demo.demo2.framework.web.result.JsonResult;
import com.jason.demo.demo2.framework.web.result.JsonResults;
import jakarta.validation.ConstraintViolation;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ValidationExceptionMapper {

    private ValidationExceptionMapper() {
    }

    public static JsonResult<Void> fromBindingResult(BindingResult bindingResult) {
        List<String> messages = new ArrayList<>();
        boolean missing = false;
        for (ObjectError error : bindingResult.getAllErrors()) {
            if (isMissingConstraint(error.getCodes())) {
                missing = true;
            }
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            String defaultMessage = error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
            messages.add(field + ": " + defaultMessage);
        }
        return fail(missing, messages);
    }

    public static JsonResult<Void> fromConstraintViolations(Set<? extends ConstraintViolation<?>> violations) {
        List<String> messages = new ArrayList<>();
        boolean missing = false;
        for (ConstraintViolation<?> v : violations) {
            String annotation = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            if (isMissingAnnotationName(annotation)) {
                missing = true;
            }
            String path = v.getPropertyPath() == null ? "param" : v.getPropertyPath().toString();
            messages.add(path + ": " + v.getMessage());
        }
        return fail(missing, messages);
    }

    private static JsonResult<Void> fail(boolean missing, List<String> messages) {
        int code = missing
                ? CommonErrorCodeEnum.PARAM_MISSING.getCode()
                : CommonErrorCodeEnum.BAD_REQUEST.getCode();
        String message = messages.isEmpty()
                ? (missing ? CommonErrorCodeEnum.PARAM_MISSING.getDesc() : CommonErrorCodeEnum.BAD_REQUEST.getDesc())
                : String.join("; ", messages);
        return JsonResults.fail(code, message);
    }

    private static boolean isMissingConstraint(String[] codes) {
        if (codes == null) {
            return false;
        }
        for (String code : codes) {
            if (code == null) {
                continue;
            }
            if (code.equals("NotNull") || code.equals("NotBlank") || code.equals("NotEmpty")
                    || code.startsWith("NotNull.") || code.startsWith("NotBlank.") || code.startsWith("NotEmpty.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMissingAnnotationName(String simpleName) {
        return "NotNull".equals(simpleName) || "NotBlank".equals(simpleName) || "NotEmpty".equals(simpleName);
    }
}
