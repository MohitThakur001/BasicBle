package com.apogee.mylibrary.Fragments;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.apogee.mylibrary.CommunicationLibrary.SerialListener;
import com.apogee.mylibrary.CommunicationLibrary.SerialService;
import com.apogee.mylibrary.CommunicationLibrary.SerialSocket;
import com.apogee.mylibrary.R;
import com.apogee.mylibrary.SQlite.DBHelper;
import com.apogee.mylibrary.SQlite.Model;
import com.apogee.mylibrary.Utils.TextUtil;

import java.util.ArrayDeque;
import java.util.List;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True } // Group of constants
    private String deviceAddress;  // String for saving the device macAddress
    private SerialService service; // Calling the serial service class
    private TextView receiveText; // Text View for saving the response coming from ble device
    private TextView sendText; // Text View for send the request ble device
    private TextUtil.HexWatcher hexWatcher; // Text watcher
    private Connected connected = Connected.False;

    // Boolean values
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf; // String for new line

    /** Initial Creation of the fragment
     *  We are saving the device address in string received from device fragment
     */

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    /** Called just before a fragment is destroyed
     *  We are calling the disconnect method and stopping the service
     */

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    /** Called when the fragment is visible to user
     *  We are calling the service.attach method and starting the service
     */

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    /** Called when the fragment is not visible to user
     *  We are calling the service.detach method
     */

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    /**
     * Called when a fragment is first attached to its activity.
     */

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    /**
     * Called when the fragment is no longer attached to its activity.
     */

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }


    /** Called when the fragment comes from or goes to foreground state
     * we are calling the connect method to establish the connection
     */

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    /**
     *
     *
     * @param name The concrete component name of the service that has
     * been connected.
     *
     * @param binder The IBinder of the Service's communication channel,
     * which you can now make calls on.
     */

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    /**
     * @param name The concrete component name of the service whose
     * connection has been lost.
     */

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /**
     * Inflating the xml file and showing the response received from the BLE device
     * also we are sending the request to ble device
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));



/** SQLITE DATABASE IMPLEMENTATION
 *
 */

        String result = "Raw On";
        DBHelper db = new DBHelper(getActivity()); //creating obj of handler class
        //creating a result
        Model model = new Model();
        model.setGetResp(result);
        model.setDate(java.time.LocalDate.now().toString());
        db.addResult(model);

        List<Model> bmiModelList = db.getAllResult();

        TextView id = view.findViewById(R.id.id);
        TextView date = view.findViewById(R.id.date);
        TextView response = view.findViewById(R.id.response);
        Button sendSQ = view.findViewById(R.id.sendSQ);

        String strId = String.valueOf(bmiModelList.get(0).getId());
        String strDate = String.valueOf(bmiModelList.get(0).getDate());
        String strResponse = String.valueOf(bmiModelList.get(0).getGetResp());

        id.setText(strId);
        date.setText(strDate);
        response.setText(strResponse);

        sendSQ.setOnClickListener(view1 -> send(strResponse));


            return view;
    }

    /**
     * Called for connecting the device using mac address
     */

    public void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    /**
     * Called for disconnecting the device
     */

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    /**
     * Called to send the request to ble device
     * using the SpannableStringBuilder for the color and nextLine att.
     */

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;

                msg = str;
                data = (str + newline).getBytes();

            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    /**
     * Called to receive the response to ble device
     * using the SpannableStringBuilder for the color and nextLine att.
     */

    private void receive(@NonNull ArrayDeque<byte[]> datas) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {

                String msg = new String(data);
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, "\n");
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if(spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }

                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }
        receiveText.append(spn);
    }

    /**
     * Used for the connection status
     */

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /**
     *  Serial Listener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
