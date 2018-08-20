package com.example.blechattest.bluetoothchat;

public interface Constants {

    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    public static final int MESSAGE_TOAST_FAST = 6;

    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";
    public static enum ROLE {
        SERVER,
        CLIENT
    }
}
