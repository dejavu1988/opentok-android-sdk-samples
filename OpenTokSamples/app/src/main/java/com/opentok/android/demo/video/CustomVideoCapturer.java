package com.opentok.android.demo.video;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.opentok.android.BaseVideoCapturer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class CustomVideoCapturer extends BaseVideoCapturer implements
        PreviewCallback {

    private final static String LOGTAG = "customer-video-capturer";

    private int mCameraIndex = 0;
    private Camera mCamera;
    private Camera.CameraInfo mCurrentDeviceInfo = null;
    private ReentrantLock mPreviewBufferLock = new ReentrantLock(); // sync
    // start/stop
    // capture
    // and
    // surface
    // changes

    private final static int PIXEL_FORMAT = ImageFormat.NV21;
    private final static int PREFERRED_CAPTURE_WIDTH = 640;
    private final static int PREFERRED_CAPTURE_HEIGHT = 480;

    private boolean isCaptureStarted = false;
    private boolean isCaptureRunning = false;

    private final int mNumCaptureBuffers = 3;
    private int mExpectedFrameSize = 0;

    private int mCaptureWidth = -1;
    private int mCaptureHeight = -1;
    private int mCaptureFPS = -1;

    private Display mCurrentDisplay;
    private SurfaceTexture mSurfaceTexture;

    private boolean mCaptureFrame = false;
    private int frame_number = 0;
    private byte[] frame = new byte[1];

    private final Context mContext;
    private boolean isReadyToGenerate;
    List<Bitmap> mFrameContainer;

    //ByteArrayOutputStream mGifByteStream;
    //AnimatedGifEncoder mGifEncoder;

    public CustomVideoCapturer(Context context) {
        mContext = context;

        // Initialize front camera by default
        this.mCameraIndex = getFrontCameraIndex();

        // Get current display to query UI orientation
        WindowManager windowManager = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        mCurrentDisplay = windowManager.getDefaultDisplay();

        mFrameContainer = new ArrayList<>();
        //mGifByteStream = new ByteArrayOutputStream();
        //mGifEncoder = new AnimatedGifEncoder();
        isReadyToGenerate = false;
    }

    @Override
    public int startCapture() {
        if (isCaptureStarted) {
            return -1;
        }

        // Set the preferred capturing size
        configureCaptureSize(PREFERRED_CAPTURE_WIDTH, PREFERRED_CAPTURE_HEIGHT);

        // Set the capture parameters
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setPreviewSize(mCaptureWidth, mCaptureHeight);
        parameters.setPreviewFormat(PIXEL_FORMAT);
        parameters.setPreviewFrameRate(mCaptureFPS);
        try {
            mCamera.setParameters(parameters);
        } catch (RuntimeException e) {
            Log.e(LOGTAG, "setParameters failed", e);
            return -1;
        }

        // Create capture buffers
        PixelFormat pixelFormat = new PixelFormat();
        PixelFormat.getPixelFormatInfo(PIXEL_FORMAT, pixelFormat);
        int bufSize = mCaptureWidth * mCaptureHeight * pixelFormat.bitsPerPixel
                / 8;
        byte[] buffer = null;
        for (int i = 0; i < mNumCaptureBuffers; i++) {
            buffer = new byte[bufSize];
            mCamera.addCallbackBuffer(buffer);
        }

        try {
            mSurfaceTexture = new SurfaceTexture(42);
            mCamera.setPreviewTexture(mSurfaceTexture);

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        // Start preview
        mCamera.setPreviewCallbackWithBuffer(this);
        mCamera.startPreview();

        mPreviewBufferLock.lock();
        mExpectedFrameSize = bufSize;
        isCaptureRunning = true;
        mPreviewBufferLock.unlock();

        isCaptureStarted = true;

        startCaptureFrame();

        return 0;
    }

    @Override
    public int stopCapture() {

        stopCaptureFrame();

        mPreviewBufferLock.lock();
        try {
            if (isCaptureRunning) {
                isCaptureRunning = false;
                mCamera.stopPreview();
                mCamera.setPreviewCallbackWithBuffer(null);
            }
        } catch (RuntimeException e) {
            Log.e(LOGTAG, "Failed to stop camera", e);
            return -1;
        }
        mPreviewBufferLock.unlock();

        isCaptureStarted = false;
        return 0;
    }

    @Override
    public void destroy() {
        if (mCamera == null) {
            return;
        }
        stopCapture();
        mCamera.release();
        mCamera = null;
    }

    @Override
    public boolean isCaptureStarted() {
        return isCaptureStarted;
    }

    @Override
    public CaptureSettings getCaptureSettings() {

        // Set the preferred capturing size
        configureCaptureSize(PREFERRED_CAPTURE_WIDTH, PREFERRED_CAPTURE_HEIGHT);

        CaptureSettings settings = new CaptureSettings();
        settings.fps = mCaptureFPS;
        settings.width = mCaptureWidth;
        settings.height = mCaptureHeight;
        settings.format = NV21;
        settings.expectedDelay = 0;
        return settings;
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onResume() {
    }

    /*
     * Get the natural camera orientation
     */
    private int getNaturalCameraOrientation() {
        if (mCurrentDeviceInfo != null) {
            return mCurrentDeviceInfo.orientation;
        } else {
            return 0;
        }
    }

    /*
     * Check if the current camera is a front camera
     */
    public boolean isFrontCamera() {
      return (mCurrentDeviceInfo != null && mCurrentDeviceInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
    }

    /*
     * Returns the currently active camera ID.
     */
    public int getCameraIndex() {
        return mCameraIndex;
    }

    /*
     * Switching between cameras if there are multiple cameras on the device.
     */
    public void swapCamera(int index) {
        boolean wasStarted = this.isCaptureStarted;

        if (mCamera != null) {
            stopCapture();
            mCamera.release();
            mCamera = null;
        }

        this.mCameraIndex = index;
        this.mCamera = Camera.open(index);
        this.mCurrentDeviceInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(index, mCurrentDeviceInfo);

        if (wasStarted) {
            startCapture();
        }
    }

    /*
     * Set current camera orientation
     */
    private int compensateCameraRotation(int uiRotation) {

        int cameraRotation = 0;
        switch (uiRotation) {
            case (Surface.ROTATION_0):
                cameraRotation = 0;
                break;
            case (Surface.ROTATION_90):
                cameraRotation = 270;
                break;
            case (Surface.ROTATION_180):
                cameraRotation = 180;
                break;
            case (Surface.ROTATION_270):
                cameraRotation = 90;
                break;
            default:
                break;
        }

        int cameraOrientation = this.getNaturalCameraOrientation();

        int totalCameraRotation = 0;
        boolean usingFrontCamera = this.isFrontCamera();
        if (usingFrontCamera) {
            // The front camera rotates in the opposite direction of the
            // device.
            int inverseCameraRotation = (360 - cameraRotation) % 360;
            totalCameraRotation = (inverseCameraRotation + cameraOrientation) % 360;
        } else {
            totalCameraRotation = (cameraRotation + cameraOrientation) % 360;
        }

        return totalCameraRotation;
    }

    /*
     * Set camera index
     */
    private static int getFrontCameraIndex() {
        for (int i = 0; i < Camera.getNumberOfCameras(); ++i) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i;
            }
        }
        return 0;
    }

    private void configureCaptureSize(int preferredWidth, int preferredHeight) {
        Camera.Parameters parameters = mCamera.getParameters();

        List<Size> sizes = parameters.getSupportedPreviewSizes();
        @SuppressWarnings("deprecation")
        List<Integer> frameRates = parameters.getSupportedPreviewFrameRates();
        int maxFPS = 0;
        if (frameRates != null) {
            for (Integer frameRate : frameRates) {
                if (frameRate > maxFPS) {
                    maxFPS = frameRate;
                }
            }
        }
        mCaptureFPS = maxFPS;

        int maxw = 0;
        int maxh = 0;
        for (int i = 0; i < sizes.size(); ++i) {
            Size s = sizes.get(i);
            if (s.width >= maxw && s.height >= maxh) {
                if (s.width <= preferredWidth && s.height <= preferredHeight) {
                    maxw = s.width;
                    maxh = s.height;
                }
            }
        }
        if (maxw == 0 || maxh == 0) {
            Size s = sizes.get(0);
            maxw = s.width;
            maxh = s.height;
        }

        mCaptureWidth = maxw;
        mCaptureHeight = maxh;
    }

    @Override
    public void init() {
        mCamera = Camera.open(mCameraIndex);
        mCurrentDeviceInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraIndex, mCurrentDeviceInfo);

    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mPreviewBufferLock.lock();
        if (isCaptureRunning) {
            if (data.length == mExpectedFrameSize) {
                // Get the rotation of the camera
                int currentRotation = compensateCameraRotation(mCurrentDisplay
                        .getRotation());

                // Send frame to OpenTok
                provideByteArrayFrame(data, NV21, mCaptureWidth,
                        mCaptureHeight, currentRotation, isFrontCamera());

                // Reuse the video buffer
                camera.addCallbackBuffer(data);

                if (mCaptureFrame) {
                    if(frame_number / mCaptureFPS >= 10) {
                        stopCaptureFrame();

                    }else if(frame_number % mCaptureFPS == 1) {
                        frame[0] = (byte) (frame_number / mCaptureFPS);
                        new FrameHandler().execute(data.clone(), frame);

                        Toast.makeText(mContext, "Frames Captured: " + frame_number + " w " + mCaptureWidth + " h " + mCaptureHeight + " fps " + mCaptureFPS, Toast.LENGTH_SHORT).show();

                    }

                    frame_number++;

                }else {
                    if(frame_number / mCaptureFPS >= 10 && isReadyToGenerate) {
                        isReadyToGenerate = false;
                        new GifHandler().execute();
                    }
                }

            }
        }
        mPreviewBufferLock.unlock();
    }

    public void startCaptureFrame() {
        mCaptureFrame = true;
        //AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        //encoder.start(bos);
        //mGifEncoder.start(mGifByteStream);
    }

    public void stopCaptureFrame() {
        mCaptureFrame = false;
        //saveGif();
    }

    public byte[] generateGIF() {
        //BitmapFactory.Options options = new BitmapFactory.Options();
        //options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        //options.inJustDecodeBounds=true;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(bos);
        for (Bitmap bitmap: mFrameContainer) {
            //String imgPath = Environment.getExternalStorageDirectory().getPath() + File.separator + "Pictures" + File.separator + "Test" + File.separator + i + ".png";
            //Bitmap bitmap = BitmapFactory.decodeFile(imgPath, options);
            encoder.addFrame(bitmap);
        }
        encoder.finish();
        clearBitmaps();
        return bos.toByteArray();
    }

    public void clearBitmaps() {
        if(mFrameContainer != null) {
            for (Bitmap bitmap: mFrameContainer) {
                bitmap.recycle();
            }
            mFrameContainer.clear();
        }
    }

    public boolean saveGif() {
        boolean result = false;
        FileOutputStream outStream = null;
        try{
            outStream = new FileOutputStream(Environment.getExternalStorageDirectory().getPath() + File.separator + "Pictures" + File.separator + "Test" + File.separator + "test.gif");
            outStream.write(generateGIF());
            outStream.close();
            result = true;
        }catch(Exception e){
            e.printStackTrace();
            result = false;
        }
        return result;
    }

    public class FrameHandler extends AsyncTask<byte[], Void, Boolean> {

        // Final Variables
        private final static int WIDTH = 640;
        private final static int HEIGHT = 480;
        private final static int ARRAY_LENGTH = 480 * 640 * 3 / 2;

        // pre-allocated working arrays
        private int[] argb8888 = new int[ARRAY_LENGTH];

        // filename of image
        int quality = 100;
        //private String filename;
        int imageIndex = 0;

        @Override
        protected synchronized Boolean doInBackground(byte[]... args) {
            Log.v("GlobeTrotter", "Beginning AsyncTask");

            imageIndex = args[1][0];
            String filepath = Environment.getExternalStorageDirectory().getPath() + File.separator + "Pictures" + File.separator + "Test" + File.separator + (args[1][0]) + ".png";

            // creates an RGB array in argb8888 from the YUV btye array
            decodeYUV(argb8888, args[0], WIDTH, HEIGHT);
            Bitmap bitmap = Bitmap.createBitmap(argb8888, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);

            mFrameContainer.add(bitmap);
            //mGifEncoder.addFrame(bitmap);
            //bitmap.recycle();

            // save a jpeg file locally
            /*try {
                save(bitmap, filepath);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                bitmap.recycle();
            }*/

            // upload that file to the server
            //postData();

            return true;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            if(imageIndex >= 9) {
                isReadyToGenerate = true;
            }
            Toast.makeText(mContext, "Frame " + imageIndex + " Captured ", Toast.LENGTH_SHORT).show();
        }

        public void save(Bitmap bmp, String filepath) throws IOException {
            //  BitmapFactory.Options options=new BitmapFactory.Options();
            //      options.inSampleSize = 20;

            FileOutputStream fos = new FileOutputStream(filepath);

            BufferedOutputStream bos = new BufferedOutputStream(fos);
            bmp.compress(Bitmap.CompressFormat.PNG, quality, bos);

            bos.flush();
            bos.close();
        }

        // decode Y, U, and V values on the YUV 420 buffer described as YCbCr_422_SP by Android
        // David Manpearl 081201
        public void decodeYUV(int[] out, byte[] fg, int width, int height)
                throws NullPointerException, IllegalArgumentException {
            int sz = width * height;
            if (out == null)
                throw new NullPointerException("buffer out is null");
            if (out.length < sz)
                throw new IllegalArgumentException("buffer out size " + out.length
                        + " < minimum " + sz);
            if (fg == null)
                throw new NullPointerException("buffer 'fg' is null");
            if (fg.length < sz)
                throw new IllegalArgumentException("buffer fg size " + fg.length
                        + " < minimum " + sz * 3 / 2);
            int i, j;
            int Y, Cr = 0, Cb = 0;
            for (j = 0; j < height; j++) {
                int pixPtr = j * width;
                final int jDiv2 = j >> 1;
                for (i = 0; i < width; i++) {
                    Y = fg[pixPtr];
                    if (Y < 0)
                        Y += 255;
                    if ((i & 0x1) != 1) {
                        final int cOff = sz + jDiv2 * width + (i >> 1) * 2;
                        Cb = fg[cOff];
                        if (Cb < 0)
                            Cb += 127;
                        else
                            Cb -= 128;
                        Cr = fg[cOff + 1];
                        if (Cr < 0)
                            Cr += 127;
                        else
                            Cr -= 128;
                    }
                    int R = Y + Cr + (Cr >> 2) + (Cr >> 3) + (Cr >> 5);
                    if (R < 0)
                        R = 0;
                    else if (R > 255)
                        R = 255;
                    int G = Y - (Cb >> 2) + (Cb >> 4) + (Cb >> 5) - (Cr >> 1)
                            + (Cr >> 3) + (Cr >> 4) + (Cr >> 5);
                    if (G < 0)
                        G = 0;
                    else if (G > 255)
                        G = 255;
                    int B = Y + Cb + (Cb >> 1) + (Cb >> 2) + (Cb >> 6);
                    if (B < 0)
                        B = 0;
                    else if (B > 255)
                        B = 255;
                    out[pixPtr++] = 0xff000000 + (B << 16) + (G << 8) + R;
                }
            }

        }
    }

    public class GifHandler extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... params) {
            //mGifEncoder.finish();
            return saveGif();
        }

        @Override
        protected void onPostExecute(Boolean result) {
            //isGifGenerated = result;
            Toast.makeText(mContext, "Gif Created " + result, Toast.LENGTH_SHORT).show();
        }

    }

}
