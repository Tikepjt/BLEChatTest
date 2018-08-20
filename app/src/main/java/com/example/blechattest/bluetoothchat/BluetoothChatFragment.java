package com.example.blechattest.bluetoothchat;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.blechattest.R;
import com.example.blechattest.ble.BLEAdvertisingActivity;
import com.example.blechattest.ble.BLECentralChatEvents;
import com.example.blechattest.ble.BLECentralHelper;
import com.example.blechattest.ble.BLEChatEvents;
import com.example.blechattest.ble.BLEDiscoveringActivity;
import com.example.blechattest.ble.BLEMode;
import com.example.blechattest.ble.BLEPeripheralChatEvents;
import com.example.blechattest.ble.BLEPeripheralHelper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Semaphore;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

public class BluetoothChatFragment extends Fragment {

    private static final String TAG = "BluetoothChatFragment";
    private BLEMode mBleMode = BLEMode.CENTRAL;

    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;
    private static final int BLE_REQUEST_CONNECT_DEVICE = 11;
    private static final int BLE_REQUEST_DEVICE_CONNECTING = 12;
    private static final int PICK_IMAGE = 21;

    private ListView mConversationView;
    private EditText mOutEditText;
    private Button mSendButton;

    private String mConnectedDeviceName = null;

    private ArrayAdapter<String> mConversationArrayAdapter;

    private StringBuffer mOutStringBuffer;

    private BluetoothAdapter mBluetoothAdapter = null;

    private BluetoothChatService mChatService = null;

    private ProgressDialog mProgressBar = null;
    private int mProgressBarStatus = 0;

