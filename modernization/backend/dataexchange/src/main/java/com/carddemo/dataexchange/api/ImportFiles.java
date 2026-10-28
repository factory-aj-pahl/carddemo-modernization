package com.carddemo.dataexchange.api;

import java.nio.file.Path;

/** CBIMPORT outputs: DDs CUSTOUT, ACCTOUT, XREFOUT, TRNXOUT, CARDOUT (Q-CBIMPORT-01), and ERROUT. */
public record ImportFiles(Path customers, Path accounts, Path xrefs, Path transactions, Path cards, Path errors) {
}
