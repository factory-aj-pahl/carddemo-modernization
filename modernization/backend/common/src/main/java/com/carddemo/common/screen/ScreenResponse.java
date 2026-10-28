package com.carddemo.common.screen;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Final state of one screen turn (C-RENDER): the fields the user sees, the message line, the field
 * that gets focus (the 3270 cursor), and, when the turn transfers control (XCTL), the target program.
 *
 * @param fields       screen-specific output fields
 * @param message      message line text, verbatim (may carry trailing spaces); null when empty
 * @param messageColor color of the message line
 * @param focus        name of the field that receives focus, or null
 * @param navigate     program the client must show next (for example {@code COMEN01C}), or null to stay
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScreenResponse<F>(F fields, String message, MessageColor messageColor, String focus, String navigate) {

    public static <F> ScreenResponse<F> show(F fields) {
        return new ScreenResponse<>(fields, null, null, null, null);
    }

    public static <F> ScreenResponse<F> navigate(String program) {
        return new ScreenResponse<>(null, null, null, null, program);
    }

    public static <F> ScreenResponse<F> navigate(String program, String message, MessageColor color) {
        return new ScreenResponse<>(null, message, color, null, program);
    }

    public ScreenResponse<F> withMessage(String text, MessageColor color) {
        return new ScreenResponse<>(fields, text, color, focus, navigate);
    }

    public ScreenResponse<F> error(String text) {
        return withMessage(text, MessageColor.RED);
    }

    public ScreenResponse<F> withFocus(String field) {
        return new ScreenResponse<>(fields, message, messageColor, field, navigate);
    }

    public ScreenResponse<F> withFields(F newFields) {
        return new ScreenResponse<>(newFields, message, messageColor, focus, navigate);
    }
}
