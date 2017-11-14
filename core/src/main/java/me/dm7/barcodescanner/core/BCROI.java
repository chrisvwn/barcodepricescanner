package me.dm7.barcodescanner.core;

import android.graphics.Rect;
import org.opencv.*;
/**
 * Created by chris on 2017-09-23.
 */

public class BCROI
{
    String type = "";
    public org.opencv.core.Rect bRect = new org.opencv.core.Rect();
    public String value = "";

    public BCROI()
    {

    }

    public BCROI(String theType, org.opencv.core.Rect rect, String val)
    {
        type = theType;
        bRect = rect;
        value = val;
    }
}
