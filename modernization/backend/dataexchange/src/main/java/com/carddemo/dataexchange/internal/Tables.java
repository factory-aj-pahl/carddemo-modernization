package com.carddemo.dataexchange.internal;

import com.carddemo.migration.Layout;

/** Opens a master table as its legacy KSDS: records in the legacy layout, in key order. */
interface Tables {

    RecordSource of(Layout layout);
}
