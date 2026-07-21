/*
 * This file is part of Grocy Android.
 *
 * Grocy Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grocy Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grocy Android. If not, see http://www.gnu.org/licenses/.
 *
 * Copyright (c) 2026 by Patrick Zedler
 */

package xyz.zedler.patrick.grocy.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.RemoteException;
import android.util.Log;
import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.IWoyouService;

public class PrinterUtil {

    private static final String TAG = "PrinterUtil";

    public static void setBold(IWoyouService service, boolean enabled) throws RemoteException {
        if (service == null) return;
        byte[] command = new byte[]{0x1B, 0x45, (byte) (enabled ? 0x01 : 0x00)};
        Log.d(TAG, "Setting printer bold: " + enabled);
        service.sendRAWData(command, null);
    }

    public static void printHeading(IWoyouService service, String text, ICallback cb) throws RemoteException {
        service.setAlignment(1, null); // Center
        service.setFontSize(38f, null);
        setBold(service, true);
        service.printText(text, cb);
    }

    public static void printBody(IWoyouService service, String text, ICallback cb) throws RemoteException {
        service.setAlignment(0, null); // Left
        service.setFontSize(30f, null);
        setBold(service, true);
        service.printText(text, cb);
    }

    public static void printSmall(IWoyouService service, String text, ICallback cb) throws RemoteException {
        service.setAlignment(0, null); // Left
        service.setFontSize(24f, null);
        setBold(service, true);
        service.printText(text, cb);
    }

    public static void resetFormatting(IWoyouService service) throws RemoteException {
        if (service == null) return;
        setBold(service, false);
        service.printerInit(null);
    }

    public static Bitmap renderTextToBitmap(String text, float size, boolean bold) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(size);
        paint.setColor(Color.BLACK);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        
        float width = paint.measureText(text);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float height = fm.bottom - fm.top;
        
        Bitmap bitmap = Bitmap.createBitmap((int) Math.max(width, 1), (int) Math.max(height, 1), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        canvas.drawText(text, 0, -fm.top, paint);
        return bitmap;
    }
}
