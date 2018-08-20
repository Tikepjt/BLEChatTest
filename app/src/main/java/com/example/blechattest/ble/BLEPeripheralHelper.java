package com.example.blechattest.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;


public class BLEPeripheralHelper {

    private static final String TAG = "BLEPeripheralHelper";

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothGattServer mGattServer;
    private Context mContext;
    private ArrayList<BLEAdvertiseCallback> mAdvListeners = new ArrayList<>();
    private ArrayList<BLEPeripheralChatEvents> mChatListeners = new ArrayList<>();

    private ArrayList<BluetoothDevice> mConnectedDevices;
    private Object mLock = new Object();
    private Handler mHandler = new Handler();

    private AcceptThread mInsecureAcceptThread;


    private static BLEPeripheralHelper instance = new BLEPeripheralHelper();

    private BLEPeripheralHelper() {
    }

    public static BLEPeripheralHelper getInstance() {
        if (instance == null) {
            synchronized (BLEPeripheralHelper.class) {
                if (instance == null) {
                    instance = new BLEPeripheralHelper();
                }
            }
        }
        return instance;
    }


    public void register(BLEAdvertiseCallback advListener) {
        mAdvListeners.add(advListener);
    }


    public void register(BLEPeripheralChatEvents chatEventListener) {
        mChatListeners.add(chatEventListener);
    }

    public void unregister(BLEAdvertiseCallback advListener) {
        mAdvListeners.remove(advListener);
    }


    public void unregister(BLEPeripheralChatEvents chatEventListener) {
        mChatListeners.remove(chatEventListener);
    }

    private enum NotifyAdvAction {
        NOTIFY_ADV_ACTION_INIT_FAILURE,
        NOTIFY_ADV_ACTION_INIT_SUCCESS,
        NOTIFY_ADV_ACTION_CLIENT_CONNECT,
        NOTIFY_ADV_ACTION_INFO,
        NOTIFY_ADV_ACTION_CONNECTION_ERROR,
    }

//    ;

    private enum NotifyChatAction {
        NOTIFY_CHAT_ACTION_MESSAGE,
        NOTIFY_CHAT_ACTION_INFO,
        NOTIFY_CHAT_ACTION_CLIENT_DISCONNECT,
        NOTIFY_CHAT_ACTION_CONNECTION_ERROR,
        NOTIFY_CHAT_ACTION_INIT_RFCOMM_SOCKET,
        NOTIFY_CHAT_ACTION_CONNECT_RFCOMM_SOCKET,
        NOTIFY_CHAT_ACTION_DATA_RFCOMM_SOCKET,
        NOTIFY_CHAT_ACTION_BLE_STREAM,
    }


    private void notifyAdvListeners(NotifyAdvAction action, Object data) {
        for (BLEAdvertiseCallback listener : mAdvListeners) {
            switch (action) {
                case NOTIFY_ADV_ACTION_INIT_SUCCESS:
                    listener.onInitSuccess();
                    break;
                case NOTIFY_ADV_ACTION_INIT_FAILURE:
                    listener.onInitFailure((String) data);
                    break;
                case NOTIFY_ADV_ACTION_CLIENT_CONNECT:
                    listener.onClientConnect((BluetoothDevice) data);
                    break;
                case NOTIFY_ADV_ACTION_INFO:
                    listener.onInfo((String) data);
                    break;
                case NOTIFY_ADV_ACTION_CONNECTION_ERROR:
                    listener.onError((String) data);
                    break;
            }
        }
    }


    private void notifyChatListeners(NotifyChatAction action, Object data) {
        for (BLEPeripheralChatEvents listener : mChatListeners) {
            switch (action) {
                case NOTIFY_CHAT_ACTION_MESSAGE:
                    listener.onMessage((String) data);
                    break;
                case NOTIFY_CHAT_ACTION_INFO:
                    listener.onInfo((String) data);
                    break;
                case NOTIFY_CHAT_ACTION_CLIENT_DISCONNECT:
                    listener.onClientDisconnect((BluetoothDevice) data);
                    break;
                case NOTIFY_CHAT_ACTION_CONNECTION_ERROR:
                    listener.onConnectionError((String) data);
                    break;
                case NOTIFY_CHAT_ACTION_INIT_RFCOMM_SOCKET:
                    listener.onInitRfcommSocket();
                    break;
                case NOTIFY_CHAT_ACTION_CONNECT_RFCOMM_SOCKET:
                    listener.onConnectRfcommSocket();
                    break;
                case NOTIFY_CHAT_ACTION_DATA_RFCOMM_SOCKET:
                    listener.onData((byte [])data);
                    break;
                case NOTIFY_CHAT_ACTION_BLE_STREAM:
                    listener.onDataStream((byte [])data);
            }
        }
    }


