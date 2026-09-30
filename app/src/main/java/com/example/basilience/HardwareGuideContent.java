package com.example.basilience;

import com.example.basilience.models.GuideSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Content for the Hardware/System Guide. See {@link MobileGuideContent} for
 * the shared rationale (content kept out of the Fragment).
 *
 * <p>Actuator and sensor names are taken directly from
 * Parameters_Monitoring_Fragment's actuator list and the six Monitoring
 * parameter cards, not from external documentation - this repository does
 * not contain the firmware source, so no claim is made here about internals
 * (state machines, wiring, NVS storage, etc.) that can't be verified from the
 * Android app's own behavior.
 *
 * <p>Each section documenting one physical component is tagged with a
 * {@link HardwareComponentKey} via {@code .hardwareKey(...)} and restructured
 * into Purpose / Normal Operation (the existing numbered {@code .steps(...)})
 * / Indicators / Common Problems / Troubleshooting, so a notification about
 * that component (see HardwareComponentKey.resolve) can deep-link straight to
 * it. System-wide sections (overview, manual control, maintenance, startup,
 * connectivity, safety) intentionally keep the plain description/steps shape
 * and no component key, since they don't document one specific part.
 */
final class HardwareGuideContent {

    private HardwareGuideContent() {}

    private static final String ADMIN_ONLY = "Admin Only";

