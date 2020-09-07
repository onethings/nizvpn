/*
 * Copyright (c) 2020 Mochamad Nizwar Syafuan
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package id.nizwar.open_nizvpn;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNThread;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;

public class MainActivity extends FlutterActivity {
    private MethodChannel vpnControlMethod;
    private EventChannel vpnControlEvent;
    private EventChannel vpnStatusEvent;
    private EventChannel.EventSink vpnStageSink;
    private EventChannel.EventSink vpnStatusSink;

    private static final String EVENT_CHANNEL_VPN_STAGE = "id.nizwar.nvpn/vpnstage";
    private static final String EVENT_CHANNEL_VPN_STATUS = "id.nizwar.nvpn/vpnstatus";
    private static final String METHOD_CHANNEL_VPN_CONTROL = "id.nizwar.nvpn/vpncontrol";
    private static final int VPN_REQUEST_ID = 1;
    private static final String TAG = "NVPN";

    private String config = "", username = "", password = "", name = "";

    @Override
    public void finish() {
        vpnControlEvent.setStreamHandler(null);
        vpnControlMethod.setMethodCallHandler(null);
        vpnStatusEvent.setStreamHandler(null);
        super.finish();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        LocalBroadcastManager.getInstance(this).registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra("state");
                if (status != null) setStatus(status);

                if (vpnStatusSink != null) {
                    try {
                        String duration = intent.getStringExtra("duration");
                        String lastPacketReceive = intent.getStringExtra("lastPacketReceive");
                        String byteIn = intent.getStringExtra("byteIn");
                        String byteOut = intent.getStringExtra("byteOut");

                        if (duration == null) duration = "00:00:00";
                        if (lastPacketReceive == null) lastPacketReceive = "0";
                        if (byteIn == null) byteIn = " ";
                        if (byteOut == null) byteOut = " ";

                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("duration", duration);
                        jsonObject.put("last_packet_receive", lastPacketReceive);
                        jsonObject.put("byte_in", byteIn);
                        jsonObject.put("byte_out", byteOut);

                        vpnStatusSink.success(jsonObject.toString());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }, new IntentFilter("connectionState"));
        super.onCreate(savedInstanceState);
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        vpnControlEvent = new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), EVENT_CHANNEL_VPN_STAGE);
        vpnControlEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                vpnStageSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                vpnStageSink.endOfStream();
            }
        });

        vpnStatusEvent = new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), EVENT_CHANNEL_VPN_STATUS);
        vpnStatusEvent.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                vpnStatusSink = events;
            }

            @Override
            public void onCancel(Object arguments) {

            }
        });

        vpnControlMethod = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), METHOD_CHANNEL_VPN_CONTROL);
        vpnControlMethod.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "stop":
                    OpenVPNThread.stop();
                    setStatus("disconnected");
                    break;
                case "start":

                    if (call.hasArgument("config")) config = call.argument("config");
                    if (call.hasArgument("country")) name = call.argument("country");
                    if (call.hasArgument("username")) username = call.argument("username");
                    if (call.hasArgument("password")) password = call.argument("password");

                    if (config == null || name == null) {
                        Log.e(TAG, "Config not valid!");
                        return;
                    }

                    prepareVPN();
                    break;
                case "refresh":
                    updateVPNStatus();
                    break;
                case "status":
                    result.success(OpenVPNService.getStatus());
                    break;
            }
        });

    }

    private void prepareVPN() {
        if (isConnected()) {
            setStatus("prepare");
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) startActivityForResult(vpnIntent, VPN_REQUEST_ID);
            else startVPN();
        } else {
            setStatus("nonetwork");
        }
    }

    private void startVPN() {
        try {
            setStatus("connecting");
            ConfigParser configParser = new ConfigParser();
            configParser.parseConfig(new StringReader(config));
            VpnProfile vpnProfile = configParser.convertProfile();
            if(vpnProfile.checkProfile(this) != de.blinkt.openvpn.R.string.no_error_found){
                throw new RemoteException(getString(vpnProfile.checkProfile(this)));
            }
            vpnProfile.mName = name;
            vpnProfile.mProfileCreator = getPackageName();
            vpnProfile.mUsername = username;
            vpnProfile.mPassword = password;
            ProfileManager.setTemporaryProfile(this, vpnProfile);
            VPNLaunchHelper.startOpenVpn(vpnProfile, this);
        } catch (RemoteException e) {
            setStatus("disconnected");
            e.printStackTrace();
        } catch (ConfigParser.ConfigParseError configParseError) {
            setStatus("disconnected");
            configParseError.printStackTrace();
        } catch (IOException e) {
            setStatus("disconnected");
            e.printStackTrace();
        }
    }


    private void updateVPNStatus() {
        setStatus(OpenVPNService.getStatus());
    }


    private boolean isConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        @SuppressLint("MissingPermission") NetworkInfo nInfo = cm.getActiveNetworkInfo();

        return nInfo != null && nInfo.isConnectedOrConnecting();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VPN_REQUEST_ID && resultCode == RESULT_OK) {
            startVPN();
        } else {
            setStatus("denied");
            Toast.makeText(this, "Permission is denied!", Toast.LENGTH_SHORT).show();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }


    private void setStatus(String status) {
        switch (status.toUpperCase()) {
            case "CONNECTED":
                if (vpnStageSink != null) vpnStageSink.success("connected");
                break;
            case "DISCONNECTED":
                if (vpnStageSink != null) vpnStageSink.success("disconnected");
                break;
            case "WAIT":
                if (vpnStageSink != null) vpnStageSink.success("wait_connection");
                break;
            case "AUTH":
                if (vpnStageSink != null) vpnStageSink.success("authenticating");
                break;
            case "RECONNECTING":
                if (vpnStageSink != null) vpnStageSink.success("reconnect");
                break;
            case "NONETWORK":
                if (vpnStageSink != null) vpnStageSink.success("no_connection");
                break;
            case "CONNECTING":
                if (vpnStageSink != null) vpnStageSink.success("connecting");
                break;
            case "PREPARE":
                if (vpnStageSink != null) vpnStageSink.success("prepare");
                break;
            case "DENIED":
                if (vpnStageSink != null) vpnStageSink.success("denied");
                break;
        }
    }
}
