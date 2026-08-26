package com.example.basilience;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * The Terms and Conditions and Privacy Policy text, in one place.
 *
 * The Terms previously lived only as a hard-coded android:text in
 * settings_terms.xml, which made them readable only after login. Registration
 * now has to show the same document before an account exists, so the text
 * moved here and both surfaces render it: {@link ToSFragment} for the
 * post-login Settings screen, and a read-only dialog on the registration
 * screen. There is still exactly one copy of the wording.
 *
 * Follows the same content-class pattern already used by
 * {@code MobileGuideContent} and {@code HardwareGuideContent}: text only, no
 * UI wiring beyond the shared read-only presenter at the bottom.
 */
public final class LegalContent {

    public static final String TERMS_TITLE = "Terms and Conditions";
    public static final String PRIVACY_TITLE = "Privacy Policy";

    /**
     * Expanded from the original five-clause version, which covered too
     * little of what the app actually does. Every section below maps to
     * real, shipped behaviour - device claiming, growth cycles, automated
     * equipment, sensor readings, reports - and adds nothing the app does
     * not do.
     */
    public static final String TERMS_BODY =
            "1. Acceptance of Terms\n"
                    + "By creating an account and using Basilience, you agree to be bound by these Terms and "
                    + "Conditions. If you do not agree, please do not use the app.\n\n"

                    + "2. Purpose of Basilience\n"
                    + "Basilience is a farm management application that supports Genovese basil cultivation "
                    + "using fogponics. It is provided to help you monitor growing conditions, manage "
                    + "connected cultivation equipment, and keep records of your growth cycles and "
                    + "harvests. It is an academic/farm management tool, not a financial or commercial "
                    + "trading platform.\n\n"

                    + "3. Account Responsibilities\n"
                    + "You are responsible for the accuracy of the information you provide when creating "
                    + "your account, and for keeping your login credentials confidential. An Admin is "
                    + "responsible for the personnel they assign access to their devices, and for the "
                    + "actions those personnel take within their assigned access.\n\n"

                    + "4. Authorized Use\n"
                    + "You agree to use Basilience only for lawful purposes connected to operating the farm "
                    + "you have access to, and in a way that does not infringe the rights of others or "
                    + "interfere with the normal operation of the service.\n\n"

                    + "5. Device and Connectivity\n"
                    + "Basilience is designed to work with a physical cultivation controller connected to "
                    + "your account. Some app features require an internet connection to load or update. "
                    + "If your device or phone temporarily loses connectivity, information shown in the app "
                    + "may become stale until the connection is restored.\n\n"

                    + "6. Automated Cultivation\n"
                    + "While a growth cycle is active, Basilience can automate connected cultivation "
                    + "equipment based on your configured settings. When no growth cycle is active, normal "
                    + "cultivation automation is paused; monitoring and safety functions remain available. "
                    + "Automated actions depend on your device's configured settings and the hardware "
                    + "actually connected to it.\n\n"

                    + "7. Sensor and Report Limitations\n"
                    + "Sensor readings, alerts, and reports reflect the data recorded by your device at the "
                    + "time it was recorded. Readings can be affected by sensor calibration, hardware "
                    + "condition, or connectivity, and may not always represent current real-world "
                    + "conditions. Reports and historical data are generated from this recorded data and "
                    + "carry the same limitations.\n\n"

                    + "8. User Actions and Farm Decisions\n"
                    + "Basilience is a monitoring and support tool. You remain responsible for the normal "
                    + "physical observation and maintenance of your farm, and for the cultivation decisions "
                    + "you make, whether or not they are informed by data shown in the app.\n\n"

                    + "9. Data and Privacy\n"
                    + "Your use of Basilience is also governed by our Privacy Policy, which is incorporated "
                    + "into these Terms by reference and explains what information we collect and why.\n\n"

                    + "10. Service Availability\n"
                    + "We aim to keep Basilience's app and cloud features available, but do not guarantee "
                    + "uninterrupted access. Maintenance, connectivity issues, or circumstances outside our "
                    + "control may make some features temporarily unavailable.\n\n"

