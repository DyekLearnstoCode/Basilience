package com.example.basilience;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.util.Log;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;

public final class LocalProvisioningClient {
    private static final String TAG = "Provisioning";
    private static final String ESP32_HOST = "192.168.4.1";
    private static final String STATUS_URL = "http://192.168.4.1/status";
    private static final String SETUP_URL = "http://192.168.4.1/setup";

    private LocalProvisioningClient() {
    }

    public static final class ProvisioningStatus {
        public final String status;
        public final boolean connected;
        public final String ssid;
        public final String rawBody;
        ProvisioningStatus(String status, boolean connected, String ssid, String rawBody) {
            this.status = status;
            this.connected = connected;
            this.ssid = ssid;
            this.rawBody = rawBody;
        }
    }

    public static boolean isSetupApReachable(Context context) {
        HttpURLConnection conn = null;
        try {
            conn = openEsp32Connection(context, STATUS_URL);
            Log.i(TAG, "[Provisioning] GET " + STATUS_URL);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7500);
            int responseCode = conn.getResponseCode();
            Log.i(TAG, "[Provisioning] Response code=" + responseCode);
            return responseCode == 200;
        } catch (Exception error) {
            Log.w(TAG, "[Provisioning] GET failed: " + error.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static int sendCredentials(Context context, String ssid, String password) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openEsp32Connection(context, SETUP_URL);
            Log.i(TAG, "[Provisioning] POST " + SETUP_URL);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String postData = "ssid=" + URLEncoder.encode(ssid, "UTF-8") +
                    "&password=" + URLEncoder.encode(password, "UTF-8");

            OutputStream os = conn.getOutputStream();
            os.write(postData.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "[Provisioning] POST response code=" + responseCode);
            return responseCode;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static ProvisioningStatus getProvisioningStatus(Context context) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = openEsp32Connection(context, STATUS_URL);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) throw new IllegalStateException("Status HTTP " + conn.getResponseCode());
            InputStream input = conn.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            input.close();
            String body = output.toString("UTF-8");
            Log.i(TAG, "[PROVISION-TRACE] Android /status HTTP=200 body=" + body);
            org.json.JSONObject json = new org.json.JSONObject(body);
            ProvisioningStatus status = new ProvisioningStatus(json.optString("status", "setup_mode"),
                    json.optBoolean("connected", false), json.optString("ssid", ""), body);
            Log.i(TAG, "[PROVISION-TRACE] parsed status=" + status.status + " connected=" + status.connected + " ssid=" + status.ssid);
            return status;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openEsp32Connection(Context context, String url) throws Exception {
        Network setupNetwork = findSetupWifiNetwork(context);
        URL endpoint = new URL(url);

        if (setupNetwork == null) {
            throw new IllegalStateException(
                "Basilience-Setup Wi-Fi network is not connected or its gateway is not 192.168.4.1");
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean processGloballyBound = cm != null && cm.getBoundNetworkForProcess() != null;
        Log.i(TAG, "[Provisioning] Local request network gateway=" + ESP32_HOST);
        Log.i(TAG, "[Provisioning] Local request uses Network.openConnection only");
        Log.i(TAG, "[Provisioning] Process globally bound=" + processGloballyBound);
        return (HttpURLConnection) setupNetwork.openConnection(endpoint);
    }

    private static Network findSetupWifiNetwork(Context context) {
        Log.i(TAG, "[Provisioning] Searching for Wi-Fi network");
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            Log.w(TAG, "[Provisioning] No Wi-Fi network with gateway " + ESP32_HOST);
            return null;
        }

        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }

            LinkProperties linkProperties = cm.getLinkProperties(network);
            if (hasEsp32Gateway(linkProperties)) {
                Log.i(TAG, "[Provisioning] Using Basilience setup network");
                return network;
            }
        }

        Log.w(TAG, "[Provisioning] No Wi-Fi network with gateway " + ESP32_HOST);
        return null;
    }

    private static boolean hasEsp32Gateway(LinkProperties linkProperties) {
        if (linkProperties == null) return false;

        for (RouteInfo route : linkProperties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway != null) {
                Log.i(TAG, "[Provisioning] Candidate network gateway=" + gateway.getHostAddress());
            }
            if (gateway != null && ESP32_HOST.equals(gateway.getHostAddress())) {
                return true;
            }
        }

        return false;
    }
}
