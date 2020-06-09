/*******************************************************************
 * @title FLIR THERMAL SDK
 * @file CameraHandler.java
 * @Author FLIR Systems AB
 *
 * @brief Helper class that encapsulates *most* interactions with a FLIR ONE camera
 *
 * Copyright 2019:    FLIR Systems
 ********************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.util.SparseArray;

import androidx.appcompat.app.AlertDialog;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.Point;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.streaming.ThermalImageStreamListener;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import android.content.Context;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Encapsulates the handling of a FLIR ONE camera or built in emulator, discovery, connecting and start receiving images.
 * All listeners are called from Thermal SDK on a non-ui thread
 * <p/>
 * Usage:
 * <pre>
 * Start discovery of FLIR FLIR ONE cameras or built in FLIR ONE cameras emulators
 * {@linkplain #startDiscovery(DiscoveryEventListener, DiscoveryStatus)}
 * Use a discovered Camera {@linkplain Identity} and connect to the Camera
 * (note that calling connect is blocking and it is mandatory to call this function from a background thread):
 * {@linkplain #connect(Identity, ConnectionStatusListener)}
 * Once connected to a camera
 * {@linkplain #startStream(StreamDataListener)}
 * </pre>
 * <p/>
 * You don't *have* to specify your application to listen or USB intents but it might be beneficial for you application,
 * we are enumerating the USB devices during the discovery process which eliminates the need to listen for USB intents.
 * See the Android documentation about USB Host mode for more information
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    private StreamDataListener streamDataListener;
    private RequestQueue queue;

    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);
        void images(Bitmap msxBitmap, Bitmap dcBitmap);
    }
    Context wrapper;
    CameraHandler(Context context){
        this.wrapper = context;
        queue = Volley.newRequestQueue(wrapper);
    }

    //Discovered FLIR cameras
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    //A FLIR Camera
    private Camera camera;


    public interface DiscoveryStatus {
        void started();
        void stopped();
    }

    public CameraHandler() {
    }

    /**
     * Start discovery of USB and Emulators
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Stop discovery of USB and Emulators
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        camera = new Camera();
        camera.connect(identity, connectionStatusListener);
    }

    public void disconnect() {
        if (camera == null) {
            return;
        }
        if (camera.isGrabbing()) {
            camera.unsubscribeAllStreams();
        }
        camera.disconnect();
    }

    /**
     * Start a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        camera.subscribeStream(thermalImageStreamListener);
    }

    /**
     * Stop a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void stopStream(ThermalImageStreamListener listener) {
        camera.unsubscribeStream(listener);
    }

    /**
     * Add a found camera to the list of known cameras
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity get(int i) {
        return foundCameraIdentities.get(i);
    }

    /**
     * Get a read only list of all found cameras
     */
    @Nullable
    public List<Identity> getCameraList() {
        return Collections.unmodifiableList(foundCameraIdentities);
    }

    /**
     * Clear all known network cameras
     */
    public void clear() {
        foundCameraIdentities.clear();
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("C++ Emulator")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOne() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            boolean isFlirOneEmulator = foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE");
            boolean isCppEmulator = foundCameraIdentity.deviceId.contains("C++ Emulator");
            if (!isFlirOneEmulator && !isCppEmulator) {
                return foundCameraIdentity;
            }
        }

        return null;
    }

    private void withImage(ThermalImageStreamListener listener, Camera.Consumer<ThermalImage> functionToRun) {
        camera.withImage(listener, functionToRun);
    }


    /**
     * Called whenever there is a new Thermal Image available, should be used in conjunction with {@link Camera.Consumer}
     */
    private final ThermalImageStreamListener thermalImageStreamListener = new ThermalImageStreamListener() {
        @Override
        public void onImageReceived() {
            //Will be called on a non-ui thread
            Log.d(TAG, "onImageReceived(), we got another ThermalImage");
            withImage(this, handleIncomingImage);
        }
    };

    /**
     * Function to process a Thermal Image and update UI
     */





    public static  String myTAG = "MYTAG";
    private final Camera.Consumer<ThermalImage> handleIncomingImage = new Camera.Consumer<ThermalImage>() {
        @Override
        public void accept(ThermalImage thermalImage) {
            Log.d(TAG, "accept() called with: thermalImage = [" + thermalImage.getDescription() + "]");
            //Will be called on a non-ui thread,
            // extract information on the background thread and send the specific information to the UI thread

            Bitmap msxBitmap;
            {
                thermalImage.getFusion().setFusionMode(FusionMode.THERMAL_ONLY);
                msxBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();
            }

            //Get a bitmap with the visual image, it might have different dimensions then the bitmap from THERMAL_ONLY
            Bitmap dcBitmap = BitmapAndroid.createBitmap(thermalImage.getFusion().getPhoto()).getBitMap();
            Paint myRectPaint = new Paint();
                    myRectPaint.setStrokeWidth(5);
            myRectPaint.setColor(Color.RED);
            myRectPaint.setStyle(Paint.Style.STROKE);

            Bitmap tempBitmap = Bitmap.createBitmap(dcBitmap.getWidth(), dcBitmap.getHeight(), Bitmap.Config.RGB_565);
            Canvas tempCanvas = new Canvas(tempBitmap);
//            Canvas thermalCanvas = new Canvas(msxBitmap);

            tempCanvas.drawBitmap(dcBitmap, 0, 0, null);
//
            FaceDetector faceDetector = new
                    FaceDetector.Builder(wrapper).setTrackingEnabled(true)
                    .build();
            if(!faceDetector.isOperational()){
//                new AlertDialog.Builder(v.getContext()).setMessage("Could not set up the face detector!").show();
                return;
            }


            Frame frame = new Frame.Builder().setBitmap(dcBitmap).build();
            SparseArray<Face> faces = faceDetector.detect(frame);
            myRectPaint.setTextSize((int) (70));


            // THIS CODE IS USED TO DEBUG THE PAINTS AND POINT SCALING.
//            try{
//                Log.d("temp",Double.toString(5465) );
//                Log.d("tempsize", Double.toString(thermalImage.getWidth())   + "    "+ Double.toString(dcBitmap.getWidth())  );
//                double avgTemp = thermalImage.getValueAt(new Point( ((dcBitmap.getWidth() / 5)), dcBitmap.getHeight()/5));
//                avgTemp-=273;
//
//                Log.d("SPECS", Integer.toString(dcBitmap.getWidth())+ "  " + Integer.toString(msxBitmap.getWidth() ));
//                Log.d("SPECS", Integer.toString(dcBitmap.getHeight())+ "  " + Integer.toString(msxBitmap.getHeight() ));
//
//                tempCanvas.drawRoundRect(new RectF(50, 50, (int)(  dcBitmap.getWidth() /2),(int)( dcBitmap.getHeight()/2) ), 2, 2, myRectPaint);
//                thermalCanvas.drawRoundRect(new RectF((int) (50 /2.25), (int)(50 /2.25), (int)(  dcBitmap.getWidth() /(2*2.25)),(int)( dcBitmap.getHeight()/(2*2.25)) ), 2, 2, myRectPaint);
//                tempCanvas.drawText(Double.toString(avgTemp), dcBitmap.getWidth() / 2, dcBitmap.getHeight()/2, myRectPaint);
//            } catch (Exception e ){
//                Log.d("XD", e.toString());
//            }


            try {
                for(int i=0; i<faces.size(); i++) {
                    Face thisFace = faces.valueAt(i);
                    float width = thisFace.getWidth();
                    float height = thisFace.getHeight();
                    float x1 = thisFace.getPosition().x;
                    float y1 = thisFace.getPosition().y;
                    float x2 = x1 + width;
                    float y2 = y1 + height;
                    int rectangleX = (int) (x1 + width / 2);
                    int rectangleY = (int) (y1 + height/2);
                        try{
                            double averageTemp = 0;
                            averageTemp = thermalImage.getValueAt(new Point( (int)(rectangleX /2.25), (int)(rectangleY / 2.25)));
                            averageTemp-=273;
                            Log.d("TEMP", Double.toString(averageTemp));

                            if( averageTemp >= 36.5){
                                myRectPaint.setColor(Color.RED);
                                myRectPaint.setTextSize((int) (70));
                                tempCanvas.drawText(Double.toString(averageTemp), x1, y1, myRectPaint);
                                tempCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
                            } else{
                                myRectPaint.setColor(Color.BLUE);
                                myRectPaint.setTextSize((int) (70));
                                tempCanvas.drawText(Double.toString(averageTemp), x1, y1, myRectPaint);
                                tempCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
                            }

                        }catch (Exception e){
                            Log.d("EX", e.toString());
                        }

                    tempCanvas.drawRoundRect(new RectF(x1, y1, x2, y2), 2, 2, myRectPaint);
                }
            }catch (Exception e) {
                Log.d("EX", "LOGEX" + e.toString());
            }
//            faceDetector.release();
//            streamDataListener.images(msxBitmap, tempBitmap);
            streamDataListener.images(msxBitmap, tempBitmap);
            // end

            Log.d(TAG,"adding images to cache");

        }
    };


}