                    + "11. Changes to the Application / Terms\n"
                    + "Basilience may be updated over time, and these Terms may change to reflect those "
                    + "updates. If these Terms change, the updated version will be made available in the "
                    + "app.\n\n"

                    + "12. Limitation of Liability\n"
                    + "Basilience is provided 'as is', on an academic/farm-support basis. To the extent "
                    + "permitted by law, we are not liable for damages arising from reliance on sensor "
                    + "readings, automated actions, or reports, or from the use or inability to use the "
                    + "service.";

    /**
     * Describes only what the application actually processes today. Every
     * category below maps to a real field or collection in this codebase:
     * the user profile document (full name, email, phone, role), device
     * claiming and personnel assignment, sensor readings and parameter logs,
     * growth cycles and harvest records, generated reports, and notification
     * records.
     *
     * Deliberately makes no commitment the project does not implement: no
     * retention period, no third-party sharing arrangement, no data-portability
     * mechanism, and no promise of a response time.
     */
    public static final String PRIVACY_BODY =
            "Basilience is committed to protecting your personal information in accordance with "
                    + "Republic Act No. 10173, the Data Privacy Act of 2012.\n\n"

                    + "1. Information We Collect\n"
                    + "Account and profile information: your full name, email address, mobile number, "
                    + "and your role within the farm (Admin or Farmer).\n"
                    + "Device association: which Basilience devices your account has claimed, and which "
                    + "personnel have been assigned access to them.\n"
                    + "System and sensor data: readings recorded by your device, including pH, EC, air "
                    + "temperature, humidity, water temperature and water level, along with fogging and "
                    + "other system activity logs.\n"
                    + "Cultivation records: growth cycles and harvest entries you record, including "
                    + "weights, dates and notes.\n"
                    + "Reports: the parameter and harvest reports generated from the data above.\n"
                    + "Notifications: alerts raised by your device and whether you have read them.\n\n"

                    + "2. Why We Collect It\n"
                    + "Your account information identifies you, controls what you can access, and lets an "
                    + "Admin manage the personnel working on their farm. Sensor, cycle and harvest data "
                    + "exist to operate the growing system, show you the state of your farm, generate your "
                    + "reports, and raise notifications when a reading needs your attention.\n\n"

                    + "3. How We Use It\n"
                    + "Your data is used to provide the Basilience service to you and the farm you belong "
                    + "to. We do not sell your personal information, and we do not use it for advertising.\n\n"

                    + "4. Access and Protection\n"
                    + "Your data is stored in Basilience's Firebase project and is accessed through your "
                    + "authenticated account. Access is restricted by role: an Admin can see the personnel "
                    + "and devices belonging to their own account, and personnel can see only the devices "
                    + "they have been assigned. Passwords are handled by Firebase Authentication and are "
                    + "never stored or visible in the app.\n\n"

                    + "5. Your Rights\n"
                    + "Under the Data Privacy Act of 2012, you have the right to be informed about, and to "
                    + "access and correct, the personal information held about you. You can view and update "
                    + "your own profile details from the Account screen in the app. An Admin can update or "
                    + "unlink the personnel records they created. For any other request concerning your "
                    + "personal information, please contact your farm's Basilience Admin.\n\n"

                    + "6. Changes to This Policy\n"
                    + "If this policy changes, the updated version will be made available in the app.";

    private LegalContent() {}

    /**
     * Shows a legal document as a read-only, scrollable dialog.
     *
     * Used by the registration screen so the Terms and Privacy Policy can be
     * read before an account exists - reading them must never require signing
     * in first. Unlike the post-login Settings screens (ToSFragment /
     * PrivacyPolicyFragment), which have their own header Back control, this
     * dialog's body is plain read-only text with no button of its own -
     * "Back" here is the shared dialog shell's existing tertiary dismiss
     * button (see NotificationHelper.showCustomViewDialog), not a new control.
     */
    public static void showReadOnly(Context context, String title, String body) {
        if (context == null) return;
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_legal_text, null);
        TextView tvBody = content.findViewById(R.id.tvLegalBody);
        if (tvBody != null) tvBody.setText(body);
        NotificationHelper.showCustomViewDialog(context, title, content, "Back");
    }
}