    private StreamThread mStreamThread = null;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null) {
            FragmentActivity activity = getActivity();
            Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            activity.finish();
        }

        setupProgressBar(getContext());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        } else if (mChatService == null) {
            setupChat();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            mChatService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mChatService != null) {
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                mChatService.start();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mConversationView = view.findViewById(R.id.in);
        mOutEditText = view.findViewById(R.id.edit_text_out);
        mSendButton = view.findViewById(R.id.button_send);
    }

    private void setupChat() {
        Log.d(TAG, "setupChat()");

        mConversationArrayAdapter = new ArrayAdapter<String>(getActivity(), R.layout.message);

        mConversationView.setAdapter(mConversationArrayAdapter);

        mOutEditText.setOnEditorActionListener(mWriteListener);

        mSendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View view = getView();
                if (null != view) {
                    TextView textView = view.findViewById(R.id.edit_text_out);
                    String message = textView.getText().toString();
                    processOutgoingMsg(message);
                }
            }
        });

        mChatService = new BluetoothChatService(getActivity(), mHandler);

        mOutStringBuffer = new StringBuffer("");
    }

    /**
     * Makes this device discoverable.
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    private synchronized void processOutgoingMsg(String message){
        if(message.startsWith("/")){
            String[] tokens = message.split(" ", 2);
            if(tokens[0].compareTo("/transfertest") == 0){
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        BLECentralHelper.getInstance().changeMtu(512);
                    }
                }, 2100);
                return;
            }else if(tokens[0].compareTo("/transfer") == 0){
                sendStream();
                return;
            }
        }
        sendMessage(message);
    }

    private void sendStream(){
        if(mBleMode == BLEMode.PERIPHERAL ){
            BLEPeripheralHelper.getInstance().sendStream();
        }else if(mBleMode == BLEMode.CENTRAL){
            BLECentralHelper.getInstance().sendData();
        }
    }

    private synchronized void sendMessage(String message) {
        if(mBleMode != BLEMode.NONE)
            sendMessageViaBLE(message);
        else
            sendMessageViaClassicBT(message);
    }

    private synchronized void sendMessageViaClassicBT(String message) {
        if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        if (message.length() > 0) {
            byte[] send = message.getBytes();
            mChatService.write(send);

            mOutStringBuffer.setLength(0);
            mOutEditText.setText(mOutStringBuffer);
        }
    }

    private TextView.OnEditorActionListener mWriteListener
            = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                String message = view.getText().toString();
                sendMessage(message);
            }
            return true;
        }
    };

    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }

    private void answerBack(String msg){
        final String mMsg = msg;
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "ConversationThread::run()");
                String response = (mMsg.equalsIgnoreCase("PING") ? "PONG" : "PING");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                sendMessage(response);
            }
        }, 2000);
    }


    private void showIncomingMessage(String msg){
        mHandler.obtainMessage(Constants.MESSAGE_READ, msg.length(), -1, msg.getBytes())
                .sendToTarget();
    }

    private void showOutgoingMessage(String msg){
        mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, msg.getBytes())
                .sendToTarget();
    }

    private void showInfo(String info){
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, info);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        Log.i(TAG, info);
    }

    private void showStatus(int status){
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, status, -1)
                .sendToTarget();
    }

    private void showConnectedName(String name){
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.DEVICE_NAME, name);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(BluetoothChatService.STATE_CONNECTED);
    }

    private void setState(int newState){
        switch (newState) {
            case BluetoothChatService.STATE_CONNECTED:
                setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                break;
            case BluetoothChatService.STATE_CONNECTING:
                setStatus(R.string.title_connecting);
                break;
            case BluetoothChatService.STATE_LISTEN:
            case BluetoothChatService.STATE_NONE:
                setStatus(R.string.title_not_connected);
                break;
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    setState(msg.arg1);
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    String writeMessage = new String(writeBuf);
                    mConversationArrayAdapter.add("Me:  " + writeMessage);
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage);
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };



    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(getActivity(), R.string.bt_not_enabled_leaving,
                            Toast.LENGTH_SHORT).show();
                    getActivity().finish();
                }
                break;
            case BLE_REQUEST_CONNECT_DEVICE:
                if( resultCode == Activity.RESULT_OK) {
                    connectBleDevice(data);
                }
                break;
            case BLE_REQUEST_DEVICE_CONNECTING:
                if( resultCode == Activity.RESULT_OK){
                    bleDeviceConnecting(data);
                }
                break;
            case PICK_IMAGE:
                if( resultCode == Activity.RESULT_OK){
                    sendFile(data);
                }
        }
    }

    private void connectDevice(Intent data, boolean secure) {
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        mBleMode = BLEMode.NONE;
        mChatService.connect(device, secure);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.bluetooth_chat, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.secure_connect_scan: {
                if(mChatService != null)
                    mChatService.start();
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
                return true;
            }
            case R.id.insecure_connect_scan: {
                if(mChatService != null)
                    mChatService.start();
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
                return true;
            }
            case R.id.discoverable: {
                ensureDiscoverable();
                return true;
            }
            case R.id.ble_advertise: {
                startAdvertising();
                return true;
            }
            case R.id.ble_discover: {
                startScanning();
                return true;
            }

        }
        return false;
    }

    public BluetoothChatService getChatService(){
        return mChatService;
    }

    private void startAdvertising() {
        Intent advertisementIntent = new Intent(getContext(), BLEAdvertisingActivity.class);
        startActivityForResult(advertisementIntent, BLE_REQUEST_DEVICE_CONNECTING);
    }

    private void startScanning(){
        Intent scanningIntent = new Intent(getActivity(), BLEDiscoveringActivity.class);
        startActivityForResult(scanningIntent, BLE_REQUEST_CONNECT_DEVICE);
    }

    private synchronized void sendMessageViaBLE(String message) {
        if (message.length() > 0) {
            if(mBleMode == BLEMode.PERIPHERAL){
                BLEPeripheralHelper.getInstance().send(message);
            }else if(mBleMode == BLEMode.CENTRAL){
                BLECentralHelper.getInstance().send(message);
            }
            showOutgoingMessage(message);
            mOutStringBuffer.setLength(0);
            mOutEditText.setText(mOutStringBuffer);
        }
    }

    private void sendFile(Intent data){
        Uri uri = data.getData();
        BLECentralHelper.getInstance().sendFile(uri);
    }


    private void connectBleDevice(Intent data){
        mBleMode = BLEMode.CENTRAL;
        if(mChatService != null)
            mChatService.stop();
        mConnectedDeviceName = data.getExtras().getString(BLEDiscoveringActivity.EXTRA_DEVICE_NAME);
        String address = data.getExtras().getString(BLEDiscoveringActivity.EXTRA_DEVICE_ADDRESS);
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        showStatus(BluetoothChatService.STATE_CONNECTING);
        BLECentralHelper.getInstance().connect(this.getContext(), device, mBLEChatEvents);
    }


    private BLECentralChatEvents mBLEChatEvents = new BLECentralChatEvents() {
        private Object mLock = new Object();
        @Override
        public void onVersion(String version) {
            synchronized (mLock){
                showInfo("Version: " + version);
            }
        }

        @Override
        public void onDescription(String description) {
            synchronized (mLock){
                showIncomingMessage("Description: " + description);
            }
        }

        @Override
        public void onMessage(String msg) {
            synchronized (mLock){
                processIncomingMsg(msg);
            }
        }

        @Override
        public void onInfo(String info){
            synchronized (mLock) {
                showInfo(info);
            }
        }

        @Override
        public void onConnect(){
            synchronized (mLock){
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        sendMessage("/name Z3C");
                        showConnectedName(mConnectedDeviceName);
                        showStatus(BluetoothChatService.STATE_CONNECTED);
                    }
                }, 2000);
            }
        }

        @Override
        public void onDisconnect(){
            synchronized (mLock) {
                Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
                Bundle bundle = new Bundle();
                bundle.putString(Constants.TOAST, new String("[!] Disconnected"));
                msg.setData(bundle);
                mHandler.sendMessage(msg);

                showStatus(BluetoothChatService.STATE_NONE);
                if(mProgressBar!=null){
                    mProgressBar.dismiss();
                    mProgressBar.hide();
                }

            }
        }

        @Override
        public void onConnectionError(String error){
            synchronized (mLock){
                if(mStreamThread!=null)
                    mStreamThread.end();

                mProgressBar.dismiss();
                mProgressBar.cancel();

                showStatus(BluetoothChatService.STATE_NONE);
                showInfo("[!] Error : " + error);

            }
        }

        @Override
        public void onRfcommConnect(){
            synchronized (mLock) {
                Intent intent = new Intent();
                intent.setType("image/*");
                intent.setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(intent, "Select Picture"), PICK_IMAGE);
            }
        }

        @Override
        public void onData(byte [] data){
            synchronized (mLock){
                showInfo("Not implemented yet");
            }
        }

        private int mLastLength = 0;
        @Override
        public void onDataStream(byte[] data){
            synchronized (mLock){
                if( mLastLength != data.length ) {
                    showInfo("Received " + data.length + " bytes via BLE!");
                    mLastLength = data.length;
                }
            }
        }

        @Override
        public void onStreamSent(int status){
            synchronized (mLock) {
                if (status == BLEChatEvents.SENT_SUCCEED) {
                    mStreamThread.nextMessage();
                } else {
                    mStreamThread.end();
                }
            }
        }


        @Override
        public void onMtuChanged(int status, int newMtu){
            synchronized (mLock) {
                if (status == BLECentralChatEvents.MTU_CHANGE_SUCCEED) {
                    showInfo("MTU changed to " + newMtu);
                } else {
                    showInfo("Error changing MTU. Falling back to " +  newMtu + " ...");
                }

                mStreamThread = new StreamThread();
                mStreamThread.start();
            }
        }
    };


    private void bleDeviceConnecting(Intent data){
        mBleMode = BLEMode.PERIPHERAL;
        if(mChatService != null)
            mChatService.stop();

        showStatus(BluetoothChatService.STATE_CONNECTED);
        BLEPeripheralHelper.getInstance().register(mBlePeripheralChatEvents);
    }
    private BLEPeripheralChatEvents mBlePeripheralChatEvents = new BLEPeripheralChatEvents() {
        private Object mLock = new Object();

        @Override
        public void onClientDisconnect(BluetoothDevice device) {
            synchronized (mLock){
                showInfo(device.getName() + " disconnected");
                setStatus(R.string.title_not_connected);
            }
        }

        @Override
        public void onMessage(String msg) {
            synchronized (mLock){
                processIncomingMsg(msg);
            }
        }

        @Override
        public void onInfo(String info) {
            synchronized (mLock){
                showInfo(info);
            }
        }

        @Override
        public void onConnectionError(String error) {
            synchronized (mLock){
                if(mProgressBar != null){
                    mProgressBar.dismiss();
                    mProgressBar.hide();
                }

                showInfo("[!] Error : " + error);
            }
        }

        @Override
        public void onInitRfcommSocket(){
            synchronized (mLock) {
                ensureDiscoverable();
                showInfo("RFCOMM: Socket listening...");
            }
        }

        @Override
        public void onConnectRfcommSocket(){
            synchronized (mLock){
                showInfo("RFCOMM: Client connected");
            }
        }

        @Override
        public void onData(byte [] data){
            synchronized (mLock) {
                save2File(data);
            }
        }

        private int mLastLength = 0;
        private long mStartingTime = 0;
        private long mBytesPerSec = 0;
        @Override
        public void onDataStream(byte[] data){
            synchronized (mLock){
                if( mLastLength != data.length ) {
                    showInfo("Received " + data.length + " bytes via BLE!");
                    mLastLength = data.length;
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mProgressBar.show();
                        }
                    });
                }

                long now = System.currentTimeMillis();
                if( mStartingTime == 0 ){
                    mStartingTime = now;
                }
                if( mStartingTime + 1000 <= now ){
                    showInfo( mBytesPerSec + " B/s");
                    mBytesPerSec = mStartingTime = 0;
                }

                mBytesPerSec += data.length;
                mProgressBarStatus += data.length;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mProgressBar.setProgress(mProgressBarStatus);
                    }
                });

            }
        }

        @Override
        public void onStreamSent(int status){
            if(status == BLEChatEvents.SENT_SUCCEED){
                mStreamThread.nextMessage();
            }else{
                mStreamThread.end();
                showInfo("Error sending data!!");
            }
        }

    };

    private void processIncomingMsg(String msg){
        if(msg.startsWith("/")){
            String[] tokens = msg.split(" ", 2);
            if(tokens[0].compareTo("/name") == 0){
                showConnectedName(tokens[1]);
            }else if(tokens[0].compareTo("/send") == 0){
                transferData();
            }
        }else{
            showIncomingMessage(msg);
        }
    }

    private void transferData(){
        if(mBleMode == BLEMode.PERIPHERAL ) {
            // 1st - unleash RFCOMM Socket machinery...
            BLEPeripheralHelper.getInstance().initRfcommService();
            showInfo("Initializing RFCOMM socket...");
        }
    }

    private void save2File(byte [] data){
        File filepath = Environment.getExternalStorageDirectory();
        String filePathName = filepath.getAbsolutePath() + "/BluetoothBLEChat/";
        File dir = new File(filePathName);
        dir.mkdirs();
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePathName + "data.jpg"));
            bos.write(data);
            bos.flush();
            bos.close();
        }catch (FileNotFoundException ex){
            showInfo(ex.toString());
        }catch (IOException ex){
            showInfo(ex.toString());
        }finally {
            BLEPeripheralHelper.getInstance().stopRfcommService();
        }
    }


    private void setupProgressBar(Context cxt) {
        if(mProgressBar == null) {
            mProgressBar = new ProgressDialog(cxt);
            mProgressBar.setCancelable(true);
            mProgressBar.setMessage("Transferring data ...");
            mProgressBar.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mProgressBar.setMax(1024 * 1024);
        }
    }

    private class StreamThread extends Thread {
        private boolean mEnd = false;
        private Semaphore mSemaphore = new Semaphore(0,true);
        StreamThread(){ }

        private void updateProgressBar(final int increment){
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mProgressBar.setProgress(increment);
                }
            });
        }

        private void hideProgressBar(){
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mProgressBar.dismiss();
                    mProgressBar.hide();
                }
            });
        }

        private void showProgressBar(){
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mProgressBar.show();
                }
            });
        }


        private void sendViaLoop(){
            int iMtu = BLECentralHelper.getInstance().getMtu();
            long startTime = System.currentTimeMillis();
            for(int iBytesSent = 0;!mEnd && iBytesSent < 1024 * 1024 ; iBytesSent += iMtu ) {
                processOutgoingMsg("/transfer");
                updateProgressBar(iBytesSent);
            }
            long difference = (System.currentTimeMillis() - startTime) / 1000;
            showInfo("1 MB took " + difference + " secs to complete");
        }

        private void sendViaEvent(){
            int iBytesSent = 0;
            int iMtu = BLECentralHelper.getInstance().getMtu();
            while(!mEnd && iBytesSent < 1024 * 1024) {
                processOutgoingMsg("/transfer");
                iBytesSent += iMtu;
                updateProgressBar(iBytesSent);
                try {
                    mSemaphore.acquire();
                }catch (InterruptedException ex){
                    mBLEChatEvents.onConnectionError("Interrupted while in a semaphore!!");
                }
            }
        }

        public void nextMessage(){
            mSemaphore.release();
        }

        public void run(){
            showProgressBar();
            try {
                Thread.sleep(2000);
                sendViaEvent();
            }catch (InterruptedException ex){
                mBLEChatEvents.onConnectionError("Interrupted while sleeping!!!");
            }finally {
                hideProgressBar();
            }
        }

        public void end(){
            mEnd = true;
        }
    }

}
