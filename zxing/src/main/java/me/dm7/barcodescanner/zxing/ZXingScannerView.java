package me.dm7.barcodescanner.zxing;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.googlecode.tesseract.android.TessBaseAPI;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.dm7.barcodescanner.core.BarcodeScannerView;
import me.dm7.barcodescanner.core.DisplayUtils;

import static android.content.ContentValues.TAG;

public class ZXingScannerView extends BarcodeScannerView {
    private static final String TAG = "ZXingScannerView";

    public interface ResultHandler {
        void handleResult(Result rawResult);
        void handleResult(String barcode, String price);
    }

    private MultiFormatReader mMultiFormatReader;
    public static final List<BarcodeFormat> ALL_FORMATS = new ArrayList<>();
    private List<BarcodeFormat> mFormats;
    private ResultHandler mResultHandler;
    private boolean firstPass = true;
    private String barcode = "BARCODE DISABLED"; //change barcode dialog message if foundBarcode = true
    private String price = "";
    private boolean foundBarcode = true; //set to false to enable barcode scanning
    private boolean foundPrice = false;
    private boolean foundResult = false;

    Context ctxt;

    public String DATA_PATH = "";

    TessBaseAPI tessInstance = new TessBaseAPI();
    String tessResult = null;

    String[] paths;

    String regex = "(\\d+,\\d{2})";
    Pattern pattern = Pattern.compile(regex);
    Matcher m;

    Mat rgba = new Mat();
    Bitmap bmp, bmpTemp;
    YuvImage yuv;
    ByteArrayOutputStream out;
    private Rect theFramingRect, theFramingRectScaled;
    int[] confs;

    static {
        if (!OpenCVLoader.initDebug()) {
            // Handle initialization error
            Log.e("OPENCV LOADER", "  OpenCVLoader.initDebug(), not working.");
        } else {
            Log.d("OPENCV LOADER", "  OpenCVLoader.initDebug(), working.");
        }
    }

    static {
        ALL_FORMATS.add(BarcodeFormat.AZTEC);
        ALL_FORMATS.add(BarcodeFormat.CODABAR);
        ALL_FORMATS.add(BarcodeFormat.CODE_39);
        ALL_FORMATS.add(BarcodeFormat.CODE_93);
        ALL_FORMATS.add(BarcodeFormat.CODE_128);
        ALL_FORMATS.add(BarcodeFormat.DATA_MATRIX);
        ALL_FORMATS.add(BarcodeFormat.EAN_8);
        ALL_FORMATS.add(BarcodeFormat.EAN_13);
        ALL_FORMATS.add(BarcodeFormat.ITF);
        ALL_FORMATS.add(BarcodeFormat.MAXICODE);
        ALL_FORMATS.add(BarcodeFormat.PDF_417);
        ALL_FORMATS.add(BarcodeFormat.QR_CODE);
        ALL_FORMATS.add(BarcodeFormat.RSS_14);
        ALL_FORMATS.add(BarcodeFormat.RSS_EXPANDED);
        ALL_FORMATS.add(BarcodeFormat.UPC_A);
        ALL_FORMATS.add(BarcodeFormat.UPC_E);
        ALL_FORMATS.add(BarcodeFormat.UPC_EAN_EXTENSION);
    }

    private void initTess(Context context)
    {
        ctxt = context;

        //create path in application context to save tesseract database
        DATA_PATH = ctxt.getApplicationContext().getFilesDir() + "/BarcodePriceScanner/";

        paths = new String[]{DATA_PATH, DATA_PATH + "tessdata/"};

        for (String path : paths) {
            File dir = new File(path);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.v(TAG, "ERROR: Creation of directory " + path + " on sdcard failed");
                    return;
                } else {
                    Log.v(TAG, "Created directory " + path + " on sdcard");
                }
            }
        }

