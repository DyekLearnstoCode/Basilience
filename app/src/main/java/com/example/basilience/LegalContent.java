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
     * Unchanged from the wording that already shipped on the Settings Terms
     * screen - moved, not rewritten.
     */
    public static final String TERMS_BODY =
            "1. Acceptance of Terms\n"
                    + "By accessing and using Basilience, you agree to be bound by these Terms and Conditions.\n\n"
                    + "2. Use of Service\n"
                    + "You agree to use the service only for lawful purposes and in a way that does not infringe the rights of others.\n\n"
                    + "3. Privacy Policy\n"
                    + "Your use of the service is also governed by our Privacy Policy, which is incorporated into these terms by reference.\n\n"
                    + "4. Data Collection\n"
                    + "We collect sensor data and user information to provide and improve our services. We do not sell your personal data to third parties.\n\n"
                    + "5. Limitation of Liability\n"
                    + "Basilience is provided 'as is'. We are not liable for any damages arising from the use or inability to use the service.";

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
     * in first.
     */
    public static void showReadOnly(Context context, String title, String body) {
        if (context == null) return;
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_legal_text, null);
        TextView tvBody = content.findViewById(R.id.tvLegalBody);
        if (tvBody != null) tvBody.setText(body);
        NotificationHelper.showCustomViewDialog(context, title, content);
    }
}
