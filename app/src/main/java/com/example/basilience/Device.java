package com.example.basilience;

public class Device {
    private String device_name;
    private String owner_id;
    private String status;

    // IMPORTANT: Kailangan ng Firebase ng empty constructor na ganito
    public Device() {
    }

    public Device(String device_name, String owner_id, String status) {
        this.device_name = device_name;
        this.owner_id = owner_id;
        this.status = status;
    }

    // Getters and Setters (Para mabasa at ma-update ang data)
    public String getDevice_name() {
        return device_name;
    }

    public void setDevice_name(String device_name) {
        this.device_name = device_name;
    }

    public String getOwner_id() {
        return owner_id;
    }

    public void setOwner_id(String owner_id) {
        this.owner_id = owner_id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}