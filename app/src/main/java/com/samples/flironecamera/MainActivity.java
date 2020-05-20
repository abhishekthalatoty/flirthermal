/*
 * ******************************************************************
 * @title FLIR THERMAL SDK
 * @file MainActivity.java
 * @Author FLIR Systems AB
 *
 * @brief  Main UI of test application
 *
 * Copyright 2019:    FLIR Systems
 * ******************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler;
import com.flir.thermalsdk.image.Point;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.log.ThermalLog;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Sample application for scanning a FLIR ONE or a built in emulator
 * <p>
 * See the {@link CameraHandler} for how to preform discovery of a FLIR ONE camera, connecting to it and start streaming images
 * <p>
 * The MainActivity is primarily focused to "glue" different helper classes together and updating the UI components
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    //Handles Android permission for eg Network
    private PermissionHandler permissionHandler;

    //Handles network camera operations
    private CameraHandler cameraHandler;

    private Identity connectedIdentity = null;
    private TextView connectionStatus;
    private TextView discoveryStatus;

    private ImageView msxImage;
    private ImageView photoImage;

    private LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue(21);
    private UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();

    /**
     * Show message on the screen
     */
    public interface ShowMessage {
        void show(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ThermalLog.LogLevel enableLoggingInDebug = BuildConfig.DEBUG ? ThermalLog.LogLevel.DEBUG : ThermalLog.LogLevel.NONE;

        //ThermalSdkAndroid has to be initiated from a Activity with the Application Context to prevent leaking Context,
        // and before ANY using any ThermalSdkAndroid functions
        //ThermalLog will show log from the Thermal SDK in standards android log framework
        ThermalSdkAndroid.init(getApplicationContext(), enableLoggingInDebug);

        permissionHandler = new PermissionHandler(showMessage, MainActivity.this);

        cameraHandler = new CameraHandler(getApplicationContext());

        setupViews();

//        showSDKversion(ThermalSdkAndroid.getVersion());
    }

    public void startDiscovery(View view) {
        startDiscovery();
    }

    public void stopDiscovery(View view) {
        stopDiscovery();
    }


    public void connectFlirOne(View view) {
        connect(cameraHandler.getFlirOne());
    }

    public void connectSimulatorOne(View view) {
        connect(cameraHandler.getCppEmulator());
    }

    public void connectSimulatorTwo(View view) {
        connect(cameraHandler.getFlirOneEmulator());
    }

    public void disconnect(View view) {
        disconnect();
    }

    /**
     * Handle Android permission request response for Bluetooth permissions
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult() called with: requestCode = [" + requestCode + "], permissions = [" + permissions + "], grantResults = [" + grantResults + "]");
        permissionHandler.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Connect to a Camera
     */
    private void connect(Identity identity) {
        //We don't have to stop a discovery but it's nice to do if we have found the camera that we are looking for
        cameraHandler.stopDiscovery(discoveryStatusListener);

        if (connectedIdentity != null) {
            Log.d(TAG, "connect(), in *this* code sample we only support one camera connection at the time");
            showMessage.show("connect(), in *this* code sample we only support one camera connection at the time");
            return;
        }

        if (identity == null) {
            Log.d(TAG, "connect(), can't connect, no camera available");
            showMessage.show("connect(), can't connect, no camera available");
            return;
        }

        connectedIdentity = identity;

        updateConnectionText(identity, "CONNECTING");
        //IF your using "USB_DEVICE_ATTACHED" and "usb-device vendor-id" in the Android Manifest
        // you don't need to request permission, see documentation for more information
        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }

    }

    private UsbPermissionHandler.UsbPermissionListener permissionListener = new UsbPermissionHandler.UsbPermissionListener() {
        @Override
        public void permissionGranted(Identity identity) {
            doConnect(identity);
        }

        @Override
        public void permissionDenied(Identity identity) {
            MainActivity.this.showMessage.show("Permission was denied for identity ");
        }

        @Override
        public void error(UsbPermissionHandler.UsbPermissionListener.ErrorType errorType, final Identity identity) {
            MainActivity.this.showMessage.show("Error when asking for permission for FLIR ONE, error:" + errorType + " identity:" + identity);
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, connectionStatusListener);
                runOnUiThread(() -> {
                    updateConnectionText(identity, "CONNECTED");
                    cameraHandler.startStream(streamDataListener);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Log.d(TAG, "Could not connect: " + e);
                    updateConnectionText(identity, "DISCONNECTED");
                });
            }
        }).start();
    }

    /**
     * Disconnect to a camera
     */
    private void disconnect() {
        updateConnectionText(connectedIdentity, "DISCONNECTING");
        connectedIdentity = null;
        Log.d(TAG, "disconnect() called with: connectedIdentity = [" + connectedIdentity + "]");
        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> {
                updateConnectionText(null, "DISCONNECTED");
            });
        }).start();
    }

    /**
     * Update the UI text for connection status
     */
    private void updateConnectionText(Identity identity, String status) {
        String deviceId = identity != null ? identity.deviceId : "";
        connectionStatus.setText(getString(R.string.connection_status_text, deviceId + " " + status));
    }

    /**
     * Start camera discovery
     */
    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    /**
     * Stop camera discovery
     */
    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    /**
     * Callback for discovery status, using it to update UI
     */
    private CameraHandler.DiscoveryStatus discoveryStatusListener = new CameraHandler.DiscoveryStatus() {
        @Override
        public void started() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "discovering"));
        }

        @Override
        public void stopped() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "not discovering"));
        }
    };

    /**
     * Camera connecting state thermalImageStreamListener, keeps track of if the camera is connected or not
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private ConnectionStatusListener connectionStatusListener = new ConnectionStatusListener() {
        @Override
        public void onDisconnected(@org.jetbrains.annotations.Nullable ErrorCode errorCode) {
            Log.d(TAG, "onDisconnected errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateConnectionText(connectedIdentity, "DISCONNECTED");
                }
            });
        }
    };

    private final CameraHandler.StreamDataListener streamDataListener = new CameraHandler.StreamDataListener() {

        @Override
        public void images(FrameDataHolder dataHolder) {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
//                     Bitmap msxBitmap = dataHolder.msxBitmap;
//                     Bitmap dcBitmap = dataHolder.dcBitmap;
//                    Paint myRectPaint = new Paint();
//                    myRectPaint.setStrokeWidth(5);
//                    myRectPaint.setColor(Color.RED);
//                    myRectPaint.setStyle(Paint.Style.STROKE);
//
//                    Bitmap tempBitmap = Bitmap.createBitmap(dcBitmap.getWidth(), dcBitmap.getHeight(), Bitmap.Config.RGB_565);
//                    Canvas tempCanvas = new Canvas(tempBitmap);
//                    tempCanvas.drawBitmap(dcBitmap, 0, 0, null);
//
//                    FaceDetector faceDetector = new
//                            FaceDetector.Builder(getApplicationContext()).setTrackingEnabled(true)
//                            .build();
//                    if(!faceDetector.isOperational()){
////                new AlertDialog.Builder(v.getContext()).setMessage("Could not set up the face detector!").show();
//                        return;
//                    }
//
//
//                    Frame frame = new Frame.Builder().setBitmap(dcBitmap).build();
//                    SparseArray<Face> faces = faceDetector.detect(frame);
//
//                    for(int i=0; i<faces.size(); i++) {
//                        Face thisFace = faces.valueAt(i);
//                        float x1 = thisFace.getPosition().x;
//                        float y1 = thisFace.getPosition().y;
//                        float x2 = x1 + thisFace.getWidth();
//                        float y2 = y1 + thisFace.getHeight();
//                        tempCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
//                    }
//                    photoImage.setImageDrawable(new BitmapDrawable(getResources(),tempBitmap));

                    msxImage.setImageBitmap(dataHolder.msxBitmap);
//                    photoImage.setImageBitmap(dataHolder.dcBitmap);


                }
            });
        }

        private static final String myTAG = "MY";

        @Override
        public void images(Bitmap msxBitmap, Bitmap dcBitmap) {

            try {
                framesBuffer.put(new FrameDataHolder(msxBitmap, dcBitmap));
            } catch (InterruptedException e) {
                //if interrupted while waiting for adding a new item in the queue
                Log.e(myTAG, "images(), unable to add incoming images to frames buffer, exception:" + e);
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "framebuffer size:" + framesBuffer.size());
                    FrameDataHolder poll = framesBuffer.poll();
//                    msxImage.setImageBitmap(poll.msxBitmap);
//
//                    Bitmap customdcBitmap = poll.dcBitmap;
//
//
//                    Bitmap tempBitmap = Bitmap.createBitmap(customdcBitmap.getWidth(), customdcBitmap.getHeight(), Bitmap.Config.RGB_565);
//                    Canvas tempCanvas = new Canvas(tempBitmap);
//                    tempCanvas.drawBitmap(customdcBitmap, 0, 0, null);
//
//                    FaceDetector faceDetector = new
//                            FaceDetector.Builder(getApplicationContext()).setTrackingEnabled(true)
//                            .build();
//                    if (!faceDetector.isOperational()) {
////                new AlertDialog.Builder(v.getContext()).setMessage("Could not set up the face detector!").show();
//                        return;
//                    }
//
//                    Paint myRectPaint = new Paint();
//                    myRectPaint.setStrokeWidth(5);
//                    myRectPaint.setColor(Color.RED);
//                    myRectPaint.setStyle(Paint.Style.STROKE);
//
//                    Frame frame = new Frame.Builder().setBitmap(customdcBitmap).build();
//                    SparseArray<Face> faces = faceDetector.detect(frame);
//
//                    Log.d("FACES", "Number of faces : "+faces.size());
//                    for (int i = 0; i < faces.size(); i++) {
//                        Face thisFace = faces.valueAt(i);
//                        float x1 = thisFace.getPosition().x;
//                        float y1 = thisFace.getPosition().y;
//                        float x2 = x1 + thisFace.getWidth();
//                        float y2 = y1 + thisFace.getHeight();
//                        float width = thisFace.getWidth();
//                        float height = thisFace.getHeight();
//                        myRectPaint.setColor(Color.BLUE);
//                        try {
//                            ThermalImage thermalImage = ThermalStore.getThermalImage();
//                            int rectangleX = (int) (x1 + width / 2);
//                            int rectangleY = (int) (y1 + height / 2);
//                            try{
//                                double averageTemp = 0;
//                                averageTemp = thermalImage.getValueAt(new Point(rectangleX, rectangleY)) ;
//                                averageTemp += thermalImage.getValueAt(new Point((int)(x1+width/3), (int)(y1+height/3)));
//                                averageTemp += thermalImage.getValueAt(new Point((int)(x1+(2*(width/3))), (int)(y1+height/3)));
//                                averageTemp += thermalImage.getValueAt(new Point((int)(x1+width/3), (int)(y1+ (2*(height/3)))));
//                                averageTemp += thermalImage.getValueAt(new Point((int)(x1+(2*(width/3))), (int)((y1+2*(height/3)))));
//                                averageTemp/=5.0;
//                                averageTemp-=273;
//                                Log.d("TEMP", Double.toString(averageTemp));
//                                myRectPaint.setTextSize((int) (70));
//                                tempCanvas.drawText( Double.toString( averageTemp), x1, y1, myRectPaint);
//                                if( averageTemp >= 36.5){
//                                    myRectPaint.setColor(Color.RED);
//                                    tempCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
//                                } else{
//                                    myRectPaint.setColor(Color.BLUE);
//                                    tempCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
//                                }
//
//                            }catch (Exception e){
//                                Log.d("EX", e.toString());
//                            }
//                        } catch (Exception e) {
//                            Log.d("EX", e.toString());
//                        }
//                    }
//
//
//                    photoImage.setImageDrawable(new BitmapDrawable(getResources(), tempBitmap));

                    photoImage.setImageBitmap(dcBitmap);
                }
            });

        }
    };

    /**
     * Camera Discovery thermalImageStreamListener, is notified if a new camera was found during a active discovery phase
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(Identity identity) {
            Log.d(TAG, "onCameraFound identity:" + identity);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cameraHandler.add(identity);
                }
            });
        }

        @Override
        public void onDiscoveryError(CommunicationInterface communicationInterface, ErrorCode errorCode) {
            Log.d(TAG, "onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopDiscovery();
                    MainActivity.this.showMessage.show("onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);
                }
            });
        }
    };

    private ShowMessage showMessage = new ShowMessage() {
        @Override
        public void show(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

//    private void showSDKversion(String version) {
////        TextView sdkVersionTextView = findViewById(R.id.sdk_version);
//        String sdkVersionText = getString(R.string.sdk_version_text, version);
//        sdkVersionTextView.setText(sdkVersionText);
//    }

    private void setupViews() {
        connectionStatus = findViewById(R.id.connection_status_text);
        discoveryStatus = findViewById(R.id.discovery_status);

        msxImage = findViewById(R.id.msx_image);
        photoImage = findViewById(R.id.photo_image);
    }

}
