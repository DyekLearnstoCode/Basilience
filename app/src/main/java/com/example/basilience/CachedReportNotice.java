package com.example.basilience;

import android.view.View;
import android.widget.TextView;

/**
 * The single wording and behaviour for a report whose data came only from the
 * local cache (the device was offline when it loaded), shared by Parameter
 * Reports and Fogging Reports. Such a report still renders, but it may not
 * cover the whole selected range, so it must not be exported as a formal one.
 */
final class CachedReportNotice {

    static final String NOTICE = "Offline — showing saved data. This report may be incomplete.";
    static final String EXPORT_BLOCKED = "Reconnect to export a complete report.";

    /** The export button stays tappable (so it can explain itself) but looks unavailable. */
    private static final float BLOCKED_EXPORT_ALPHA = 0.5f;

    private CachedReportNotice() {}

    static void apply(TextView notice, View exportButton, boolean fromCache) {
        if (notice != null) {
            notice.setText(NOTICE);
            notice.setVisibility(fromCache ? View.VISIBLE : View.GONE);
        }
        if (exportButton != null) {
            exportButton.setAlpha(fromCache ? BLOCKED_EXPORT_ALPHA : 1f);
        }
    }
}
