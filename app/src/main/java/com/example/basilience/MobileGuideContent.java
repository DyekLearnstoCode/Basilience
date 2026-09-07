package com.example.basilience;

import com.example.basilience.models.GuideSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Content for the Mobile App Guide. Kept separate from the Fragment so the
 * (large) instructional text is easy to review/edit without touching any
 * UI-wiring code, and so the Fragment stays a thin binder between this data
 * and {@link GuideSectionAdapter}.
 *
 * <p>Every section here was written against the actual current app behavior
 * (Dashboard_Fragment, Parameters_Monitoring_Fragment, Cycle_Details_Fragment,
 * HarvestLogFragment, SystemReportsFragment, FoggingReportsFragment,
 * NotificationAdapter, Personnel_*_Fragment, DeviceFragment, SettingsFragment,
 * AccountFragment, WifiConfigFragment) rather than assumed or copied from
 * older documentation. Image resources are not yet available for any
 * section, so every {@code imagePlaceholder(...)} caption is present and no
 * {@code image(...)} call is made; dropping in a real screenshot later is a
 * one-line change per section (see GuideSection.Builder#image).
 */
final class MobileGuideContent {

    private MobileGuideContent() {}

    private static final String ADMIN_ONLY = "Admin Only";

    static List<GuideSection> sections() {
        List<GuideSection> list = new ArrayList<>();

        list.add(GuideSection.builder("Getting Started")
                .description("Basilience is organized around three bottom tabs, plus Settings in the top-right corner of every screen.")
                .image(com.example.basilience.R.drawable.guide_dashboard)
                .imagePlaceholder("Dashboard screen with the bottom navigation bar visible")
                .steps(Arrays.asList(
                        "Home takes you to the Dashboard for your currently selected device.",
                        "Reports (Admin accounts only) opens the report selector.",
                        "Notification opens your notification history.",
                        "The gear icon in the top-right corner opens Settings from anywhere in the app.",
                        "Tapping Home from any screen always returns you to the Dashboard."))
                .build());

        list.add(GuideSection.builder("Dashboard")
                .description("The Dashboard is what you see right after selecting a device. It's the starting point for everything else.")
                .image(com.example.basilience.R.drawable.guide_dashboard)
                .imagePlaceholder("Dashboard screen showing the device status line and the three action cards")
                .steps(Arrays.asList(
                        "A status line under the title shows the currently selected device's connection state.",
                        "● ONLINE means the device is actively reporting to Basilience.",
                        "● RECONNECTING... means Basilience is waiting to hear from the device again.",
                        "● DEVICE UNREACHABLE means Basilience cannot currently communicate with the device.",
                        "Below the status line, three cards open Parameters Monitoring, User Guide, and Cycle Details."))
                .build());

        list.add(GuideSection.builder("Parameter Monitoring")
                .description("Monitoring shows the live sensor readings for the selected device.")
                .image(com.example.basilience.R.drawable.guide_monitoring)
                .imagePlaceholder("Monitoring screen with all six parameter cards showing valid readings")
                .steps(Arrays.asList(
                        "From the Dashboard, tap the \"Parameters Monitoring\" card.",
                        "The top grid shows pH, EC, Air Temperature, and Humidity.",
                        "The Details section below shows Water Temperature and Water Level.",
                        "Each card shows the current value with its unit (°C for temperature, % for humidity and water level, mS/cm for EC; pH has no unit).",
                        "Each card also shows a status word: Normal, Below Range, Above Range, or No Data, matching the value's color.",
                        "Every parameter has a target range with a Minimum and a Maximum. The Minimum is the lowest reading still considered inside the target growing range, and the Maximum is the highest. A reading outside either limit is shown as Below Range or Above Range here, and appears in red on the Reports charts."))
                .tip("If a card shows \"--\" and a No Data status, the sensor reading hasn't arrived yet or is currently invalid — this is not the same as a Warning.")
                .build());

        list.add(GuideSection.builder("Actuator & Automation Status")
                .description("Below the sensor cards, the Actuators section shows every pump, fan, light, and the fogger, with a Manual Mode switch at the top.")
                .image(com.example.basilience.R.drawable.guide_actuators)
                .imagePlaceholder("Monitoring screen scrolled down to the actuator list")
                .steps(Arrays.asList(
                        "Manual Mode (the switch at the top of the section) allows you to control individual actuators by hand. The automatic system keeps running underneath even while Manual Mode is on.",
                        "Each actuator row shows its name (for example \"Fogger\" or \"Grow Lights\") and a status word.",
                        "Off, Command Sent, Validating, Starting, Running, and Stopping describe where a command currently is.",
                        "A small · Auto, · Manual, or · App tag after the status shows what triggered it — the automatic system, a physical control, or this app.",
                        "Toggling a switch while Manual Mode is on sends a command directly to that actuator."))
                .warning("Actuator switches are disabled unless Manual Mode is turned on.")
                .build());

        list.add(GuideSection.builder("Growth Cycles")
                .description("A growth cycle represents one planting-to-harvest run for a device.")
                .image(com.example.basilience.R.drawable.guide_growth_cycles)
                .imagePlaceholder("Growth Cycles list with one active and one completed cycle")
                .steps(Arrays.asList(
                        "From the Dashboard, tap the \"Cycle Details\" card.",
                        "Each cycle card shows its cycle number and an ACTIVE or COMPLETED badge.",
                        "Start Date and Next Harvest (or Completed Date, once finished) are shown side by side with Harvests and Total Weight.",
                        "Only one cycle can be ACTIVE at a time for a device.",
                        "Tap any cycle card to open its Harvest screen."))
                .build());

        list.add(GuideSection.builder("Adding a Growth Cycle")
                .description("Admins and assigned Personnel can create a new growth cycle when no active cycle is running. A new cycle can only be started once the previous one is completed.")
                .image(com.example.basilience.R.drawable.guide_add_cycle)
                .imagePlaceholder("Add Cycle screen with Cycle Number, Start Date, and Harvest Frequency filled in")
                .steps(Arrays.asList(
                        "From Growth Cycles, tap \"Add New Cycle.\"",
                        "The Cycle Number is assigned automatically.",
                        "Tap the Start Date field to choose a date from the calendar.",
                        "Enter Harvest Frequency in days — this sets how often the app expects a harvest to be recorded.",
                        "Tap Save. If an active cycle already exists for this device, Basilience will ask you to complete it first."))
                .build());

        list.add(GuideSection.builder("Harvest")
                .description("The Harvest screen for a cycle shows its production summary, schedule, chart, and history.")
                .image(com.example.basilience.R.drawable.guide_harvest)
                .imagePlaceholder("Harvest screen showing the summary card, chart, and history list")
                .steps(Arrays.asList(
                        "Open a cycle from Growth Cycles to reach its Harvest screen.",
                        "The summary card shows Total Harvested weight and the number of Harvest Entries.",
                        "For an active cycle, the schedule shows \"Ready to Harvest\" (in green) once a harvest is due, or \"Next Harvest\" with the upcoming date beforehand.",
                        "For a completed cycle, the schedule shows the Completed Date instead, and no further harvests can be added.",
                        "The Accumulated Harvest chart plots running total weight over the cycle; Harvest History below lists every individual entry, newest first."))
                .build());

        list.add(GuideSection.builder("Recording Harvest Weight")
                .description("Harvest weight can be entered by hand, or read automatically from a paired harvest scale.")
                .image(com.example.basilience.R.drawable.guide_add_harvest)
                .imagePlaceholder("Add Harvest dialog with weight field and today's date shown")
                .steps(Arrays.asList(
                        "On an active cycle's Harvest screen, tap the + button.",
                        "The dialog shows the date this harvest will be recorded under.",
                        "Enter the harvested weight in grams, or tap \"Read from Harvest Scale\" to fill it in from the device's paired scale instead of typing it — this button only appears once a harvest scale has been paired to the device.",
                        "Notes are optional.",
                        "Tap Save. The total, chart, and history update immediately."))
                .warning("If a harvest isn't due yet, tapping + shows how many days remain instead of the entry form. Admin accounts can choose to override this and log the harvest early, which also resets the schedule from that date.")
                .build());

        list.add(GuideSection.builder("Reports")
                .role(ADMIN_ONLY)
                .description("The Reports tab (bottom navigation) opens a selector with two report types.")
                .image(com.example.basilience.R.drawable.guide_reports_selector)
                .imagePlaceholder("Reports selector showing the Parameter Reports and Fogging Reports cards")
                .steps(Arrays.asList(
                        "Parameter Reports covers sensor readings (pH, EC, temperature, humidity, water level) over time.",
                        "Fogging Reports covers fogger activity and water usage.",
                        "Tap either card to open that report."))
                .build());

        list.add(GuideSection.builder("Parameter Report")
                .description("Shows sensor trends for a chosen cycle and parameter.")
                .image(com.example.basilience.R.drawable.guide_parameter_report)
                .imagePlaceholder("Parameter Report with cycle/parameter selectors, chart, and metrics filled in")
                .steps(Arrays.asList(
                        "Choose a Cultivation Cycle from the first selector.",
                        "Choose a Parameter (pH, EC, temperature, humidity, or water level).",
                        "Choose a Period: Entire, Today, 7D, 30D, or Custom.",
                        "The chart, Average/Highest/Lowest metrics, and \"What This Means\" summary update for that selection.",
                        "The dashed Minimum and Maximum lines are the target ranges that were in use when that growth cycle was created, so an older report keeps reading the way it did at the time. Readings outside them are drawn in red.",
                        "Use the share icon at the top to export the report as a PDF."))
                .build());

        list.add(GuideSection.builder("Fogging Report")
                .description("Shows fogging activity and water usage for a chosen cycle.")
                .image(com.example.basilience.R.drawable.guide_fogging_report)
                .imagePlaceholder("Fogging Report with sessions, chart, and Water Outlook visible")
                .steps(Arrays.asList(
                        "Choose a Cultivation Cycle and a Period, the same way as the Parameter Report.",
                        "Sessions, Runtime, and Avg Session summarize fogging activity for that period.",
                        "Fogging Control shows whether the fogger is currently running under Automatic or Manual control.",
                        "Water Outlook estimates the reservoir level and when a refill may be needed — shown only when the app has enough recent data to estimate it.",
                        "Recent Fogging Activity lists individual sessions; use the share icon to export a PDF."))
                .build());

        list.add(GuideSection.builder("Notifications")
                .description("The Notification tab keeps a history of alerts for your devices.")
                .image(com.example.basilience.R.drawable.guide_notifications)
                .imagePlaceholder("Notifications screen with the All/Unread/Read filters and a few entries")
                .steps(Arrays.asList(
                        "Use the All, Unread, and Read chips to filter the list.",
                        "Tapping a notification marks it as read.",
                        "\"Mark all as read\" is available at the top of each month's group when there are unread notifications."))
                .tip("Categories you may see: Parameter Alert, Harvest Ready, Hardware Issue, Device Unreachable, Device Back Online, and Information.")
                .build());

        list.add(GuideSection.builder("Personnel Management")
                .role(ADMIN_ONLY)
                .description("Personnel are Farmer-role accounts linked to your admin account.")
                .image(com.example.basilience.R.drawable.guide_personnel)
                .imagePlaceholder("Personnel list with a couple of farmer accounts")
                .steps(Arrays.asList(
                        "Open Personnel from the management area to see everyone linked to your account.",
                        "\"Create Personnel\" registers a brand-new farmer account with a name, email, phone number, and password.",
                        "\"Add Existing Personnel\" links an existing, unlinked farmer account by email instead of creating a new one.",
                        "Tap a person to view their details, edit their name/phone, reset their password, or remove them."))
                .build());

        list.add(GuideSection.builder("Device Management")
                .role(ADMIN_ONLY)
                .description("Devices are claimed to your account using a token provided with the hardware.")
                .image(com.example.basilience.R.drawable.guide_device_management)
                .imagePlaceholder("Device Management screen with the claim field and a registered device list")
                .steps(Arrays.asList(
                        "Enter the device's token code and tap \"Claim Device\" to add it to your account.",
                        "Registered Devices lists everything claimed to your account, with a live status dot.",
                        "Tap a device to select it — the rest of the app will then work with that device.",
                        "Press and hold a device to choose \"Configure Wi-Fi\" or \"Unclaim Device.\""))
                .build());

        list.add(GuideSection.builder("Settings")
                .description("Reached from the gear icon in the top-right corner of any screen.")
                .image(com.example.basilience.R.drawable.guide_settings)
                .imagePlaceholder("Settings screen listing Account Information, About Basilience, and Terms and Agreements")
                .steps(Arrays.asList(
                        "Account Information — view and edit your profile.",
                        "Device Configuration — Admin Only. Safe, always-available device diagnostics: the device's clock status, a physical sensor test, and refill threshold settings. See the Device Configuration section of this guide.",
                        "About Basilience — general information about the app.",
                        "Terms and Conditions — the app's terms of use.",
                        "Privacy Policy — how your data is handled.",
                        "Developer Options — appears only once your account has Developer Tester access and developer mode has been turned on for the selected device. Testing and simulation tools not needed for normal use — see the Developer Options section of this guide."))
                .build());

        // Device Configuration is a plain Admin-only screen (Settings >
        // Device Configuration) - no Developer Tester entitlement or
        // per-device developer-mode flag required, unlike Developer
        // Options below. Both screens are the same underlying Fragment
        // (DevOptionsFragment) opened in a different mode; this section
        // documents only what that mode actually shows.
        list.add(GuideSection.builder("Device Configuration")
                .role(ADMIN_ONLY)
                .description("Safe, always-available device diagnostics for Admins. Settings > Device Configuration.")
                .image(com.example.basilience.R.drawable.guide_device_config)
                .imagePlaceholder("Device Configuration screen with the Device Clock card and Sensor Test/Refill tabs visible")
                .steps(Arrays.asList(
                        "Device Clock — shows whether the device's real-time clock is connected and what it's currently reading. Read-only; there's no way to set the clock from the app.",
                        "Physical Sensor Test — tap \"Start Sensor Test\" to read live physical sensor values (pH, EC, Air Temperature, Humidity, Water Temperature, Water Level) directly from the hardware, bypassing Mock Sensors. Automatic control pauses while this runs; tap \"Stop Sensor Test\" or leave the screen to resume normal operation.",
                        "Refill Thresholds — the Start and Stop water depth (in cm) that open and close the refill valve. This is different from a parameter's target range — it just controls when the valve turns on and off."))
                .build());

        list.add(GuideSection.builder("Account Settings")
                .image(com.example.basilience.R.drawable.guide_account_info)
                .imagePlaceholder("Account Information screen in view mode")
                .steps(Arrays.asList(
                        "Full Name, Email, and Phone Number are shown here.",
                        "\"Edit Profile\" switches the screen into edit mode to change your Full Name or Phone Number. Email cannot be changed.",
                        "\"Change Password\" opens a dialog asking for your current password and a new one.",
                        "\"Log Out\" signs you out of Basilience and returns to the login screen."))
                .build());

        list.add(GuideSection.builder("Wi-Fi Configuration")
                .description("Used the first time a Basilience device is set up, or whenever it needs to be moved to a different Wi-Fi network.")
                .image(com.example.basilience.R.drawable.guide_wifi_config)
                .imagePlaceholder("Wi-Fi Configuration screen with the device status card and network name/password fields")
                .steps(Arrays.asList(
                        "On your phone's Wi-Fi settings, connect to the \"Basilience-Setup\" network broadcast by the device.",
                        "Return to Basilience and open Wi-Fi Configuration — from Device Management by pressing and holding a device and choosing \"Configure Wi-Fi,\" from Monitoring's \"Retry Wi-Fi Configuration\" button, or by tapping a Wi-Fi setup notification.",
                        "Enter your home/facility Wi-Fi Network Name and Password.",
                        "Tap \"Save & Reconnect.\" Basilience sends the credentials to the device directly over the local setup connection — no internet connection is required for this step.",
                        "Once saved, the device reconnects to your network and the Current Device Status card updates automatically."))
                .build());

        // ------------------------------------------------------------
        // Developer Options - gated by the account's Developer Tester
        // entitlement AND developer mode enabled for the selected device
        // (see SettingsFragment.updateDeveloperOptionsVisibility() /
        // DevOptionsFragment's matching re-check), and hidden from the
        // guide entirely for accounts that can't actually reach the real
        // screen (see MobileGuideFragment.visibleSections()). This is a
        // DIFFERENT screen from Device Configuration above, even though
        // both are the same DevOptionsFragment opened in a different
        // mode - Physical Sensor Test and the Device Clock live in
        // Device Configuration only; Mock Data, Parameter Target
        // Ranges, Automation Testing, and Safety Overrides live here
        // only (see DevOptionsFragment.configureAccessMode()). The role
        // badge and warning appear once on the overview section rather
        // than on every one, to avoid cluttering a block that's already
        // filtered as a unit for accounts that can't reach it.
        // ------------------------------------------------------------
        list.add(GuideSection.builder("Developer Options")
                .role(ADMIN_ONLY)
                .adminOnly(true)
                .description("Testing, simulation, and maintenance tools for developers - not needed for normal Basilience operation, and separate from the plain Device Configuration screen every Admin can reach.")
                .image(com.example.basilience.R.drawable.guide_developer_options)
                .imagePlaceholder("Developer Options screen with the major diagnostic groups/buttons visible")
                .steps(Arrays.asList(
                        "Developer Options only appears once your account has Developer Tester access and developer mode has been turned on for the selected device — it will not appear for an ordinary Admin account, even though the screen exists in the app.",
                        "Once both are on, it's reached from Settings, in a \"Developer Options\" row that only shows up while they are.",
                        "The screen covers Mock Data, Parameter Target Ranges, Automation Testing, and Safety Overrides, with \"Enable Provisioning/AP Mode\" and \"Disable Developer Mode\" always visible at the bottom."))
                .warning("Some of these tools change real device behavior or data — Mock Sensors, Automation Testing, the Safety Overrides, and Provisioning/AP Mode all affect the actual device, not just a preview. Use them only when testing or validating the system, and turn them back off when you're done.")
                .build());

        list.add(GuideSection.builder("Developer Options — Data & Simulation Testing")
                .adminOnly(true)
                .description("Mock Sensors replace the values used by the device's real automatic control with values you type in — useful for demonstrations or testing automation without needing real plant conditions.")
                .steps(Arrays.asList(
                        "Enable Mock Data Override — turns on simulated sensor values for this device, useful for demonstrations or for testing how automation reacts to a specific condition (like a low pH) without waiting for it to happen for real. A confirmation dialog explains that mock values will replace the real readings until you turn this off, and Monitoring will show your entered values instead of the actual sensors.",
                        "pH / EC / Air Temperature / Humidity / Water Temperature / Water Level fields and \"Push Mock Values to ESP32\" — enter the values you want to simulate, then tap Push to send them to the device. You'll see a success message once the device confirms it got them, or a warning if it doesn't respond within about 15 seconds.",
                        "Remember to turn Mock Data Override back off when you're done — the device keeps acting on the mock values until you do."))
                .build());

        list.add(GuideSection.builder("Developer Options — Parameter Target Ranges")
                .adminOnly(true)
                .description("The acceptable growing range for each monitored parameter, grouped here with the other developer/testing tools.")
                .imagePlaceholder("Parameter Target Ranges screen showing Minimum and Maximum for each parameter")
                .steps(Arrays.asList(
                        "Tap \"Parameter Target Ranges\" to open it as its own screen.",
                        "Each parameter has a Minimum and a Maximum. The Minimum is the lowest value considered acceptable, and the Maximum is the highest.",
                        "A reading below the minimum or above the maximum is marked as out of range in Monitoring, and appears in red on the Reports charts.",
                        "Enter the values for a parameter and tap Save Changes. The minimum must be lower than the maximum.",
                        "Changes reach the device within about a minute and are used for monitoring, alerts and reports from then on."))
                .tip("Each growth cycle keeps the target ranges that were in use when the cycle was created, so changing the ranges later does not change that cycle's report. Cycles created before this feature use the ranges configured now.")
                .build());

        list.add(GuideSection.builder("Developer Options — Automation Testing")
                .adminOnly(true)
                .description("Lets a developer test one part of the automatic system on its own, without turning off real sensors, actuators, or safety checks.")
                .steps(Arrays.asList(
                        "Automation Test Mode — pick a subsystem from the dropdown to pause just that part's automatic control so it can be tested by hand. Manual controls and safety checks keep working the whole time.",
                        "Grow Light Schedule test mode only: a \"Mock Grow Light Time\" switch and button let you test the light's on/off schedule against a time you choose, instead of waiting for the real clock to reach it. This never changes the device's actual clock, and stops having any effect once you switch to a different test mode."))
                .build());

        list.add(GuideSection.builder("Developer Options — Safety & Maintenance")
                .adminOnly(true)
                .steps(Arrays.asList(
                        "Ignore Water Level Automation — for developer testing only. Temporarily turns off the automatic low-water/refill response while still showing the real water level. Turn it back off when you're done, since the device keeps ignoring low water until you do.",
                        "Enable Provisioning/AP Mode — remotely tells an already-online device to start its local \"Basilience-Setup\" Wi-Fi network, so its Wi-Fi can be reconfigured without needing physical access to it. A confirmation dialog explains that no Wi-Fi credentials are sent by this step alone — afterward, connect to \"Basilience-Setup\" and send credentials as usual to finish.",
                        "Disable Developer Mode — turns off developer mode for this device and returns to Settings. Developer Options won't appear again until it's turned back on; nothing else changes."))
                .build());

        list.add(GuideSection.builder("When the Device is Offline")
                .image(com.example.basilience.R.drawable.guide_monitoring)
                .imagePlaceholder("Monitoring screen showing the Device Unreachable status banner")
                .steps(Arrays.asList(
                        "RECONNECTING... appears while Basilience is waiting to hear from the device again — this is often brief and needs no action.",
                        "DEVICE UNREACHABLE appears once the device has been silent long enough to be considered offline. Check that it has power and that its Wi-Fi network is available.",
                        "Once the device reports in again, the status returns to ONLINE automatically and you'll typically also see a \"Device Back Online\" notification."))
                .build());

        list.add(GuideSection.builder("Common Messages")
                .description("A quick reference for status text you may see around the app.")
                .steps(Arrays.asList(
                        "Below Range / Above Range \u2014 the reading is outside the parameter's configured Minimum or Maximum.",
                        "No Data \u2014 a sensor reading hasn't arrived yet or is currently invalid. Not the same as being out of range.",
                        "Device Unreachable — Basilience hasn't heard from the device recently enough to consider it online.",
                        "Unable to load data — a screen couldn't refresh from Basilience's servers; check your phone's internet connection and try again.",
                        "No growth cycles yet — this device has no cycles recorded yet; an Admin or assigned Personnel can add one.",
                        "Harvest not ready — the next scheduled harvest date hasn't arrived yet for this cycle."))
                .build());

        return list;
    }
}
