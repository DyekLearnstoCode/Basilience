package com.example.basilience;

import android.view.View;
import android.widget.TextView;

/**
 * The single wording and behaviour for a report whose data came only from the
 * local cache (the device was offline when it loaded), shared by Parameter
 * Reports and Fogging Reports. Such a report still renders and can still be
 * exported, but it may not cover the whole selected range, so both the screen
 * and every exported file say so.
 */
final class CachedReportNotice {

    static final String NOTICE = "Offline — showing saved data. This report may be incomplete.";
    static final String EXPORT_WARNING =
            "Offline cached report — generated from locally saved data. Some records may be unavailable.";
    static final String DATA_SOURCE_OFFLINE = "Offline cache";

    private CachedReportNotice() {}

    static void apply(TextView notice, boolean fromCache) {
        if (notice == null) return;
        notice.setText(NOTICE);
        notice.setVisibility(fromCache ? View.VISIBLE : View.GONE);
    }
}
