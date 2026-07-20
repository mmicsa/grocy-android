package woyou.aidlservice.jiuiv5;

import woyou.aidlservice.jiuiv5.ICallback;
import woyou.aidlservice.jiuiv5.ITax;
import android.graphics.Bitmap;
import com.sunmi.trans.TransBean;

interface IWoyouService {

    /**
     * Update printer firmware (usually system-only)
     */
    void updateFirmware();

    /**
     * Get printer firmware status
     * @return 0: unknown, 0xA5: bootloader, 0xC3: printer
     */
    int getFirmwareStatus();

    /**
     * Get WoyouService version
     */
    String getServiceVersion();

    /**
     * Initialize the printer, reset logic, but do not clear the buffer.
     * @param callback results callback
     */
    void printerInit(in ICallback callback);

    /**
     * Print a self-test page
     * @param callback results callback
     */
    void printerSelfChecking(in ICallback callback);

    /**
     * Get the printer board serial number
     */
    String getPrinterSerialNo();

    /**
     * Get the printer firmware version
     */
    String getPrinterVersion();

    /**
     * Get the printer model
     */
    String getPrinterModal();

    /**
     * Get total printed length of the print head
     */
    void getPrintedLength(in ICallback callback);

    /**
     * Feed paper n lines
     * @param n lines to feed
     */
    void lineWrap(int n, in ICallback callback);

    /**
     * Send raw ESC/POS commands
     * @param data byte array of commands
     */
    void sendRAWData(in byte[] data, in ICallback callback);

    /**
     * Set alignment mode
     * @param alignment 0: Left, 1: Center, 2: Right
     */
    void setAlignment(int alignment, in ICallback callback);

    /**
     * Set font name (currently supports 'gh')
     */
    void setFontName(String typeface, in ICallback callback);

    /**
     * Set font size
     */
    void setFontSize(float fontsize, in ICallback callback);

    /**
     * Print text
     */
    void printText(String text, in ICallback callback);

    /**
     * Print text with specific font and size
     */
    void printTextWithFont(String text, String typeface, float fontsize, in ICallback callback);

    /**
     * Print a table row
     * @param colsTextArr text for each column
     * @param colsWidthArr width of each column (relative weights)
     * @param colsAlign alignment for each column (0: Left, 1: Center, 2: Right)
     */
    void printColumnsText(in String[] colsTextArr, in int[] colsWidthArr, in int[] colsAlign, in ICallback callback);

    /**
     * Print a bitmap (max width 384px)
     */
    void printBitmap(in Bitmap bitmap, in ICallback callback);

    /**
     * Print a barcode
     * @param symbology 0: UPC-A, 1: UPC-E, 2: EAN13, 3: EAN8, 4: CODE39, 5: ITF, 6: CODABAR, 7: CODE93, 8: CODE128
     * @param height height of barcode (1-255)
     * @param width width of barcode (2-6)
     * @param textposition text alignment (0: None, 1: Top, 2: Bottom, 3: Both)
     */
    void printBarCode(String data, int symbology, int height, int width, int textposition, in ICallback callback);

    /**
     * Print a QR code
     * @param modulesize size of QR code (1-16)
     * @param errorlevel error correction (0-3)
     */
    void printQRCode(String data, int modulesize, int errorlevel, in ICallback callback);

    /**
     * Print original text (no styling)
     */
    void printOriginalText(String text, in ICallback callback);

    /**
     * Commit and print the buffer
     */
    void commitPrinterBuffer();

    /**
     * Enter buffer mode (commands are stored until commit)
     * @param clean if true, clear existing buffer
     */
    void enterPrinterBuffer(in boolean clean);

    /**
     * Exit buffer mode
     * @param commit if true, print the buffer content
     */
    void exitPrinterBuffer(in boolean commit);

    /**
     * Open the cash drawer
     */
    void openDrawer(in ICallback callback);

    /**
     * Get number of times the cash drawer has been opened
     */
    int getOpenDrawerTimes();

    /**
     * Transaction print task list
     */
    void commitPrint(in TransBean[] trans, in ICallback callback);

    /**
     * Get printer status
     */
    int updatePrinterState();

    /**
     * Get Tax interface
     */
    void getTax(in ITax callback);

    /**
     * Support version 4.0.0+ for direct data transport
     */
    boolean postPrintData(String packageName, in byte[] data, int offset, int length);
}
