/*
 * Copyright 2021 Google Inc. All Rights Reserved.
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

package com.google.ar.core.codelab.rawdepth;

import static com.google.ar.core.codelab.rawdepth.DepthData.depthImageMeasure;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.PlaybackStatus;
import com.google.ar.core.RecordingConfig;
import com.google.ar.core.RecordingStatus;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.codelab.common.helpers.AABB;
import com.google.ar.core.codelab.common.helpers.CameraPermissionHelper;
import com.google.ar.core.codelab.common.helpers.DisplayRotationHelper;
import com.google.ar.core.codelab.common.helpers.FullScreenHelper;
import com.google.ar.core.codelab.common.helpers.PointClusteringHelper;
import com.google.ar.core.codelab.common.helpers.SnackbarHelper;
import com.google.ar.core.codelab.common.helpers.TrackingStateHelper;
import com.google.ar.core.codelab.common.rendering.BackgroundRenderer;
import com.google.ar.core.codelab.common.rendering.BoxRenderer;
import com.google.ar.core.codelab.common.rendering.DepthRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.PlaybackFailedException;
import com.google.ar.core.exceptions.RecordingFailedException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore Raw Depth API. The application will show 3D point-cloud data of the environment.
 */
public class RawDepthCodelabActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = RawDepthCodelabActivity.class.getSimpleName();

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    private TextView mHeight;
    private TextView mWidth;

//    private Button stopRecording;
//    private Button startRecording;
    private boolean installRequested;

    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;

    private final DepthRenderer depthRenderer = new DepthRenderer();
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final BoxRenderer boxRenderer = new BoxRenderer();

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceview);
//        mHeight = findViewById(R.id.tvDepthHeightValue);
//        mWidth = findViewById(R.id.tvDepthWidthValue);
//        startRecording = findViewById(R.id.start_recording_button);
//        stopRecording = findViewById(R.id.stop_recording_button);

        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

