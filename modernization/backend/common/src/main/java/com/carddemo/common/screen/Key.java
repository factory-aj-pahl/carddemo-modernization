package com.carddemo.common.screen;

/**
 * Attention identifier (AID) of a screen turn. The web app maps keyboard shortcuts to these values;
 * the server decides what each key means, including the "invalid key" outcome.
 */
public enum Key {
    ENTER, CLEAR, PA1, PA2, PA3,
    PF1, PF2, PF3, PF4, PF5, PF6, PF7, PF8, PF9, PF10, PF11, PF12,
    PF13, PF14, PF15, PF16, PF17, PF18, PF19, PF20, PF21, PF22, PF23, PF24;

    /** CSSTRPFY folding used by some programs: PF13-PF24 behave as PF1-PF12. */
    public Key folded() {
        int n = ordinal() - PF13.ordinal();
        return n >= 0 ? values()[PF1.ordinal() + n] : this;
    }
}