    /** All Hardware/System Guide sections, in display order. */
    static List<GuideSection> sections() {
        List<GuideSection> list = new ArrayList<>();

        list.add(GuideSection.builder("Basilience System Overview")
                .description("Basilience is a cultivation monitoring and automation system: it reads conditions in your growing environment and controls equipment to keep them in range.")
                .image(com.example.basilience.R.drawable.hw_system_overview)
                .imagePlaceholder("Full physical Basilience system installed at a grow site")
                .steps(Arrays.asList(
                        "A controller reads sensors and operates the connected equipment.",
                        "Readings and status are sent to Basilience's cloud service.",
                        "The Android app shows you those readings and lets you send commands back to the controller.",
                        "The system keeps monitoring and automating even while no one is looking at the app."))
                .build());

        list.add(GuideSection.builder("Hardware Video Tutorial")
                .hardwareKey(HardwareComponentKey.VIDEO_TUTORIAL)
                .video("Video Tutorial", "A step-by-step hardware setup and usage video will be available here.")
                .build());

        list.add(GuideSection.builder("Main Controller")
                .hardwareKey(HardwareComponentKey.MAIN_CONTROLLER)
                .image(com.example.basilience.R.drawable.hw_main_controller)
                .imagePlaceholder("Basilience controller enclosure, closed")
                .purpose("The central unit every sensor and piece of equipment connects to. It continuously reads sensor values, runs the automatic control logic, reports readings/status to Basilience, and receives commands from the app in return.")
                .steps(Arrays.asList(
                        "Connects to your facility's Wi-Fi network to communicate with Basilience.",
                        "Keeps monitoring and controlling equipment on its own, independent of whether the app is open."))
                .indicators(Arrays.asList(
                        "Dashboard/Monitoring status line reads ONLINE when it's reporting normally.",
                        "Parameter cards on Monitoring show real numbers rather than No Data when it's reading sensors correctly."))
                .commonProblems(Arrays.asList(
                        "Device shows Unreachable or stuck on RECONNECTING.",
                        "Every sensor shows No Data at the same time (rather than just one probe)."))
                .troubleshooting(Arrays.asList(
                        "Check the controller has power and is switched on.",
                        "Check your facility's Wi-Fi network is up and the controller is within range.",
                        "If every sensor lost data at once, suspect the controller or Wi-Fi rather than one probe.",
                        "If the app can't load data even though the equipment looks fine in person, check your phone's own internet connection first.",
                        "Once the connection is restored, the app updates automatically - no action is needed beyond restoring power/Wi-Fi."))
                .build());

        list.add(GuideSection.builder("pH Sensor")
                .hardwareKey(HardwareComponentKey.PH_SENSOR)
                .image(com.example.basilience.R.drawable.hw_ph_sensor)
                .imagePlaceholder("pH probe positioned in the reservoir")
                .purpose("Measures the acidity/alkalinity of the nutrient solution. Nutrients become harder for plants to absorb when pH drifts too far in either direction, so this reading matters for plant health.")
                .steps(Arrays.asList(
                        "Continuously reports its reading to the controller, which uses it for automatic pH correction (see Peristaltic Pumps)."))
                .indicators(Arrays.asList(
                        "The pH card on Monitoring shows a live numeric reading when the probe is working.",
                        "The card's styling flags a reading that's outside the configured Parameter Target Range."))
                .commonProblems(Arrays.asList(
                        "\"No Data\" on the pH card.",
                        "Reading looks physically implausible or stays exactly the same for an unusually long time."))
                .troubleshooting(Arrays.asList(
                        "Check the probe is actually submerged in the reservoir solution.",
                        "Check the probe's cable connection at the controller.",
                        "A reading that stays suspiciously constant even as conditions change may need calibration - follow your system's maintenance procedure rather than adjusting it from the app."))
                .build());

        list.add(GuideSection.builder("EC Sensor")
                .hardwareKey(HardwareComponentKey.EC_SENSOR)
                .image(com.example.basilience.R.drawable.hw_ec_sensor)
                .imagePlaceholder("EC probe positioned in the reservoir")
                .purpose("Measures the electrical conductivity of the nutrient solution, shown in mS/cm - a stand-in for how concentrated the dissolved nutrients are. Too low and plants may be under-fed; too high can stress the roots.")
                .steps(Arrays.asList(
                        "Continuously reports its reading to the controller, which uses it for automatic nutrient correction (see Peristaltic Pumps)."))
                .indicators(Arrays.asList(
                        "The EC card on Monitoring shows a live mS/cm reading when the probe is working.",
                        "The card's styling flags a reading that's outside the configured Parameter Target Range."))
                .commonProblems(Arrays.asList(
                        "\"No Data\" on the EC card.",
                        "Reading looks physically implausible or stays exactly the same for an unusually long time."))
                .troubleshooting(Arrays.asList(
                        "Check the probe is actually submerged in the reservoir solution.",
                        "Check the probe's cable connection at the controller.",
                        "A reading that stays suspiciously constant even as conditions change may need calibration - follow your system's maintenance procedure rather than adjusting it from the app."))
                .build());

        list.add(GuideSection.builder("Water Temperature Sensor")
                .hardwareKey(HardwareComponentKey.WATER_TEMPERATURE_SENSOR)
                .image(com.example.basilience.R.drawable.hw_water_temp_sensor)
                .imagePlaceholder("Water temperature probe in the reservoir")
                .purpose("Measures the temperature of the nutrient solution itself, separately from the surrounding air. Root health and nutrient uptake are sensitive to water temperature.")
                .steps(Arrays.asList(
                        "Continuously reports its reading to the controller, which uses it for automatic cooling (see Temperature Control)."))
                .indicators(Arrays.asList(
                        "The Water Temperature card on Monitoring shows a live reading when the probe is working."))
                .commonProblems(Arrays.asList(
                        "\"No Data\" on the Water Temperature card.",
                        "Reading looks physically implausible."))
                .troubleshooting(Arrays.asList(
                        "Check the probe is disconnected or reports a value the app recognizes as invalid.",
                        "Check the probe's physical connection at the controller.",
                        "If the reading has been suspiciously constant for a long time, it may need calibration - follow your system's maintenance procedure rather than adjusting it from the app."))
                .build());

        list.add(GuideSection.builder("Air Temperature / Humidity Sensor")
                .hardwareKey(HardwareComponentKey.AIR_TEMPERATURE_HUMIDITY_SENSOR)
                .image(com.example.basilience.R.drawable.hw_air_temp_humidity_sensor)
                .imagePlaceholder("Air temperature/humidity sensor mounted in the canopy area")
                .purpose("Measures the surrounding air's temperature and humidity around the plants. Both affect transpiration and disease risk, so the system watches them together.")
                .steps(Arrays.asList(
                        "Continuously reports both readings to the controller, which uses them for automatic climate response (Canopy Fan, Fogging System, Root Blower)."))
                .indicators(Arrays.asList(
                        "The Air Temperature and Humidity cards on Monitoring show live readings when the sensor is working."))
                .commonProblems(Arrays.asList(
                        "\"No Data\" on either card.",
                        "Reading looks physically implausible."))
                .troubleshooting(Arrays.asList(
                        "Check the sensor is disconnected or its most recent reading is invalid.",
                        "Check the sensor's mounting and cable connection.",
                        "If a reading has been suspiciously constant for a long time, it may need calibration - follow your system's maintenance procedure rather than adjusting it from the app."))
                .build());

        list.add(GuideSection.builder("Water Level Sensor")
                .hardwareKey(HardwareComponentKey.WATER_LEVEL_SENSOR)
                .image(com.example.basilience.R.drawable.hw_water_level_sensor)
                .imagePlaceholder("Water level sensor in the reservoir")
                .purpose("Measures how full the reservoir is, shown as a percentage.")
                .steps(Arrays.asList(
                        "A low reading can trigger an automatic refill, and is also what drives the Fogging Report's Water Outlook estimate."))
                .indicators(Arrays.asList(
                        "The Water Level card on Monitoring shows a live percentage when the sensor is working."))
                .commonProblems(Arrays.asList(
                        "\"No Data\" on the Water Level card.",
                        "Reading is stuck at 0% or 100%."))
                .troubleshooting(Arrays.asList(
                        "Check the sensor is disconnected or its reading is invalid.",
                        "If the level is genuinely low rather than a sensor problem, see Water & Reservoir instead - that's not a sensor fault."))
                .build());

        list.add(GuideSection.builder("Fogging System")
                .hardwareKey(HardwareComponentKey.FOGGER)
                .image(com.example.basilience.R.drawable.hw_fogging_system)
                .imagePlaceholder("Ultrasonic fogger unit")
                .purpose("The fogger produces a fine mist inside the root chamber, used to raise humidity and, depending on conditions, help cool the growing area.")
                .steps(Arrays.asList(
                        "Runs automatically under the controller's logic by default.",
                        "Can also be started by hand from Monitoring (Fogger switch) when Manual Mode is on. An Admin turns Manual Mode on. A Farmer needs an Admin's approval first.",
                        "Starting it by hand also runs the Root Blower together with it - see Root Blower.",
                        "During Night Fogging, Basilience uses a reduced 2-minute ON and 10-minute OFF fogging cycle from 10:00 PM to 6:00 AM. After the night period ends, the system returns to its temperature-based fogging schedule."))
                .indicators(Arrays.asList(
                        "Fogger's status line on Monitoring shows whether it is running automatically (Auto) or being controlled manually through the Basilience mobile application (Manual). During an automatic run it can also name the fogging pattern in use: Normal, Hot, Cold, or Night.",
                        "Its recent activity and runtime are summarized in the Fogging Report, including a Fogging Strategy list (Normal, Startup, Hot, Cold, Night)."))
                .commonProblems(Arrays.asList(
                        "Doesn't turn on when expected.",
                        "Runs continuously / won't turn off."))
                .troubleshooting(Arrays.asList(
                        "Check whether the Fogger shows the Manual tag. A manually controlled Fogger is on hold and waits for your command instead of the automatic fogging schedule.",
                        "Check the Fogging Report for its recent runtime to see whether it's actually behaving as expected.",
                        "If it seems stuck on, an Admin can use Manual Mode to turn it off directly."))
                .build());

        list.add(GuideSection.builder("Root Blower")
                .hardwareKey(HardwareComponentKey.BLOWER)
                .image(com.example.basilience.R.drawable.hw_root_blower)
                .imagePlaceholder("Root blower fan mounted near the root chamber")
                .purpose("Moves the fogger's mist through the root chamber, shown on Monitoring as \"Reservoir Fan (Blower).\"")
                .steps(Arrays.asList(
                        "Starting root fogging by hand (Fogger switch, Manual Mode) also runs the Root Blower together with it - it isn't a separate step.",
                        "Airflow increases automatically when air temperature or humidity is high, to clear fog and heat faster.",
                        "After manual fogging stops, the blower briefly keeps running at increased airflow (about 30 seconds) to clear remaining fog, then turns off on its own."))
                .indicators(Arrays.asList(
                        "Its recent activity and runtime are summarized in the Fogging Report."))
                .commonProblems(Arrays.asList(
                        "Doesn't increase airflow when air temperature or humidity is high.",
                        "Keeps running well past 30 seconds after manual fogging stops."))
                .troubleshooting(Arrays.asList(
                        "Check whether the Reservoir Fan (Blower) shows the Manual tag. A manually controlled item is on hold and does not follow the automatic system until it is switched off or Manual Mode ends.",
                        "Check the Fogging Report for its recent runtime to see whether it's actually behaving as expected."))
                .build());

        list.add(GuideSection.builder("Peristaltic Pumps")
                .hardwareKey(HardwareComponentKey.PERISTALTIC_PUMPS)
                .image(com.example.basilience.R.drawable.hw_nutrient_ph_control)
                .imagePlaceholder("Nutrient and pH dosing pumps mounted near the reservoir")
                .purpose("Three peristaltic pumps keep the reservoir's nutrient strength and pH in range: the pH Up Pump, the pH Down Pump, and the Nutrient Pump (drawing from the Grow and Bloom solutions, shown on Monitoring as \"Nutrients (EC)\").")
                .steps(Arrays.asList(
                        "All three run automatically based on the pH/EC readings by default, and can be triggered by hand from Monitoring when Manual Mode is enabled.",
                        "A manual pH Up, pH Down, or Nutrient Pump request runs that pump for a single 5-second dose and stops it automatically - a one-time manual dose, not the system's full automatic correction, which keeps checking and adjusting afterward."))
                .indicators(Arrays.asList(
                        "Each pump's status line on Monitoring shows whether it is running automatically (Auto) or being controlled manually through the Basilience mobile application (Manual)."))
                .commonProblems(Arrays.asList(
                        "pH or EC keeps drifting back out of range despite dosing.",
                        "A pump doesn't respond to a manual command.",
                        "\"pH correction needs attention\" or \"Nutrient correction needs attention\" notification appears."))
                .troubleshooting(Arrays.asList(
                        "Check the Grow, Bloom, pH Up, and pH Down solution containers regularly and refill them before they run out - dosing can only work if there is solution for the pumps to draw from.",
                        "Check whether the pump shows the Manual tag. A manually controlled pump is on hold and won't follow the automatic system until it is switched off or Manual Mode ends.",
                        "A pH/Nutrient correction-needed notification means the automatic system tried and couldn't bring the reading back into range - check solution levels and the reservoir before anything else.",
                        "Once the underlying condition is fixed, an Admin can use Reset Safety (Monitoring screen, see Safety) to clear the lock."))
                .build());

        list.add(GuideSection.builder("Temperature Control")
                .hardwareKey(HardwareComponentKey.TEMPERATURE_CONTROL)
                .image(com.example.basilience.R.drawable.hw_temperature_control)
                .imagePlaceholder("Canopy fan, reservoir fan/blower, and Peltier cooling module")
                .purpose("Three pieces of equipment work together to manage temperature: Canopy Fan (circulates air around the plants), Reservoir Fan/Blower (see Root Blower for its own details), and Peltier (Temp), which actively cools the reservoir when water temperature runs high.")
                .steps(Arrays.asList(
                        "All three read from Water Temperature and Air Temperature/Humidity and normally run automatically."))
                .indicators(Arrays.asList(
                        "Each actuator's status line on Monitoring shows whether it is running automatically (Auto) or being controlled manually through the Basilience mobile application (Manual)."))
                .commonProblems(Arrays.asList(
                        "Water temperature stays high despite cooling running.",
                        "\"Cooling System Stopped\" notification appears."))
                .troubleshooting(Arrays.asList(
                        "Check the Peltier module isn't obstructed and has power.",
                        "A \"Cooling System Stopped\" notification means Basilience couldn't safely maintain cooling - check the water level and the reservoir before anything else, since cooling depends on a healthy water level.",
                        "Once the underlying condition is fixed, an Admin can use Reset Safety (Monitoring screen, see Safety) to clear the lock."))
                .build());

        list.add(GuideSection.builder("Grow Light")
                .hardwareKey(HardwareComponentKey.GROW_LIGHT)
                .image(com.example.basilience.R.drawable.hw_grow_light)
                .imagePlaceholder("Grow light fixture over the canopy")
                .purpose("Provides light for the plants on a schedule managed by the automatic system.")
                .steps(Arrays.asList(
                        "Can be switched on or off by hand from Monitoring when Manual Mode is enabled."))
                .indicators(Arrays.asList(
                        "Its status line on Monitoring shows whether it's currently on/off and whether it is running automatically (Auto) or being controlled manually through the Basilience mobile application (Manual)."))
                .commonProblems(Arrays.asList(
                        "Doesn't turn on/off on schedule.",
                        "Doesn't respond to a manual switch."))
                .troubleshooting(Arrays.asList(
                        "Check whether the light shows the Manual tag. A manually controlled light is on hold and waits for your command instead of the schedule.",
                        "Check the light fixture has power and its connection to the controller."))
                .build());

        list.add(GuideSection.builder("Water & Reservoir")
                .hardwareKey(HardwareComponentKey.RESERVOIR)
                .image(com.example.basilience.R.drawable.hw_water_reservoir)
                .imagePlaceholder("Reservoir with circulation pump and water pump/valve visible")
                .purpose("The reservoir holds the nutrient solution, monitored by the Water Level sensor and kept correct by the Circulation Pump (keeps solution moving so readings stay representative and nutrients stay mixed) and Water Pump (Valve), which handles refilling.")
                .steps(Arrays.asList(
                        "An Admin can also start a refill manually from Monitoring's \"Start Reservoir Refill\" action."))
                .indicators(Arrays.asList(
                        "Water Level card on Monitoring shows the current fill percentage.",
                        "\"Start Reservoir Refill\" (Admin, Monitoring) is available to trigger a refill manually."))
                .commonProblems(Arrays.asList(
                        "Water level reads low.",
                        "\"Reservoir refill needs attention\" notification appears."))
                .troubleshooting(Arrays.asList(
                        "Check the reservoir and refill it, or use \"Start Reservoir Refill\" if your account is an Admin.",
                        "A refill-needs-attention notification means Basilience couldn't refill normally - check the water supply and refill system before anything else.",
                        "If the Circulation Pump doesn't seem to be running, check whether it shows the Manual tag. A manually controlled pump is on hold and won't follow the automatic system.",
                        "Once the underlying condition is fixed, an Admin can use Reset Safety (Monitoring screen, see Safety) to clear a refill lock."))
                .build());

        list.add(GuideSection.builder("Harvest Scale")
                .hardwareKey(HardwareComponentKey.HARVEST_SCALE)
                .image(com.example.basilience.R.drawable.hw_harvest_scale)
                .imagePlaceholder("Basilience Harvest Scale with a harvested item on its platform")
                .purpose("A separate, dedicated device that weighs harvested crop and can fill in a harvest entry automatically once paired to this device (Device Management > Pair Harvest Scale). It runs on its own hardware and Wi-Fi connection, separate from the main controller - it stays usable even while the main controller is offline.")
                .steps(Arrays.asList(
                        "On every power-on, it re-zeroes itself to whatever is currently resting on its platform, then a short countdown follows before it starts trusting readings.",
                        "Once ready, place the item to be weighed on the platform and hold it still - the scale detects when the reading has settled and logs the weight on its own, without needing a button press."))
                .indicators(Arrays.asList(
                        "The weight becomes available in the app a few seconds after the scale detects and logs it.",
                        "To record it, open Harvest Report, tap +, and choose Read from Scale (Add Harvest offers Manual Entry or Read from Scale). Basilience uses the newest eligible reading right away, with no preview screen and no extra Save step.",
                        "A reading is eligible only if it is recent (less than 30 minutes old) and has not already been recorded."))
                .commonProblems(Arrays.asList(
                        "Weight reads zero or clearly wrong right after power-on.",
                        "Scale isn't reachable from the app."))
                .troubleshooting(Arrays.asList(
                        "If a real item was on the platform during power-on, its weight was zeroed out along with the platform - power-cycle the scale with the platform actually clear, then re-weigh.",
                        "Check the scale's own Wi-Fi connection separately from the main controller's - it runs on its own network."))
                .warning("The platform must be empty of any harvest item during that power-on zeroing step. Whatever is on it at that moment - even a real item - gets zeroed out right along with the platform, and its weight goes silently missing from every reading afterward until the scale is next power-cycled with the platform actually clear.")
                .build());

        list.add(GuideSection.builder("Automatic vs. Manual Control")
                .image(com.example.basilience.R.drawable.guide_actuators)
                .imagePlaceholder("Monitoring screen with the Manual Mode switch and an actuator row")
                .steps(Arrays.asList(
                        "By default, every pump, fan, and light is controlled automatically based on sensor readings.",
                        "Manual Mode is on the Monitoring screen. An Admin can turn it on and off. A Farmer cannot turn it on alone, but can tap the Manual Mode switch to ask an Admin for manual-control access. The Admin approves or denies the request, and sees it only while that device's Monitoring screen is open.",
                        "Manual control puts a hold on the actuator you control by hand. Other eligible automatic operations may continue. If an automatic job such as refilling or dosing is already running, turning Manual Mode on can stop it, and the app asks you to confirm first.",
                        "Built-in safety checks remain active in Manual Mode - a request can still be turned down if conditions aren't safe.",
                        "Manual Mode automatically ends after about 15 minutes of inactivity, returning the system to automatic operation. Each manual action restarts that time.",
                        "Each actuator's status line shows how it is being controlled: Auto (the automatic system is running it) or Manual (the actuator is being controlled manually through the Basilience mobile application).",
                        "If an actuator's status looks unexpected, check whether it shows the Manual tag. A manually controlled actuator is on hold until it is switched off or Manual Mode ends."))
                .build());

        list.add(GuideSection.builder("Routine Maintenance")
                .description("A few simple, non-technical checks keep the system running smoothly between growth cycles.")
                .steps(Arrays.asList(
                        "Wipe down accessible surfaces, the reservoir lid, and sensor probes periodically to prevent buildup that could affect readings. Disconnect power first (see Safety).",
                        "If a sensor reading looks consistently wrong even after cleaning, it may need calibration. This should be done following your system's maintenance procedure rather than adjusted from the app.",
                        "To power off safely: finish or pause whatever the system is doing, then disconnect power to the controller. There's no separate shutdown step in the app."))
                .build());

        list.add(GuideSection.builder("Starting the System")
                .description("A practical checklist for bringing the system online.")
                .steps(Arrays.asList(
                        "Check the reservoir has enough water and nutrient solution.",
                        "Confirm the controller and connected equipment have power.",
                        "Power on the controller and allow it a short moment to start up.",
                        "On your phone, open Basilience and select this device.",
                        "Check the Dashboard status line reads ONLINE.",
                        "Open Monitoring and confirm the parameter cards are showing real readings rather than No Data."))
                .build());

        list.add(GuideSection.builder("Internet / Wi-Fi Loss")
                .description("If the controller loses its Wi-Fi connection or Basilience's cloud service is unreachable, the app can no longer see live updates from it. This is a connectivity issue, not a fault in a specific piece of hardware.")
                .steps(Arrays.asList(
                        "The Dashboard and Monitoring status will show RECONNECTING... and then DEVICE UNREACHABLE if the outage continues.",
                        "Sensor cards keep showing the last known readings rather than clearing to zero.",
                        "Once the connection is restored, the controller resumes reporting and the app updates automatically. No action is needed from you beyond restoring power/Wi-Fi.",
                        "Wi-Fi won't connect during initial setup: make sure your phone is connected to the \"Basilience-Setup\" network before entering your home network's name and password.",
                        "App can't load data even though the device looks fine in person: check your phone's own internet connection first."))
                .tip("If Wi-Fi was changed or moved, see the Wi-Fi Configuration section in the Mobile App Guide.")
                .build());

        list.add(GuideSection.builder("Safety")
                .role(ADMIN_ONLY)
                .warning("Basilience controls pumps, fans, lights, and equipment connected to electrical power and water. Treat the reservoir and controller area with the same care as any electrical/wet-environment equipment.")
                .steps(Arrays.asList(
                        "Keep the controller and its wiring away from standing water and spills.",
                        "Disconnect power before doing any physical maintenance on the reservoir or connected equipment.",
                        "Do not open the controller enclosure or attempt electrical repairs. Contact whoever installed/maintains your system for hardware issues.",
                        "If in doubt about a reading or an actuator behaving unexpectedly: an Admin can turn on Manual Mode and turn the affected equipment off from the app while investigating. If you're a Farmer, tell your Admin. You can also ask for manual-control access from Monitoring, but an Admin has to approve it and you cannot turn Manual Mode on yourself.",
                        "If the system stops an operation because it detected a safety problem, an Admin can use \"Reset Safety\" (Monitoring screen) once the underlying condition has been checked and corrected. Reset Safety only clears the lock. It does not turn any equipment on by itself, and it will not succeed if the unsafe condition is still present.",
                        "If a sensor fault or safety lock keeps coming back after a reset, treat it as a sign the underlying physical issue (a disconnected probe, a genuinely unsafe reading, low reservoir water, etc.) hasn't actually been resolved yet, and check the equipment again before trying Reset Safety a second time."))
                .build());

        return list;
    }

    /** The bundled factory section for one component, or null if that key has no restructured section (shouldn't happen for a real component key). */
    static GuideSection byKey(HardwareComponentKey key) {
        for (GuideSection section : sections()) {
            if (section.getHardwareKey() == key) return section;
        }
        return null;
    }
}
