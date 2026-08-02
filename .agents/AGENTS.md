# Basilience Project-Scoped Rules & Design Decisions

These guidelines represent specific design decisions and requirements provided by the user. They must be followed in any future refactoring or development:

## 1. Dosing & Nutrient Pump Exclusivity
* **Rule**: The validation/safety check for `GROW_PUMP` and `BLOOM_PUMP` (Nutrient A and Nutrient B) must be grouped under a single shared condition. They operate as a single unified nutrient dosing pair, so their active status should not trigger conflict checks against each other.

## 2. Manual Mode Behavior
* **Rule**: Enabling `manualMode` (setting `commands/manualMode` to `true`) must **only** allow manual overrides for individual actuator pins. 
* **Constraint**: Enabling manual mode **must not** disable the automatic state machine, background climate checks, temperature-based cooling overrides, grow light schedules, or automatic safety thresholds. The rest of the FSM workflow must remain active and functional during manual override mode.