    public void init(Context context) {
        if (context == null) {
            notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_INIT_FAILURE, "Context cannot be null!!");
            return;
        }
        mContext = context;
        mConnectedDevices = new ArrayList<BluetoothDevice>();
        mBluetoothManager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_INIT_FAILURE, "Bluetooth not supported in this device!!");
            return;
        }

        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_INIT_FAILURE, "Bluetooth LE is not supported in this devices!!");
            return;
        }
        notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_INIT_SUCCESS, null);
    }

    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            Log.i(TAG, "onConnectionStateChange "
                    + BLEChatProfile.getStatusDescription(status) + " "
                    + BLEChatProfile.getStateDescription(newState));
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    mConnectedDevices.add(device);
                    notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_CLIENT_CONNECT, device);
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    mConnectedDevices.remove(device);
                    notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_CLIENT_DISCONNECT, device);
                }
            } else {
                String error = "Error:" + status;
                notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_CONNECTION_ERROR, error);
                notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_CONNECTION_ERROR, error);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                int requestId,
                                                int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.i(TAG, "onCharacteristicReadRequest " + characteristic.getUuid().toString());
            byte [] value;
            if (BLEChatProfile.CHARACTERISTIC_VERSION_UUID.equals(characteristic.getUuid())) {
                value = getCharacteristicVersionValue();
            } else if (BLEChatProfile.CHARACTERISTIC_DESC_UUID.equals(characteristic.getUuid())) {
                value = getCharacteristicDescValue();
            } else {
                value = new byte[0];
            }

            mGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
            Log.i(TAG, "onCharacteristicWriteRequest " + characteristic.getUuid().toString());
            int gatResult = BluetoothGatt.GATT_SUCCESS;
            try{
                if (BLEChatProfile.CHARACTERISTIC_MESSAGE_UUID.equals(characteristic.getUuid())) {
                    String msg = new String(value, "UTF-8");
                    notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_MESSAGE, msg);

                }else if(BLEChatProfile.CHARACTERISTIC_BLE_TRANSFER_UUID.equals(characteristic.getUuid())) {
                    notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_BLE_STREAM, value);
                }
            }catch (UnsupportedEncodingException ex) {
                    notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_CONNECTION_ERROR, ex.toString());
                    gatResult = BluetoothGatt.GATT_FAILURE;
            }finally{
                if (responseNeeded) {
                    mGattServer.sendResponse(device,
                            requestId,
                            gatResult,
                            offset,
                            value);
                }
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device,
                                             int requestId, BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (responseNeeded) {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value);
            }
        }
    };


    private void initService() {
        mGattServer = mBluetoothManager.openGattServer(mContext, mGattServerCallback);

        BluetoothGattService service = new BluetoothGattService(BLEChatProfile.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic messageCharacteristic =
                new BluetoothGattCharacteristic(BLEChatProfile.CHARACTERISTIC_MESSAGE_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor messageDesc = new BluetoothGattDescriptor(BLEChatProfile.DESCRIPTOR_MESSAGE_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ);
        messageCharacteristic.addDescriptor(messageDesc);

        BluetoothGattCharacteristic versionCharacteristic =
                new BluetoothGattCharacteristic(BLEChatProfile.CHARACTERISTIC_VERSION_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ);

        BluetoothGattCharacteristic descriptionCharacteristic =
                new BluetoothGattCharacteristic(BLEChatProfile.CHARACTERISTIC_DESC_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ);

        BluetoothGattCharacteristic transferCharacteristic =
                new BluetoothGattCharacteristic(BLEChatProfile.CHARACTERISTIC_RFCOMM_TRANSFER_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor transferRateDesc = new BluetoothGattDescriptor(BLEChatProfile.DESCRIPTOR_RFCOMM_TRANSFER_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ);
        transferCharacteristic.addDescriptor(transferRateDesc);

        BluetoothGattCharacteristic transferBleCharacteristic =
                new BluetoothGattCharacteristic(BLEChatProfile.CHARACTERISTIC_BLE_TRANSFER_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor transferBleDesc = new BluetoothGattDescriptor(BLEChatProfile.DESCRIPTOR_BLE_TRANSFER_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ);
        transferBleCharacteristic.addDescriptor(transferBleDesc);


        service.addCharacteristic(descriptionCharacteristic);
        service.addCharacteristic(versionCharacteristic);
        service.addCharacteristic(messageCharacteristic);
        service.addCharacteristic(transferCharacteristic);
        service.addCharacteristic(transferBleCharacteristic);


        mGattServer.addService(service);
    }


    public void initRfcommService() {
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
        sendTransferReady();
        notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_INIT_RFCOMM_SOCKET, null);
    }


    public void stopRfcommService(){
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.end();
        }
        mInsecureAcceptThread = null;
    }


    private void advertiseService() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(BLEChatProfile.SERVICE_UUID))
                .build();


        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
    }

    public void startAdvertising() {

        if (!mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            postStatusMessage("Bluetooth LE Peripheral mode is not supported in this device!!");
            return;
        }

        if (mBluetoothLeAdvertiser == null) {
            mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            if (mBluetoothLeAdvertiser == null) {
                postStatusMessage("Error initializing BLE Advertiser");
                return;
            }
        }

        initService();
        advertiseService();
    }

    public void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null)
            return;

        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }


    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "Peripheral Advertise Started.");
            postStatusMessage("GATT Server Ready");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "Peripheral Advertise Failed: " + errorCode);
            postStatusMessage("GATT Server Error " + errorCode);
        }
    };

    private void postStatusMessage(final String message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                notifyAdvListeners(NotifyAdvAction.NOTIFY_ADV_ACTION_INFO, message);
            }
        });
    }

    private byte[] getCharacteristicVersionValue() {
        synchronized (mLock) {
            return BLEChatProfile.getVersion().getBytes();
        }
    }

    private byte[] getCharacteristicDescValue() {
        synchronized (mLock) {
            return BLEChatProfile.getDescription().getBytes();
        }
    }

    public void send(String msg) {
        for (BluetoothDevice device : mConnectedDevices) {
            BluetoothGattCharacteristic msgCharacteristic = mGattServer.getService(BLEChatProfile.SERVICE_UUID)
                    .getCharacteristic(BLEChatProfile.CHARACTERISTIC_MESSAGE_UUID);
            msgCharacteristic.setValue(msg);
            mGattServer.notifyCharacteristicChanged(device, msgCharacteristic, false);
        }
    }

    public synchronized void sendStream(){
        notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_INFO, "Not tested yet!");
        byte[] randomBytes = new byte[512];
        (new Random()).nextBytes(randomBytes);

        for (BluetoothDevice device : mConnectedDevices) {
            BluetoothGattCharacteristic transferCharacteristic = mGattServer.getService(BLEChatProfile.SERVICE_UUID)
                    .getCharacteristic(BLEChatProfile.CHARACTERISTIC_BLE_TRANSFER_UUID);
            transferCharacteristic.setValue(randomBytes);
            mGattServer.notifyCharacteristicChanged(device, transferCharacteristic, false);
        }
    }


    private void sendTransferReady(){
        for (BluetoothDevice device : mConnectedDevices) {
            BluetoothGattCharacteristic transferCharacteristic = mGattServer.getService(BLEChatProfile.SERVICE_UUID)
                    .getCharacteristic(BLEChatProfile.CHARACTERISTIC_RFCOMM_TRANSFER_UUID);
            String macAddress = android.provider.Settings.Secure.getString(mContext.getContentResolver(), "bluetooth_address");
            transferCharacteristic.setValue(macAddress);
            mGattServer.notifyCharacteristicChanged(device, transferCharacteristic, false);
        }
    }


    private static final String NAME_SECURE = "BluetoothLEChatSecure";
    private static final String NAME_INSECURE = "BluetoothLEChatInsecure";
    private static final UUID MY_UUID_SECURE =
            UUID.fromString("caa8f277-6b87-49fb-a11b-ab9c9dacbd44");
    private static final UUID MY_UUID_INSECURE =
            UUID.fromString("83769a57-e930-4496-8ece-fec16420c77c");

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;
        boolean mEnd = false;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            try {
                if (secure) {
                    tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                            MY_UUID_SECURE);
                } else {
                    tmp = mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_CONNECTION_ERROR, "Socket Type: " + mSocketType + "listen() failed");
            }
            mmServerSocket = tmp;
        }

        public void run() {
            com.example.blechattest.common.logger.Log.d(TAG, "Socket Type: " + mSocketType +
                    "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;
            try {

                socket = mmServerSocket.accept();
                notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_CONNECT_RFCOMM_SOCKET, null);
                int bytesRead = 0;
                do
                {
                    InputStream is = socket.getInputStream();
                    byte[] buffer = new byte[1024];
                    bytesRead = is.read(buffer);
                    notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_DATA_RFCOMM_SOCKET, buffer);
                }while(bytesRead != 0 && !mEnd);

            } catch (IOException e) {
                notifyChatListeners(NotifyChatAction.NOTIFY_CHAT_ACTION_CONNECTION_ERROR, "Socket Type: " + mSocketType + "accept() failed");
            }
        }

        public void end(){
            mEnd = true;
        }
    }
}

