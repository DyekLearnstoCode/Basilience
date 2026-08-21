package com.example.basilience;

import com.example.basilience.models.GuideSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Content for the Hardware/System Guide. See {@link MobileGuideContent} for
 * the shared rationale (content kept out of the Fragment, no image resources
 * exist yet).
 *
 * <p>Actuator and sensor names are taken directly from
 * Parameters_Monitoring_Fragment's actuator list and the six Monitoring
 * parameter cards, not from external documentation - this repository does
 * not contain the firmware source, so no claim is made here about internals
 * (state machines, wiring, NVS storage, etc.) that can't be verified from the
 * Android app's own behavior.
 */
final class HardwareGuideContent {

    private HardwareGuideContent() {}

    /** All 13 Hardware/System Guide sections, in display order. */
    static List<GuideSection> sections() {
        List<GuideSection> list = new ArrayList<>();

        list.add(GuideSection.builder("Basilience System Overview")
                .description("Basilience is a cultivation monitoring and automation system: it reads conditions in your growing environment and controls equipment to keep them in range.")
                .imagePlaceholder("Full physical Basilience system installed at a grow site")
                .steps(Arrays.asList(
                        "A controller reads sensors and operates the connected equipment.",
                        "Readings and status are sent to Basilience's cloud service.",
                        "The Android app shows you those readings and lets you send commands back to the controller.",
                        "The system keeps monitoring and automating even while no one is looking at the app."))
                .build());

        list.add(GuideSection.builder("Main Controller")
                .imagePlaceholder("Basilience controller enclosure, closed")
                .steps(Arrays.asList(
                        "The controller is the central unit every sensor and piece of equipment connects to.",
                        "It continuously reads sensor values and runs the automatic control logic.",
                        "It reports readings and equipment status to Basilience, and receives commands from the app in return.",
                        "It connects to your facility's Wi-Fi network to communicate with Basilience."))
                .build());

        list.add(GuideSection.builder("pH Sensor")
                .imagePlaceholder("pH probe positioned in the reservoir")
                .steps(Arrays.asList(
                        "Measures the acidity/alkalinity of the nutrient solution.",
                        "Nutrients become harder for plants to absorb when pH drifts too far in either direction, so this reading matters for plant health.",
                        "\"No Data\" on the pH card usually means the probe isn't submerged, isn't connected, or its reading is currently out of a physically valid range."))
                .build());

        list.add(GuideSection.builder("EC Sensor")
                .imagePlaceholder("EC probe positioned in the reservoir")
                .steps(Arrays.asList(
                        "Measures the electrical conductivity of the nutrient solution, shown in mS/cm — a proxy for how concentrated the dissolved nutrients are.",
                        "Too low and plants may be under-fed; too high can stress the roots.",
                        "\"No Data\" usually means the probe isn't submerged, isn't connected, or its reading is currently out of range."))
                .build());

        list.add(GuideSection.builder("Water Temperature Sensor")
                .imagePlaceholder("Water temperature probe in the reservoir")
                .steps(Arrays.asList(
                        "Measures the temperature of the nutrient solution itself, separately from the surrounding air.",
                        "Root health and nutrient uptake are sensitive to water temperature.",
                        "\"No Data\" appears when the probe is disconnected or reports a value the app recognizes as invalid."))
                .build());

        list.add(GuideSection.builder("Air Temperature / Humidity Sensor")
                .imagePlaceholder("Air temperature/humidity sensor mounted in the canopy area")
                .steps(Arrays.asList(
                        "Measures the surrounding air's temperature and humidity around the plants.",
                        "Both affect transpiration and disease risk, so the system watches them together.",
                        "\"No Data\" means the sensor is disconnected or its most recent reading is invalid."))
                .build());

        list.add(GuideSection.builder("Water Level Sensor")
                .imagePlaceholder("Water level sensor in the reservoir")
                .steps(Arrays.asList(
                        "Measures how full the reservoir is, shown as a percentage.",
                        "A low reading can trigger a refill, and is also what drives the Fogging Report's Water Outlook estimate.",
                        "\"No Data\" means the sensor is disconnected or its reading is invalid."))
                .build());

        list.add(GuideSection.builder("Fogging System")
                .description("The fogger produces a fine mist inside the growing area.")
                .imagePlaceholder("Ultrasonic fogger unit")
                .steps(Arrays.asList(
                        "Used to raise humidity and, depending on conditions, help cool the growing area.",
                        "Runs automatically under the controller's logic by default.",
                        "Can also be switched on or off by hand from Monitoring when Manual Mode is enabled.",
                        "Its recent activity and runtime are summarized in the Fogging Report."))
                .build());

        list.add(GuideSection.builder("Nutrient & pH Control")
                .description("Four dosing pumps keep the reservoir's nutrient strength and pH in range.")
                .imagePlaceholder("Nutrient and pH dosing pumps mounted near the reservoir")
                .steps(Arrays.asList(
                        "Nutrients (Grow and Bloom pumps) add nutrient solution, shown on Monitoring as \"Nutrients (EC).\"",
                        "pH Up and pH Down each dose a small amount of solution to move pH in one direction.",
                        "All four run automatically based on the pH/EC readings by default, and can be triggered by hand from Monitoring when Manual Mode is enabled."))
                .build());

        list.add(GuideSection.builder("Temperature Control")
                .imagePlaceholder("Canopy fan, reservoir fan/blower, and Peltier cooling module")
                .steps(Arrays.asList(
                        "Canopy Fan circulates air around the plants.",
                        "Reservoir Fan (Blower) moves air across the reservoir, shown in the Fogging Report as part of automatic climate response.",
                        "Peltier (Temp) actively cools the reservoir when water temperature runs high.",
                        "All three are read from Water Temperature and Air Temperature/Humidity and normally run automatically."))
                .build());

        list.add(GuideSection.builder("Grow Light")
                .imagePlaceholder("Grow light fixture over the canopy")
                .steps(Arrays.asList(
                        "Provides light for the plants on a schedule managed by the automatic system.",
                        "Can be switched on or off by hand from Monitoring when Manual Mode is enabled."))
                .build());

        list.add(GuideSection.builder("Water & Reservoir")
                .imagePlaceholder("Reservoir with circulation pump and water pump/valve visible")
                .steps(Arrays.asList(
                        "The Water Level sensor watches the reservoir's fill level.",
                        "Circulation Pump keeps the nutrient solution moving so readings stay representative and nutrients stay mixed.",
                        "Water Pump (Valve) handles refilling; an Admin can also trigger a refill manually from Monitoring's \"Trigger Refill\" action."))
                .build());

        list.add(GuideSection.builder("Automatic vs. Manual Control")
                .imagePlaceholder("Monitoring screen with the Manual Mode switch and an actuator row")
                .steps(Arrays.asList(
                        "By default, every pump, fan, and light is controlled automatically based on sensor readings.",
                        "Turning on Manual Mode (on the Monitoring screen) lets you operate individual actuators by hand without disabling the automatic system underneath.",
                        "Each actuator's status line shows whether its current state came from the automatic system (· Auto), a physical control (· Manual), or the app (· App)."))
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
                .description("If the controller loses its Wi-Fi connection or Basilience's cloud service is unreachable, the app can no longer see live updates from it.")
                .steps(Arrays.asList(
                        "The Dashboard and Monitoring status will show RECONNECTING... and then DEVICE UNREACHABLE if the outage continues.",
                        "Sensor cards keep showing the last known readings rather than clearing to zero.",
                        "Once the connection is restored, the controller resumes reporting and the app updates automatically — no action is needed from you beyond restoring power/Wi-Fi."))
                .tip("If Wi-Fi was changed or moved, see the Wi-Fi Configuration section in the Mobile App Guide.")
                .build());

        list.add(GuideSection.builder("Basic Troubleshooting")
                .steps(Arrays.asList(
                        "Device shows Unreachable: check the controller has power and your facility Wi-Fi is up.",
                        "A sensor shows No Data: check that probe's physical connection and that it's submerged/positioned correctly.",
                        "Wi-Fi won't connect during setup: make sure your phone is connected to the \"Basilience-Setup\" network before entering your home network's name and password.",
                        "An actuator's status looks unexpected: check whether Manual Mode is on — if it is, the actuator waits for a command from you instead of the automatic system.",
                        "Water level reads low: check the reservoir and refill it, or use \"Trigger Refill\" if your account is an Admin.",
                        "App can't load data even though the device looks fine: check your phone's own internet connection first."))
                .build());

        list.add(GuideSection.builder("Safety")
                .warning("Basilience controls pumps, fans, lights, and equipment connected to electrical power and water. Treat the reservoir and controller area with the same care as any electrical/wet-environment equipment.")
                .steps(Arrays.asList(
                        "Keep the controller and its wiring away from standing water and spills.",
                        "Disconnect power before doing any physical maintenance on the reservoir or connected equipment.",
                        "Do not open the controller enclosure or attempt electrical repairs — contact whoever installed/maintains your system for hardware issues.",
                        "If in doubt about a reading or an actuator behaving unexpectedly, switch to Manual Mode and turn the affected equipment off from the app while you investigate."))
                .build());

        return list;
    }
}
