package me.dm7.barcodescanner.zxing;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.graphics.Bitmap;
import android.graphics.Camera;
import android.media.Image;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

//import com.googlecode.leptonica.android.*;
//import com.googlecode.tesseract.*;
import com.googlecode.tesseract.android.TessBaseAPI;
//import com.

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import me.dm7.barcodescanner.core.BCROI;
import me.dm7.barcodescanner.core.ViewFinderView;

import static android.content.ContentValues.TAG;

/**
 * Created by chris on 2017-09-21.
 */
public final class FullScanCodeReader {

    Mat origScene;

    Mat diff;
    double origTime;
    double detectedDelay, startDelay;
    Mat hierarchy = new Mat();
    List<MatOfPoint> cnts = new ArrayList<MatOfPoint>();
    double imgCntArea = 0;
    Scalar colorGreen = new Scalar(0, 255, 0);
    Mat frameDelta = new Mat();
    Mat imgResult = new Mat();
    Mat thresh = new Mat();
    Size kernSize = new Size(21, 21);
    Point txtPos = new Point(0, 50);
    Mat kern = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));;
    String state = "initializing";
    Point threshCenterAnchor = new Point(-1,-1);
    Size imgRescaleSize = new Size(800, 600);
    Mat gray1 = new Mat();
    Mat gray2 = new Mat();
    AtomicBoolean barcodeFound = new AtomicBoolean(false);
    Point topLeft = new Point(), bottomRight = new Point();
    boolean msgDisplayed = false;
    double imgFraction;
    double currTime;
    String tessResult = null;
    Mat detectedLabel;
    Rect bRect;
    int maxN;
    MatOfPoint cntCvt = new MatOfPoint();
    Mat rectKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(33,5));
    Mat sqKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(21,21));
    Mat gray = new Mat();
    List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
    MatOfPoint2f approx = new MatOfPoint2f();
    Point [] approx_array = new Point [4];

    String regex = "(\\d+,\\d{2})";
    Pattern pattern = Pattern.compile(regex);

    List<String> allBarcodes = new ArrayList<String>();
    List<String> allPrices = new ArrayList<String>();
    List<MatOfPoint> squares;
    Bitmap bMap;
    Matcher m;
    Result barcodeResult = null;
    //Rect bRect;
    LuminanceSource source;
    BinaryBitmap bitmap;
    Reader reader = new MultiFormatReader();
    Result[] multiBarcodeResult;
    GenericMultipleBarcodeReader multiBarcodeReader = new GenericMultipleBarcodeReader(reader);

    String strOut = "";
    BCROI roi = new BCROI();
    ArrayList<BCROI> bcROIArrayList = new ArrayList<BCROI>();

    Context ctxt;

    public String DATA_PATH = "";

    TessBaseAPI tessInstance = new TessBaseAPI();

    String[] paths;

    //constructor
    public FullScanCodeReader(Context context)
    {
        ctxt = context;

        DATA_PATH = ctxt.getApplicationContext().getFilesDir() + "/BarcodePriceReader/";

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

                AssetManager assetManager = context.getAssets();
                InputStream in = assetManager.open("tessdata/eng.traineddata");
                //GZIPInputStream gin = new GZIPInputStream(in);
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

            tessInstance.setVariable("tessedit_char_whitelist", "0123456789,");
            tessInstance.setDebug(true);
            if (tessInstance.init(DATA_PATH, "eng"))
                Log.d("tess", "Init success");
            else
                Log.d("tess", "Init failed");
        }
    }

    private MatOfPoint findLargestSquare(List<MatOfPoint> squares)
    {
        if (squares.size() == 0)
        {
            //std::cout << "findLargestSquare !!! No squares detect, nothing to do." << std::endl;
            return new MatOfPoint();
        }

        int max_width = 0;
        int max_height = 0;
        int max_square_idx = 0;

        for (int i = 0; i < squares.size(); i++)
        {
            // Convert a set of 4 unordered Points into a meaningful cv::Rect structure.
            Rect rectangle = Imgproc.boundingRect(squares.get(i));

            //std::cout << "find_largest_square: #" << i << " rectangle x:" << rectangle.x << " y:" << rectangle.y << " " << rectangle.width << "x" << rectangle.height << endl;

            // Store the index position of the biggest square found
            if ((rectangle.width >= max_width) && (rectangle.height >= max_height))
            {
                max_width = rectangle.width;
                max_height = rectangle.height;
                max_square_idx = i;
            }
        }

        return squares.get(max_square_idx);
    }

    private Mat detectSceneChange(Mat img1, Mat img2)
    {
        if (img1.empty() || img2.empty())
            return img2;

        imgResult = img2.clone();


        return imgResult;
    }

    private double angle(Point pt1, Point pt2, Point pt0)
    {
        double dx1 = pt1.x - pt0.x;
        double dy1 = pt1.y - pt0.y;
        double dx2 = pt2.x - pt0.x;
        double dy2 = pt2.y - pt0.y;
        return (dx1*dx2 + dy1*dy2)/Math.sqrt((dx1*dx1 + dy1*dy1)*(dx2*dx2 + dy2*dy2) + 1e-10);
    }

    /*    public Image toBufferedImage(Mat m){
            int type = BufferedImage.TYPE_BYTE_GRAY;
            if ( m.channels() > 1 ) {
                Mat m2 = new Mat();
                Imgproc.cvtColor(m,m2,Imgproc.COLOR_BGR2RGB);
                type = BufferedImage.TYPE_3BYTE_BGR;
                m = m2;
            }
            byte [] b = new byte[m.channels()*m.cols()*m.rows()];
            m.get(0,0,b); // get all the pixels
            BufferedImage image = new BufferedImage(m.cols(),m.rows(), type);
            image.getRaster().setDataElements(0, 0, m.cols(),m.rows(), b);
            return image;

        }*/
    /* findSquares: returns sequence of squares detected on the image
 */
    void findSquares(Mat src, List<MatOfPoint> squares)
    {
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        //displayImage(gray, "image in grayscale");

        //smooth the image using a 3x3 Gaussian, then apply the blackhat
        //morphological operator to find dark regions on a light background
        Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

        //displayImage(gray, "gaussian blur");

        Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_BLACKHAT, rectKernel);

        //displayImage(gray, "blackhat");

        //Threshold the image
        //Imgproc.threshold(gray, gray, 100, 255, Imgproc.THRESH_BINARY);
        //Imgproc.adaptiveThreshold(gray, gray, 200, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        //         Imgproc.THRESH_BINARY, 11, 12);
        //displayImage(gray, "thresh");

        //compute the Scharr gradient of the blackhat image and scale the
        //result into the range [0, 255]
        //Mat gradX = new Mat();
        //gradX = cv2.Sobel(blackhat, ddepth=cv2.CV_32F, dx=1, dy=0, ksize=-1)
        //Imgproc.Sobel(gray, gradX, CvType.CV_32F, 1, 0, -1, 1, 0);
        //Core.convertScaleAbs(gradX, gradX);

        //displayImage(gradX, "sobel");

        //Mat filtered = new Mat();

        //filtered = gray.clone();

        //perform a series of erosions and dilations
        //Imgproc.erode(filtered, filtered, new Mat(), new Point(-1,-1), 3);

        Imgproc.dilate(gray, gray, new Mat(), new Point(-1,-1), 20);

        //displayImage(filtered, "erodee");

        // Detect edges
        //Mat edges = new Mat();
        //int thresh = 300;
        //Imgproc.Canny(filtered, edges, thresh, thresh*2);
        Imgproc.Canny(gray, gray, 50, 255);
        //displayImage(edges, "edges");

        //filtered = null;

        // Dilate helps to connect nearby line segments
        //Mat dilated_edges = new Mat();
        //Imgproc.dilate(edges, dilated_edges, new Mat(), new Point(-1, -1), 2, 1, new Scalar(0,255,0)); // default 3x3 kernel
        //displayImage(dilated_edges, "dilated edges");

        //dilated_edges = edges;
        //Imgproc.dilate(dilated_edges, dilated_edges, new Mat(), new Point(-1,-1), 20);

        //displayImage(dilated_edges, "dilated edges");

        //Imgproc.Canny(dilated_edges, dilated_edges, 250, 255);
        //displayImage(dilated_edges, "edges2");

        //edges = null;

        // Find contours and store them in a list



        Imgproc.findContours(gray, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        // Test contours and assemble squares out of them


        approx.convertTo(approx, CvType.CV_32F);

        for (int i = 0; i < contours.size(); i++)
        {
            //

            contours.get(i).convertTo(cntCvt, CvType.CV_32F);

            contours.set(i, cntCvt);

            // approximate contour with accuracy proportional to the contour perimeter
            Imgproc.approxPolyDP(new MatOfPoint2f(contours.get(i)), approx, Imgproc.arcLength(new MatOfPoint2f(contours.get(i)), true)*0.02, true);

            approx_array = approx.toArray();
            MatOfPoint approx_matofpoint = new MatOfPoint(approx_array);

            // Note: absolute value of an area is used because
            // area may be positive or negative - in accordance with the
            // contour orientation
            Rect bRect = Imgproc.boundingRect(approx_matofpoint);
            int x=bRect.x;
            int y=bRect.y;
            int w=bRect.width;
            int h=bRect.height;

            int grWidth = src.width();

            float ar;

            if (w > h)
                ar = (float)w / (float)h;
            else
                ar = (float)h / (float)w;
            float crWidth = (float)w / (float)grWidth;

            //System.out.println("ratio="+crWidth);
            // check to see if the aspect ratio and coverage width are within
            // acceptable criteria
            if (ar > 1 && ar < 5 && crWidth > 0.05)
                if(approx_array.length == 4 )
                {
                    //if (Math.abs(Imgproc.contourArea(new MatOfPoint2f(approx))) > src.height()*src.width()*0.10 &&
                    //        Imgproc.isContourConvex(approx_matofpoint))
                    if (Imgproc.isContourConvex(approx_matofpoint))
                    {
                        double maxCosine = 0;
                        for (int j = 2; j < 5; j++)
                        {
                            double cosine = Math.abs(angle(approx_array[j%4], approx_array[j-2], approx_array[j-1]));
                            maxCosine = Math.max(maxCosine, cosine);
                        }

                        if (maxCosine < 0.4) {

                            //first sort the points into tl, tr, br, bl
                            int smallest = 0;
                            Point temp;

                            //bubble sort based on x
                            for (int idxPos = 0; idxPos < approx_array.length; idxPos++)
                                for (int idx = idxPos; idx < approx_array.length; idx++)
                                {
                                    if (approx_array[idx].x < approx_array[idxPos].x)
                                    {
                                        temp = approx_array[idxPos];
                                        approx_array[idxPos] = approx_array[idx];
                                        approx_array[idx] = temp;
                                    }
                                }

                            if (approx_array[1].y < approx_array[0].y)
                            {
                                temp = approx_array[0];
                                approx_array[0] = approx_array[1];
                                approx_array[1] = temp;
                            }

                            if (approx_array[3].y < approx_array[2].y)
                            {
                                temp = approx_array[2];
                                approx_array[2] = approx_array[3];
                                approx_array[3] = temp;
                            }

                            //order points clockwise from tl
                            MatOfPoint final_array = new MatOfPoint(approx_array[0], approx_array[2], approx_array[3], approx_array[1]);

                            squares.add(new MatOfPoint(final_array));
                        }
                    }
                }
                else
                {
                    RotatedRect rect = Imgproc.minAreaRect(approx);

                    Point [] rect_array = new Point[4];
                    rect.points(rect_array);

                    // matrices we'll use
                    Mat M;
                    Mat cropped = new Mat();
                    Mat rotated = new Mat();

                    // get angle and size from the bounding box
                    double angle = rect.angle;
                    Size rect_size = rect.size;

                    // thanks to http://felix.abecassis.me/2011/10/opencv-rotation-deskewing/
                    if (rect.angle < -45.) {
                        angle += 90.0;
                        double temp = rect_size.width;
                        rect_size.width = rect_size.height;
                        rect_size.height = temp;
                    }

                    // get the rotation matrix
                    M = Imgproc.getRotationMatrix2D(rect.center, angle, 1.0);

                    // perform the affine transformation
                    Imgproc.warpAffine(src, rotated, M, src.size(), Imgproc.INTER_CUBIC);

                    for(int j=0; j < rect_array.length; j++)
                    {
                        if(rect_array[j].x < 0)
                            rect_array[j].x = 0;
                        if(rect_array[j].x > rotated.width())
                            rect_array[j].x = rotated.width()-1;
                        if(rect_array[j].y < 0)
                            rect_array[j].y = 0;
                        if(rect_array[j].y > rotated.height())
                            rect_array[j].y = rotated.height()-1;
                    }

                    MatOfPoint rect1_matofpoint = new MatOfPoint(rect_array[2], rect_array[3], rect_array[0], rect_array[1]);

                    Rect rect1 = Imgproc.boundingRect(rect1_matofpoint);

                    rect1_matofpoint = null;

                    // crop the resulting image
                    //Imgproc.getRectSubPix(rotated, rect1.size(), rect.center, cropped);
                    cropped = rotated.submat(rect1);

                    //if the area of the contour rotated if necessary is > 10000
                    //and is convex
                    //if (cropped.height()*cropped.width() > src.height()*src.width()*0.10 &&
                    //        !Imgproc.isContourConvex(approx_matofpoint))
                    if (!Imgproc.isContourConvex(approx_matofpoint))
                    {
                        //Mat tmp = src.clone();
                        //Imgproc.rectangle(tmp, rect_array[0], rect_array[2], new Scalar(255,255,255), 5);

                        //displayImage(tmp, "min area rect");

                        //displayImage(cropped, "contour points > 4");

                        for(int j=0; j < rect_array.length; j++)
                        {
                            if(rect_array[j].x < 0)
                                rect_array[j].x = 0;
                            if(rect_array[j].x > src.width())
                                rect_array[j].x = src.width()-1;
                            if(rect_array[j].y < 0)
                                rect_array[j].y = 0;
                            if(rect_array[j].y > src.height())
                                rect_array[j].y = src.height()-1;
                        }

                        MatOfPoint final_array = new MatOfPoint(rect_array[2], rect_array[3], rect_array[0], rect_array[1]);

                        squares.add(new MatOfPoint(final_array));

                        final_array = null;
                    }
                }
            approx_matofpoint = null;
        }

    }

    private List<MatOfPoint> findLabel(Mat src)
    {
        if (src.empty())
        {
            //return src;
            return(null);
        }

        int maxLen = 1200;

/*        if(src.width() > src.height())
        {
            int ratio = src.width()/maxLen;
            if (src.width() > maxLen)
                // load the image, resize it, and convert it to grayscale
                src = resize(src, maxLen, src.height()/ratio, Imgproc.INTER_AREA);
        }
        else
        {
            int ratio = src.height()/maxLen;
            if (src.height() > maxLen)
                // load the image, resize it, and convert it to grayscale
                src = resize(src, src.height()/ratio, maxLen, Imgproc.INTER_AREA);
        }*/

        List<MatOfPoint> squares = new ArrayList<MatOfPoint>();

        findSquares(src, squares);

        if (squares.size() == 0)
            return null;

        // Draw all detected squares
        Mat src_squares = src.clone();
        for (int i = 0; i < squares.size(); i++)
        {
            int n = squares.get(i).rows();
            Imgproc.polylines(src_squares,squares, true, new Scalar(255, 0, 0), 5, Core.LINE_AA, 0);
        }

        //MatOfPoint largest_square = null;
        //largest_square = findLargestSquare(squares);

        //if (largest_square == null)
        //    return null;

        //Rect bRect = Imgproc.boundingRect(largest_square);

        return squares;
    }

    public String apply1(final Mat src, final Mat dst) {
        try {
            bMap = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(src, bMap);
            int[] intArray = new int[bMap.getWidth() * bMap.getHeight()];

            //copy pixel data from the Bitmap into the 'intArray' array
            bMap.getPixels(intArray, 0, bMap.getWidth(), 0, 0, bMap.getWidth(), bMap.getHeight());

            source = new RGBLuminanceSource(bMap.getWidth(), bMap.getHeight(),intArray);

            bitmap = new BinaryBitmap(new HybridBinarizer(source));

            multiBarcodeResult = multiBarcodeReader.decodeMultiple(bitmap);

            strOut = multiBarcodeResult.toString();

            Log.d("barcode" ,"Barcode Found:"+multiBarcodeResult.toString());

            Imgproc.rectangle(src, bRect.tl(), bRect.br(), new Scalar(255,255,0), 2);
            Imgproc.putText(src, barcodeResult.toString(), bRect.tl(), 1, 1.5, new Scalar(255,255,0), 2);
        } catch (Exception e) {
            Log.d("barcode", "Not found");
        }
        return strOut;
    }

    public ArrayList<BCROI> apply(final Mat src, final Mat dst) {
        List<MatOfPoint> squares = findLabel(src);

        if(squares != null)
            if (squares.size() == 0) {

            } else {
                barcodeFound.set(false);

                if (squares == null) {

                    Log.e("FSCodeReader", "No candidates found.");
                } else
                    for (int i = 0; i < squares.size(); i++) {
                        bRect = Imgproc.boundingRect(squares.get(i));

                        detectedLabel = src.submat(bRect);

                        try {
                            bMap = Bitmap.createBitmap(detectedLabel.width(), detectedLabel.height(), Bitmap.Config.ARGB_8888);
                            Utils.matToBitmap(detectedLabel, bMap);
                            int[] intArray = new int[bMap.getWidth() * bMap.getHeight()];

                            //copy pixel data from the Bitmap into the 'intArray' array
                            bMap.getPixels(intArray, 0, bMap.getWidth(), 0, 0, bMap.getWidth(), bMap.getHeight());

                            source = new RGBLuminanceSource(bMap.getWidth(), bMap.getHeight(),intArray);

                            bitmap = new BinaryBitmap(new HybridBinarizer(source));

                            barcodeResult = reader.decode(bitmap);

                            Log.d("barcode" ,"Barcode Found:"+barcodeResult);

                            Imgproc.rectangle(src, bRect.tl(), bRect.br(), new Scalar(255,255,0), 2);
                            Imgproc.putText(src, barcodeResult.toString(), bRect.tl(), 1, 1.5, new Scalar(255,255,0), 2);

                            bcROIArrayList.add(new BCROI("barcode", bRect, barcodeResult.getText()));

                            if(!allBarcodes.contains(barcodeResult.toString()))
                                allBarcodes.add(barcodeResult.toString());
                        } catch (Exception e) {
                            Log.d("barcode", "Not found");
                        }


                        try {
                            //Mat rectKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(13,5));

                            //Imgproc.cvtColor(detectedLabel, detectedLabel, Imgproc.COLOR_BGR2GRAY);

                            //Imgproc.morphologyEx(detectedLabel, detectedLabel, Imgproc.MORPH_BLACKHAT, rectKernel);


                            //Imgproc.threshold(detectedLabel, detectedLabel, 200, 255, Imgproc.THRESH_BINARY);

                            //Imgproc.cvtColor(detectedLabel, detectedLabel, Imgproc.COLOR_BGR2GRAY);


                            //Imgproc.threshold(detectedLabel, detectedLabel, 150, 255, Imgproc.THRESH_BINARY);

                            //displayImagedetectedLabel, "detectedlabel binary");

                            //Bitmap bmp = Bitmap.createBitmap(detectedLabel.cols(), detectedLabel.rows(), Bitmap.Config.ARGB_8888);
                            //Utils.matToBitmap(result, bmp);
                            tessInstance.setImage(bMap);
                            tessResult = tessInstance.getUTF8Text();

                            if (tessResult.isEmpty()) {
                                //System.out.println("Price:Price not found");
                            } else {
                                tessResult = tessResult.replace(" ", "");

                                m = pattern.matcher(tessResult);

                                //System.out.println("Text:"+tessResult);
                                Log.d("tess", tessResult);

                                if (m.find()) {
                                    if (!allPrices.contains(m.group(1)))
                                        allPrices.add(m.group(1));
                                    //System.out.println("Price:"+m.group(1));
                                    bcROIArrayList.add(new BCROI("price", bRect, m.group(1)));
                                } else {
                                    //System.out.println("Price:Price not found");
                                    Log.d("tess", "Price:Price not found");
                                }
                            }
                        } catch (Exception e) {
                            System.out.println(e);
                        }
                        source = null;
                        bitmap = null;
                        //reader=null;
                        barcodeResult = null;
                        tessResult = null;
                    }

                if (allBarcodes.size() > allPrices.size())
                    maxN = allBarcodes.size();
                else
                    maxN = allPrices.size();

                if (maxN == 0)
                    System.out.println("Barcode:Not found Price:Not found");

                for (int j = 0; j < maxN; j++) {
                    strOut = "Barcode[" + j + "]: ";

                    if (j < allBarcodes.size())
                        strOut += allBarcodes.get(j);

                    strOut += " Price[" + j + "]: ";

                    if (j < allPrices.size())
                        strOut += allPrices.get(j);

                    System.out.println(strOut);

                    strOut = null;
                }
            }
        //detectedLabel = null;

        if(squares != null)
        while(!squares.isEmpty())
            squares.remove(0);

        src.copyTo(dst);

        return bcROIArrayList;
    }
}
