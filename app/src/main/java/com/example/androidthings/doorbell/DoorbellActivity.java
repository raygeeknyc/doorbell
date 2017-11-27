/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.androidthings.doorbell;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
/**
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MultipartBody;
import okhttp3.Response;
import okhttp3.MediaType;
*/
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
//import com.google.firebase.database.DatabaseReference;
//import com.google.firebase.database.FirebaseDatabase;
//import com.google.firebase.database.ServerValue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * Doorbell activity that capture a picture from the Raspberry Pi 3
 * Camera on a button press and post it to Firebase and Google Cloud
 * Vision API.
 */
public class DoorbellActivity extends Activity {
    private final String GS_ENDPOINT_URL = "https://us-central1-iam-iot-hackathon.cloudfunctions.net/imageHandler";
    private final String IMAGE_FILENAME = "capture.jpg";
    private static final String TAG = DoorbellActivity.class.getSimpleName();

    private DoorbellCamera mCamera;

    /*
     * Driver for the doorbell button;
     */
    private ButtonInputDriver mButtonInputDriver;

    /**
     * A {@link Handler} for running Camera tasks in the background.
     */
    private Handler mCameraHandler;

    /**
     * An additional thread for running Camera tasks that shouldn't block the UI.
     */
    private HandlerThread mCameraThread;

    /**
     * A {@link Handler} for running Cloud tasks in the background.
     */
    private Handler mCloudHandler;

    /**
     * An additional thread for running Cloud tasks that shouldn't block the UI.
     */
    private HandlerThread mCloudThread;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Doorbell Activity created.");

        // We need permission to access the camera
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // A problem occurred auto-granting the permission
            Log.d(TAG, "No permission");
            return;
        }

        // Creates new handlers and associated threads for camera and networking operations.
        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        mCloudThread = new HandlerThread("CloudThread");
        mCloudThread.start();
        mCloudHandler = new Handler(mCloudThread.getLooper());

        // Initialize the doorbell button driver
        initPIO();

        // Camera code is complicated, so we've shoved it all in this closet class for you.
        mCamera = DoorbellCamera.getInstance();
        mCamera.initializeCamera(this, mCameraHandler, mOnImageAvailableListener);
    }

    private void initPIO() {
        try {
            mButtonInputDriver = new ButtonInputDriver(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_ENTER);
            mButtonInputDriver.register();
        } catch (IOException e) {
            mButtonInputDriver = null;
            Log.w(TAG, "Could not open GPIO pins", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCamera.shutDown();

        mCameraThread.quitSafely();
        mCloudThread.quitSafely();
        try {
            mButtonInputDriver.close();
        } catch (IOException e) {
            Log.e(TAG, "button driver error", e);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            // Doorbell rang!
            Log.d(TAG, "button pressed");
            mCamera.takePicture();
            Log.d(TAG, "pic taken");
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Listener for new camera images.
     */
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "Image is available");

            Image image = reader.acquireLatestImage();
            // get image bytes
            ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
            final byte[] imageBytes = new byte[imageBuf.remaining()];
            imageBuf.get(imageBytes);
            image.close();

            onPictureTaken(imageBytes);
        }
    };

    /**
     * Handle image processing
     */
    private void onPictureTaken(final byte[] imageBytes) {
        if (imageBytes == null) {
            Log.w(TAG, "Image is null");
        } else {
            //String imageStr = Base64.encodeToString(imageBytes, Base64.NO_WRAP | Base64.URL_SAFE);
            Log.d(TAG, "Image length:"+imageBytes.length);
            mCloudHandler.post(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "sending image to cloud");

                    String ext_storage_state = Environment.getExternalStorageState();
                    Log.d(TAG, "ext_storage)_state: "+ext_storage_state);

                    File mediaStorage = new File(Environment.getExternalStorageDirectory()
                            + "/Folder name");
                    if (ext_storage_state.equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
                        if (!mediaStorage.exists()) {
                            Log.d(TAG, "!storage.exists()");
                            mediaStorage.mkdirs();
                        }
                        //write file writing code..

                        try {
                            FileOutputStream fos=new FileOutputStream(IMAGE_FILENAME);
                            try {
                                fos.write(imageBytes);
                                fos.close();
                            } catch (IOException e) {
                                // TODO Auto-generated catch block
                                e.printStackTrace();
                            }
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                    else
                    {
                        Log.e(TAG, "SD card not found");
                    }
                    //try {
                        Log.d(TAG, "uploading image");

                        /***
                        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build();
                        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                                .addFormDataPart("File", IMAGE_FILENAME, RequestBody.create(MediaType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), IMAGE_FILENAME))
                                .build();
                        Request request = new Request.Builder().url(GS_ENDPOINT_URL).post(body).build();
                        Response response = client.newCall(request).execute();
                        String result = response.body().string();
                        **
                    } catch (java.io.IOException e) {
                        Log.e(TAG, e.getMessage());
                    } */
                }
            });
        }
    }
}
