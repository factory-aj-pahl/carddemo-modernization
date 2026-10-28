package com.carddemo.refdata.web;

/** Session keys shared by the two transaction-type screens. */
final class TranTypeNavigation {

    /** Program COTRTUPC returns to with PF3: set by COTRTLIC on PF2, otherwise the admin menu (Q-COTRTUPC-05). */
    static final String CALLER = "carddemo.COTRTUPC.caller";
    static final String LIST_STATE = "carddemo.COTRTLIC.state";
    static final String MAINT_STATE = "carddemo.COTRTUPC.state";
    static final String ADMIN_MENU = "COADM01C";

    private TranTypeNavigation() {
    }
}
