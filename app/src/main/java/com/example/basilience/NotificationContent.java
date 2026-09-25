package com.example.basilience;

import java.util.Map;

/**
 * What to show for an incoming push message, resolved from its fields.
 *
 * The backend sends the title and body twice (the notification payload and
 * the data map), but either can arrive missing or blank. A missing field
 * used to overwrite the default with null and the whole message was then
 * dropped, so a real alert never appeared. Each field now falls back through:
 * the notification payload, the data map, then text derived only from the
 * message's own "type". Nothing is invented: the generic wording claims no
 * more than "an alert arrived", and the connectivity wording just restates
 * the type (unreachable / back online).
 *
 * Plain Java on purpose (no Android types).
 */
final class NotificationContent {

    static final String GENERIC_TITLE = "Device Alert";
    static final String GENERIC_BODY = "System alert received";

    final String title;
    final String body;

    private NotificationContent(String title, String body) {
        this.title = title;
        this.body = body;
    }

    /**
     * Returns null only when the message carries nothing to show or route on:
     * no title, no body, and no type / notification id / device id.
     */
    static NotificationContent resolve(String payloadTitle, String payloadBody, Map<String, String> data) {
        String type = data == null ? null : data.get("type");

        String title = firstNonBlank(payloadTitle, data == null ? null : data.get("title"));
        String body = firstNonBlank(payloadBody, data == null ? null : data.get("body"));

        boolean hasStructure = !isBlank(type)
                || (data != null && (!isBlank(data.get("notificationId")) || !isBlank(data.get("deviceId"))));
        if (title == null && body == null && !hasStructure) return null;

        if (title == null) title = derivedTitle(type);
        if (body == null) body = derivedBody(type);
        return new NotificationContent(title, body);
    }

    private static String derivedTitle(String type) {
        if ("OFFLINE_ALERT".equalsIgnoreCase(type)) return "Basilience Device Unreachable";
        if ("ONLINE_RECOVERY".equalsIgnoreCase(type)) return "Basilience Device Back Online";
        return GENERIC_TITLE;
    }

    private static String derivedBody(String type) {
        if ("OFFLINE_ALERT".equalsIgnoreCase(type)) return "Basilience cannot communicate with the device.";
        if ("ONLINE_RECOVERY".equalsIgnoreCase(type)) return "Basilience is communicating with the device again.";
        return GENERIC_BODY;
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) return first;
        if (!isBlank(second)) return second;
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
