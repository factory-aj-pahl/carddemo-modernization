package com.carddemo.card.api;

import java.util.List;

/** Sequential browse of the card cross-reference in card-number order (XREFFILE read sequentially). */
public interface CardXrefListing {

    List<CardXref> allByCardNumber();
}