//        startRecording.setOnClickListener(this::onClickRecord);
//        stopRecording.setOnClickListener(this::onClickPlayback);

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);
        installRequested = false;
    }

    // Represents the app's working state.
    public enum AppState {
        Idle,
        Recording,
        Playingback
    }

    // Tracks app's specific state changes.
    private AppState appState = AppState.Idle;

    private void updateRecordButton() {
        View buttonView = findViewById(R.id.start_recording_button);
        Button button = (Button) buttonView;

        switch (appState) {
            case Idle:
                button.setText("Record");
                break;
            case Recording:
                button.setText("Stop");
                break;

            case Playingback:
                button.setVisibility(View.INVISIBLE);
                break;
        }
    }

    private void updatePlaybackButton() {
        View buttonView = findViewById(R.id.playback_button);
        Button button = (Button) buttonView;

        switch (appState) {

            // The app is neither recording nor playing back. The "Playback" button is visible.
            case Idle:
                button.setText("Playback");
                button.setVisibility(View.VISIBLE);
                break;

            // While playing back, the "Playback" button is visible and says "Stop".
            case Playingback:
                button.setText("Stop");
                button.setVisibility(View.VISIBLE);
                break;

            // During recording, the "Playback" button is not visible.
            case Recording:
                button.setVisibility(View.INVISIBLE);
                break;
        }
    }

    public void onClickRecord(View view) {
        Log.d(TAG, "onClickRecord");

        // Check the app's internal state and switch to the new state if needed.
        switch (appState) {
            // If the app is not recording, begin recording.
            case Idle: {
                boolean hasStarted = startRecording();
                Log.d(TAG, String.format("onClickRecord start: hasStarted %b", hasStarted));

                if (hasStarted)
                    appState = AppState.Recording;

                break;
            }

            // If the app is recording, stop recording.
            case Recording: {
                boolean hasStopped = stopRecording();
                Log.d(TAG, String.format("onClickRecord stop: hasStopped %b", hasStopped));

                if (hasStopped)
                    appState = AppState.Idle;

                break;
            }

            default:
                // Do nothing.
                break;
        }

        updateRecordButton();
        updatePlaybackButton();
    }

    public void onClickPlayback(View view) {
        Log.d(TAG, "onClickPlayback");

        switch (appState) {

            // If the app is not playing back, open the file picker.
            case Idle: {
                boolean hasStarted = selectFileToPlayback();
                Log.d(TAG, String.format("onClickPlayback start: selectFileToPlayback %b", hasStarted));
                break;
            }

            // If the app is playing back, stop playing back.
            case Playingback: {
                boolean hasStopped = stopPlayingback();
                Log.d(TAG, String.format("onClickPlayback stop: hasStopped %b", hasStopped));
                break;
            }

            default:
                // Recording - do nothing.
                break;
        }

        // Update the UI for the "Record" and "Playback" buttons.
        updateRecordButton();
        updatePlaybackButton();
    }

    private int REQUEST_MP4_SELECTOR = 1;

    private boolean selectFileToPlayback() {
        // Start file selection from Movies directory.
        // Android 10 and above requires VOLUME_EXTERNAL_PRIMARY to write to MediaStore.
        Uri videoCollection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            videoCollection = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }

        // Create an Intent to select a file.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        // Add file filters such as the MIME type, the default directory and the file category.
        intent.setType(MP4_VIDEO_MIME_TYPE); // Only select *.mp4 files
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, videoCollection); // Set default directory
        intent.addCategory(Intent.CATEGORY_OPENABLE); // Must be files that can be opened

        this.startActivityForResult(intent, REQUEST_MP4_SELECTOR);

        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check request status. Log an error if the selection fails.
        if (resultCode != android.app.Activity.RESULT_OK || requestCode != REQUEST_MP4_SELECTOR) {
            Log.e(TAG, "onActivityResult select file failed");
            return;
        }

        Uri mp4FileUri = data.getData();
        Log.d(TAG, String.format("onActivityResult result is %s", mp4FileUri));

        // Begin playback.
        startPlayingback(mp4FileUri);
    }

    private boolean startPlayingback(Uri mp4FileUri) {
        if (mp4FileUri == null)
            return false;

        Log.d(TAG, "startPlayingback at:" + mp4FileUri);

        pauseARCoreSession();

        try {
            session.setPlaybackDatasetUri(mp4FileUri);
        } catch (PlaybackFailedException e) {
            Log.e(TAG, "startPlayingback - setPlaybackDataset failed", e);
        }

        // The session's camera texture name becomes invalid when the
        // ARCore session is set to play back.
        // Workaround: Reset the Texture to start Playback
        // so it doesn't crashes with AR_ERROR_TEXTURE_NOT_SET.
//    hasSetTextureNames = false;

        boolean canResume = resumeARCoreSession();
        if (!canResume)
            return false;

        PlaybackStatus playbackStatus = session.getPlaybackStatus();
        Log.d(TAG, String.format("startPlayingback - playbackStatus %s", playbackStatus));


        if (playbackStatus != PlaybackStatus.OK) { // Correctness check
            return false;
        }

        appState = AppState.Playingback;
        updateRecordButton();
        updatePlaybackButton();

        return true;
    }

    private boolean stopPlayingback() {
        // Correctness check, only stop playing back when the app is playing back.
        if (appState != AppState.Playingback)
            return false;

        pauseARCoreSession();

        // Close the current session and create a new session.
        session.close();
        try {
            session = new Session(this);
        } catch (UnavailableArcoreNotInstalledException
                 | UnavailableApkTooOldException
                 | UnavailableSdkTooOldException
                 | UnavailableDeviceNotCompatibleException e) {
            Log.e(TAG, "Error in return to Idle state. Cannot create new ARCore session", e);
            return false;
        }
        configureSession();

        boolean canResume = resumeARCoreSession();
        if (!canResume)
            return false;

        // A new session will not have a camera texture name.
        // Manually set hasSetTextureNames to false to trigger a reset.
//    hasSetTextureNames = false;

        // Reset appState to Idle, and update the "Record" and "Playback" buttons.
        appState = AppState.Idle;
        updateRecordButton();
        updatePlaybackButton();

        return true;
    }

    private boolean startRecording() {
        Uri mp4FileUri = createMp4File();
        if (mp4FileUri == null)
            return false;

        Log.d(TAG, "startRecording at: " + mp4FileUri);

        pauseARCoreSession();

        // Configure the ARCore session to start recording.
        RecordingConfig recordingConfig = new RecordingConfig(session)
                .setMp4DatasetUri(mp4FileUri)
                .setAutoStopOnPause(true);

        try {
            // Prepare the session for recording, but do not start recording yet.
            session.startRecording(recordingConfig);
        } catch (RecordingFailedException e) {
            Log.e(TAG, "startRecording - Failed to prepare to start recording", e);
            return false;
        }

        boolean canResume = resumeARCoreSession();
        if (!canResume)
            return false;

        // Correctness checking: check the ARCore session's RecordingState.
        RecordingStatus recordingStatus = session.getRecordingStatus();
        Log.d(TAG, String.format("startRecording - recordingStatus %s", recordingStatus));
        return recordingStatus == RecordingStatus.OK;
    }

    private final String MP4_VIDEO_MIME_TYPE = "video/mp4";

    private Uri createMp4File() {
        Uri newMp4FileUri = null;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (!checkAndRequestStoragePermission()) {
                Log.i(TAG, String.format(
                        "Didn't createMp4File. No storage permission, API Level = %d",
                        Build.VERSION.SDK_INT));
                return null;
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String mp4FileName = "arcore-" + dateFormat.format(new Date()) + ".mp4";

            ContentResolver resolver = this.getContentResolver();

            Uri videoCollection = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                videoCollection = MediaStore.Video.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }

            // Create a new Media file record.
            ContentValues newMp4FileDetails = new ContentValues();
            newMp4FileDetails.put(MediaStore.Video.Media.DISPLAY_NAME, mp4FileName);
            newMp4FileDetails.put(MediaStore.Video.Media.MIME_TYPE, MP4_VIDEO_MIME_TYPE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The Relative_Path column is only available since API Level 29.
                newMp4FileDetails.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
            } else {
                // Use the Data column to set path for API Level <= 28.
                File mp4FileDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                String absoluteMp4FilePath = new File(mp4FileDir, mp4FileName).getAbsolutePath();
                newMp4FileDetails.put(MediaStore.Video.Media.DATA, absoluteMp4FilePath);
            }

            newMp4FileUri = resolver.insert(videoCollection, newMp4FileDetails);

            // Ensure that this file exists and can be written.
            if (newMp4FileUri == null) {
                Log.e(TAG, String.format("Failed to insert Video entity in MediaStore. API Level = %d", Build.VERSION.SDK_INT));
                return null;
            }

            // This call ensures the file exist before we pass it to the ARCore API.
            if (!testFileWriteAccess(newMp4FileUri)) {
                return null;
            }
            Log.d(TAG, String.format("createMp4File = %s, API Level = %d", newMp4FileUri, Build.VERSION.SDK_INT));
        }
        return newMp4FileUri;
    }

    // Test if the file represented by the content Uri can be open with write access.
    private boolean testFileWriteAccess(Uri contentUri) {
        try (java.io.OutputStream mp4File = this.getContentResolver().openOutputStream(contentUri)) {
            Log.d(TAG, String.format("Success in testFileWriteAccess %s", contentUri.toString()));
            return true;
        } catch (java.io.FileNotFoundException e) {
            Log.e(TAG, String.format("FileNotFoundException in testFileWriteAccess %s", contentUri.toString()), e);
        } catch (java.io.IOException e) {
            Log.e(TAG, String.format("IOException in testFileWriteAccess %s", contentUri.toString()), e);
        }

        return false;
    }

    private boolean stopRecording() {
        try {
            session.stopRecording();
        } catch (RecordingFailedException e) {
            Log.e(TAG, "stopRecording - Failed to stop recording", e);
            return false;
        }

        // Correctness checking: check if the session stopped recording.
        return session.getRecordingStatus() == RecordingStatus.NONE;
    }

    private void pauseARCoreSession() {
        // Pause the GLSurfaceView so that it doesn't update the ARCore session.
        // Pause the ARCore session so that we can update its configuration.
        // If the GLSurfaceView is not paused,
        //   onDrawFrame() will try to update the ARCore session
        //   while it's paused, resulting in a crash.
        surfaceView.onPause();
        session.pause();
    }

    private boolean resumeARCoreSession() {
        // We must resume the ARCore session before the GLSurfaceView.
        // Otherwise, the GLSurfaceView will try to update the ARCore session.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "CameraNotAvailableException in resumeARCoreSession", e);
            return false;
        }

        surfaceView.onResume();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                // Creates the ARCore session.
                session = new Session(/* context= */ this);
                if (!session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                    message =
                            "This device does not support the ARCore Raw Depth API. See"
                                    + " https://developers.google.com/ar/discover/supported-devices.";
                }

            } catch (UnavailableArcoreNotInstalledException
                     | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        }

        try {
            // Enable raw depth estimation and auto focus mode while ARCore is running.
            Config config = session.getConfig();
            config.setDepthMode(Config.DepthMode.RAW_DEPTH_ONLY);
            config.setFocusMode(Config.FocusMode.AUTO);
            session.configure(config);
            session.resume();
        } catch (CameraNotAvailableException e) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            session = null;
            return;
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        surfaceView.onResume();
        displayRotationHelper.onResume();
        messageSnackbarHelper.showMessage(this, "Waiting for depth data...");
    }


    private void configureSession() {
        // Enable raw depth estimation and auto focus mode while ARCore is running.
        Config config = session.getConfig();
        config.setDepthMode(Config.DepthMode.RAW_DEPTH_ONLY);
        config.setFocusMode(Config.FocusMode.AUTO);
        session.configure(config);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper.onPause();
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application",
                    Toast.LENGTH_LONG).show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(/*context=*/ this);
            depthRenderer.createOnGlThread(/*context=*/ this);
            boxRenderer.createOnGlThread(/*context=*/this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Clear screen to notify driver it should not load any pixels from previous frame.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtain the current frame from ARSession. When the configuration is set to
            // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
            // camera framerate.
            if (appState == AppState.Playingback
                    && session.getPlaybackStatus() == PlaybackStatus.FINISHED) {
                this.runOnUiThread(this::stopPlayingback);
                return;
            }

            Frame frame = session.update();
            Camera camera = frame.getCamera();

            // If frame is ready, render camera preview image to the GL surface.
            backgroundRenderer.draw(frame);

            // Retrieve the depth data for this frame.
            FloatBuffer points = DepthData.create(frame, session.createAnchor(camera.getPose()));

//            mHeight.setText(depthImageMeasure(frame).getHeight());
//            mWidth.setText(depthImageMeasure(frame).getWidth());

            if (points == null) {
                return;
            }

            if (messageSnackbarHelper.isShowing() && points != null) {
                messageSnackbarHelper.hide(this);
            }

            // If not tracking, show tracking failure reason instead.
            if (camera.getTrackingState() == TrackingState.PAUSED) {
                messageSnackbarHelper.showMessage(
                        this, TrackingStateHelper.getTrackingFailureReasonString(camera));
                return;
            }

            // Filters the depth data.
            DepthData.filterUsingPlanes(points, session.getAllTrackables(Plane.class));

            // Visualize depth points.
            depthRenderer.update(points);
            depthRenderer.draw(camera);

            // Draw boxes around clusters of points.
            PointClusteringHelper clusteringHelper = new PointClusteringHelper(points);
            List<AABB> clusters = clusteringHelper.findClusters();
            for (AABB aabb : clusters) {
                boxRenderer.draw(aabb, camera);
            }
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    private final int REQUEST_WRITE_EXTERNAL_STORAGE = 1;

    public boolean checkAndRequestStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_EXTERNAL_STORAGE);
            return false;
        }

        return true;
    }
}
