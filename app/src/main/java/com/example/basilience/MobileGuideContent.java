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
                .imagePlaceholder("Harvest screen showing the summary card, chart, and history list")
                .steps(Arrays.asList(
                        "Open a cycle from Growth Cycles to reach its Harvest screen.",
                        "The summary card shows Total Harvested weight and the number of Harvest Entries.",
                        "For an active cycle, the schedule shows \"Ready to Harvest\" (in green) once a harvest is due, or \"Next Harvest\" with the upcoming date beforehand.",
                        "For a completed cycle, the schedule shows the Completed Date instead, and no further harvests can be added.",
                        "The Accumulated Harvest chart plots running total weight over the cycle; Harvest History below lists every individual entry, newest first."))
                .build());

        list.add(GuideSection.builder("Recording Harvest Weight")
                .description("Harvest weight is entered manually. There is currently no automatic weight sensor reading in this app.")
                .imagePlaceholder("Add Harvest dialog with weight field and today's date shown")
                .steps(Arrays.asList(
                        "On an active cycle's Harvest screen, tap the + button.",
                        "The dialog shows the date this harvest will be recorded under.",
                        "Enter the harvested weight in grams.",
                        "Notes are optional.",
                        "Tap Save. The total, chart, and history update immediately."))
                .warning("If a harvest isn't due yet, tapping + shows how many days remain instead of the entry form. Admin accounts can choose to override this and log the harvest early, which also resets the schedule from that date.")
                .build());

        list.add(GuideSection.builder("Reports")
                .role(ADMIN_ONLY)
                .description("The Reports tab (bottom navigation) opens a selector with two report types.")
                .imagePlaceholder("Reports selector showing the Parameter Reports and Fogging Reports cards")
                .steps(Arrays.asList(
                        "Parameter Reports covers sensor readings (pH, EC, temperature, humidity, water level) over time.",
                        "Fogging Reports covers fogger activity and water usage.",
                        "Tap either card to open that report."))
                .build());

        list.add(GuideSection.builder("Parameter Report")
                .description("Shows sensor trends for a chosen cycle and parameter.")
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
                .imagePlaceholder("Device Management screen with the claim field and a registered device list")
                .steps(Arrays.asList(
                        "Enter the device's token code and tap \"Claim Device\" to add it to your account.",
                        "Registered Devices lists everything claimed to your account, with a live status dot.",
                        "Tap a device to select it — the rest of the app will then work with that device.",
                        "Press and hold a device to choose \"Configure Wi-Fi\" or \"Unclaim Device.\""))
                .build());

        list.add(GuideSection.builder("Settings")
                .description("Reached from the gear icon in the top-right corner of any screen.")
                .imagePlaceholder("Settings screen listing Account Information, About Basilience, and Terms and Agreements")
                .steps(Arrays.asList(
                        "Account Information — view and edit your profile, or log out.",
                        "Parameter Target Ranges — the acceptable minimum and maximum for each monitored parameter. Admins can change them; everyone else can view them.",
                        "About Basilience — general information about the app.",
                        "Terms and Agreements — the app's terms of use.",
                        "Developer Options — Admin Only. Diagnostic and testing utilities used for system maintenance and validation. Only appears once a developer-mode flag has been turned on for the device — see the Developer Options section of this guide."))
                .build());

        list.add(GuideSection.builder("Parameter Target Ranges")
                .description("The acceptable growing range for each monitored parameter. Settings > Parameter Target Ranges.")
                .imagePlaceholder("Parameter Target Ranges screen showing Minimum and Maximum for each parameter")
                .steps(Arrays.asList(
                        "Each parameter has a Minimum and a Maximum. The Minimum is the lowest value considered acceptable, and the Maximum is the highest.",
                        "A reading below the minimum or above the maximum is marked as out of range in Monitoring, and appears in red on the Reports charts.",
                        "Enter the values for a parameter and tap Save Changes. The minimum must be lower than the maximum.",
                        "Changes reach the device within about a minute and are used for monitoring, alerts and reports from then on.",
                        "Only an Admin can change these values; other users can open the screen to see what they are currently set to."))
                .tip("Each growth cycle keeps the target ranges that were in use when the cycle was created, so changing the ranges later does not change that cycle's report. Cycles created before this feature use the ranges configured now.")
                .build());

        list.add(GuideSection.builder("Account Settings")
                .imagePlaceholder("Account Information screen in view mode")
                .steps(Arrays.asList(
                        "Full Name and Phone Number can be edited directly on this screen.",
                        "Email is shown but cannot be changed here.",
                        "\"Change Password\" opens a dialog asking for your current password and a new one.",
                        "\"Log Out\" signs you out of Basilience and returns to the login screen."))
                .build());

        list.add(GuideSection.builder("Wi-Fi Configuration")
                .description("Used the first time a Basilience device is set up, or whenever it needs to be moved to a different Wi-Fi network.")
                .imagePlaceholder("Wi-Fi Configuration screen with the device status card and network name/password fields")
                .steps(Arrays.asList(
                        "On your phone's Wi-Fi settings, connect to the \"Basilience-Setup\" network broadcast by the device.",
                        "Return to Basilience and open Wi-Fi Configuration — from Device Management by pressing and holding a device and choosing \"Configure Wi-Fi,\" from Monitoring's \"Retry Wi-Fi Configuration\" button, or by tapping a Wi-Fi setup notification.",
                        "Enter your home/facility Wi-Fi Network Name and Password.",
                        "Tap \"Save & Reconnect.\" Basilience sends the credentials to the device directly over the local setup connection — no internet connection is required for this step.",
                        "Once saved, the device reconnects to your network and the Current Device Status card updates automatically."))
                .build());

        // ------------------------------------------------------------
        // Developer Options - Admin Only, and hidden from the guide
        // entirely for non-Admin accounts (see MobileGuideFragment).
        // Every function documented here exists in DevOptionsFragment
        // today; nothing was invented. Grouped to match the app's own
        // two tabs (Sensor Test / Mock Data), plus the always-visible
        // Provisioning/AP Mode and Disable Developer Mode actions below
        // them. Water Refill and Wi-Fi Config were removed from
        // Developer Options - refill thresholds have no app-editable
        // UI anymore, and Wi-Fi Configuration moved to Device
        // Management, reachable by every role without Developer Mode.
        // The role badge and warning appear once on the overview
        // section rather than on every one, to avoid cluttering a
        // block that's already filtered as a unit for non-Admins.
        // ------------------------------------------------------------
        list.add(GuideSection.builder("Developer Options")
                .role(ADMIN_ONLY)
                .adminOnly(true)
                .description("Developer Options contains diagnostic, testing, and maintenance tools intended for administrators and system developers. These functions are not required for normal Basilience operation.")
                .imagePlaceholder("Developer Options screen with the major diagnostic groups/buttons visible")
                .steps(Arrays.asList(
                        "Developer Options only appears once a developer-mode flag has been turned on for a device — it will not appear for an Admin account by default, even though the screen exists in the app.",
                        "Once enabled, it's reached from Settings, in a \"Developer Options\" row that only shows up while that flag is on.",
                        "The screen is organized into two tabs, Sensor Test and Mock Data, with \"Enable Provisioning/AP Mode\" and \"Disable Developer Mode\" always visible below them."))
                .warning("Some of these tools change real device behavior or data — Mock Sensors and Provisioning/AP Mode both affect the actual device, not just a preview. Use Developer Options only when troubleshooting or validating the system, and turn Sensor Test and Mock Sensors back off when you're done.")
                .build());

        list.add(GuideSection.builder("Developer Options — Diagnostics")
                .adminOnly(true)
                .description("Reads real hardware sensors directly, bypassing Mock Sensors, so you can confirm the device's actual wiring and readings.")
                .steps(Arrays.asList(
                        "Physical Sensor Test — Tap \"Start Sensor Test\" to ask the device to report live physical sensor readings (pH, EC, Air Temperature, Humidity, Water Temperature, Water Level, and Water Level Distance) into a diagnostic grid. When to use: to confirm real sensor hardware is wired correctly and producing valid readings, separate from any Mock Sensors values. Expected result: each tile changes from \"NO VALID READING\" to a live number once the device confirms the test is active. Impact: changes real device state — automatic cultivation control pauses while the test runs, and test-driven alerts are suppressed. Tap \"Stop Sensor Test\" (or simply leave the screen) to resume normal automatic operation."))
                .build());

        list.add(GuideSection.builder("Developer Options — Data & Simulation Testing")
                .adminOnly(true)
                .description("Mock Sensors replace the values used by the device's real automatic control with values you type in — useful for demonstrations or testing automation without needing real plant conditions.")
                .steps(Arrays.asList(
                        "Enable Mock Data Override — A switch that turns simulated sensor values on for this device. When to use: for demonstrations, or to test how automation reacts to specific conditions (for example, a low pH) without waiting for real conditions. Expected result: a confirmation dialog explains mock values will replace physical readings until turned off; Monitoring then reflects your entered values instead of the real sensors. Impact: changes real device behavior — automatic control acts on the mock values you provide until Mock Sensors is disabled again.",
                        "pH / EC / Air Temperature / Humidity / Water Temperature / Water Level fields and \"Push Mock Values to ESP32\" — Enter the values to simulate, then push them to the device. When to use: together with Enable Mock Data Override, to set specific test conditions. Expected result: a success message once the device confirms receipt, or a warning if confirmation doesn't arrive within about 15 seconds. Impact: same as above — changes real device state while Mock Sensors is enabled."))
                .build());

        list.add(GuideSection.builder("Developer Options — Advanced & Maintenance")
                .adminOnly(true)
                .steps(Arrays.asList(
                        "Enable Provisioning/AP Mode — Remotely tells an already-online device to start its local \"Basilience-Setup\" Wi-Fi network, without needing physical access to trigger it on the device. When to use: when a device's Wi-Fi needs to be reconfigured but it can't be physically reached. Expected result: a confirmation dialog explains no Wi-Fi credentials are sent through this step; afterward, connect to \"Basilience-Setup\" and send credentials as usual to finish. Impact: sends a real command that changes the device's Wi-Fi mode.",
                        "Disable Developer Mode — Turns off the developer-mode flag for this device and returns to Settings. When to use: when you're done with diagnostics and testing. Expected result: Developer Options no longer appears in Settings until the flag is turned on again. Impact: no data is changed; this only affects whether Developer Options is visible."))
                .build());

        list.add(GuideSection.builder("When the Device is Offline")
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