// lang.traineddata file with the app (in assets folder)
        // You can get them at:
        // http://code.google.com/p/tesseract-ocr/downloads/list
        // This area needs work and optimization
        if (!(new File(DATA_PATH + "tessdata/eng.traineddata")).exists()) {
            try {

                //get the tess db eng.traineddata from the assets/tessdata subidrectory
                //in the apk
                AssetManager assetManager = context.getAssets();

                //copy the file to the application context on the phone
                InputStream in = assetManager.open("tessdata/eng.traineddata");

                OutputStream out = new FileOutputStream(DATA_PATH
                        + "tessdata/eng.traineddata");

                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                //while ((lenf = gin.read(buff)) > 0) {
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                //gin.close();
                out.close();

                Log.v(TAG, "Copied eng traineddata");
            } catch (IOException e) {
                Log.e(TAG, "Was unable to copy eng traineddata " + e.toString());
            }
        }

        //Tess settings
        //restrict detected price to digits and comma
        tessInstance.setVariable("tessedit_char_whitelist", "0123456789,");

        //look for only a single word
        tessInstance.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_WORD);

        //disable debug in production
        tessInstance.setDebug(true);

        if (tessInstance.init(DATA_PATH, "eng"))
            Log.d("tess", "Init success");
        else
            Log.d("tess", "Init failed");
    }

    public ZXingScannerView(Context context) {
        super(context);
        initMultiFormatReader();
        initTess(context);
    }

    public ZXingScannerView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        initMultiFormatReader();
        initTess(context);
    }

    public void setFormats(List<BarcodeFormat> formats) {
        mFormats = formats;
        initMultiFormatReader();
    }

    public void setResultHandler(ResultHandler resultHandler) {
        mResultHandler = resultHandler;
    }

    public Collection<BarcodeFormat> getFormats() {
        if(mFormats == null) {
            return ALL_FORMATS;
        }
        return mFormats;
    }

    private void initMultiFormatReader() {
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, getFormats());
        mMultiFormatReader = new MultiFormatReader();
        mMultiFormatReader.setHints(hints);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if(mResultHandler == null) {
            return;
        }
        
        try {
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = parameters.getPreviewSize();
            int width = size.width;
            int height = size.height;

            //get the rect detailing the window in the frame
            theFramingRect = getTheFramingRect();

            if (DisplayUtils.getScreenOrientation(getContext()) == Configuration.ORIENTATION_PORTRAIT) {
                int rotationCount = getRotationCount();
                if (rotationCount == 1 || rotationCount == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
                data = getRotatedData(data, camera);
            }

            //scale the framingRect in line with the scaled screen size  i.e. width, height
            int theTop = theFramingRect.top*height/getHeight();
            int theHeight = theFramingRect.height()*height/getHeight();
            int theLeft = theFramingRect.left*height/getHeight();
            int theWidth = theFramingRect.width()*height/getHeight();

            theFramingRectScaled = new Rect(theLeft,theTop,theLeft+theWidth,theTop+theHeight);

            Result rawResult = null;
            PlanarYUVLuminanceSource source = buildLuminanceSource(data, width, height);

            //crop the barcode screen
            /*source = (PlanarYUVLuminanceSource) source.crop(theFramingRectScaled.left,
                    theFramingRectScaled.top,
                    theFramingRectScaled.width(),
                    theFramingRectScaled.height());*/

            if(foundBarcode && foundPrice) {
                foundBarcode = true; //set to true to disable barcode scanning
                foundPrice = false;

                barcode = "BARCODE DISABLED"; //change dialog if you set foundBarcode=false
                price = "Scan Price";
            }

            if (source != null) {
                if(!foundBarcode) {
                    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                    try {
                        rawResult = mMultiFormatReader.decodeWithState(bitmap);
                    } catch (ReaderException re) {
                        // continue
                    } catch (NullPointerException npe) {
                        // This is terrible
                    } catch (ArrayIndexOutOfBoundsException aoe) {

                    } finally {
                        mMultiFormatReader.reset();
                    }

                    if (rawResult == null) {
                        LuminanceSource invertedSource = source.invert();
                        bitmap = new BinaryBitmap(new HybridBinarizer(invertedSource));
                        try {
                                rawResult = mMultiFormatReader.decodeWithState(bitmap);
                        } catch (NotFoundException e) {
                            // continue
                        } finally {
                            mMultiFormatReader.reset();
                        }
                    }

                    final Result finalRawResult = rawResult;

                    if(finalRawResult != null) {
                        barcode = finalRawResult.getText();
                        foundBarcode = true;
                        foundResult = true;
                        price = "SCAN PRICE";
                    }
                }
                else {
                    //scan price
                    //convert the image to a bitmap
                    yuv = new YuvImage(data, parameters.getPreviewFormat(), width, height, null);
                    out = new ByteArrayOutputStream();
                    yuv.compressToJpeg(new Rect(0, 0, width, height), 70, out);
                    bmpTemp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());

                    //crop to the visible area only
                    bmp = Bitmap.createBitmap(bmpTemp, theFramingRectScaled.left, theFramingRectScaled.top, theFramingRectScaled.width(), theFramingRectScaled.height());

                    //convert Bitmap to Mat; note the bitmap config ARGB_8888 conversion that
                    //allows you to use other image processing methods and still save at the end
                    bmp = bmp.copy(Bitmap.Config.ARGB_8888, true);
                    //Utils.bitmapToMat(bmp, rgba); //use if processing in OpenCV required

                    tessInstance.setImage(bmp);

                    try {
                        tessResult = tessInstance.getUTF8Text();
                        confs = tessInstance.wordConfidences(); //percent confidences per word
                    }
                    catch (Exception e)
                    {
                        System.out.println(e);
                    }

                    if (tessResult != null) {
                        //tessResult = tessResult.replace(" ", "");

                        if (tessResult.isEmpty()) {
                            Log.d("tess", "Price:Price not found");
                        } else {
                            m = pattern.matcher(tessResult);

                            //System.out.println("Text:"+tessResult);
                            Log.d("tess", tessResult);

                            if (m.find()) {
                                foundPrice = true;
                                price = m.group(1);
                                foundResult = true;
                            } else {
                                //System.out.println("Price:Price not found");
                                Log.d("tess", "Price:Price not matched");
                            }
                        }
                    }
                    //tessInstance.clear();
                }
            }

            if (foundResult) {
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Stopping the preview can take a little long.
                        // So we want to set result handler to null to discard subsequent calls to
                        // onPreviewFrame.
                        ResultHandler tmpResultHandler = mResultHandler;
                        mResultHandler = null;

                        stopCameraPreview();
                        if (tmpResultHandler != null) {
                            tmpResultHandler.handleResult(barcode, price);
                        }
                    }
                });

                foundResult = false;
            } else {
                camera.setOneShotPreviewCallback(this);
            }
        } catch(RuntimeException e) {
            // TODO: Terrible hack. It is possible that this method is invoked after camera is released.
            Log.e(TAG, e.toString(), e);
        }
    }

    public void resumeCameraPreview(ResultHandler resultHandler) {
        mResultHandler = resultHandler;
        super.resumeCameraPreview();
    }

    public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        Rect rect = getFramingRectInPreview(width, height);
        if (rect == null) {
            return null;
        }
        // Go ahead and assume it's YUV rather than die.
        PlanarYUVLuminanceSource source = null;

        try {
            source = new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                    rect.width(), rect.height(), false);
        } catch(Exception e) {
        }

        return source;
    }
}
