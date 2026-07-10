package com.capacitor.ncvplugin;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Html;
import com.getcapacitor.Logger;

public class Clipboard {

    private static final String TAG = "Clipboard";

    private Context context;
    private ClipboardManager clipboard;

    public Clipboard(Context context) {
        this.context = context;
        this.clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /**
     * Writes provided content to the clipboard.
     *
     * @param label User-visible label for the clip data.
     * @param content The content to be written to the clipboard.
     * @return A response indicating the success status of the write request.
     */
    public ClipboardWriteResponse write(String label, String content) {
        ClipData data = ClipData.newPlainText(label, content);

        if (data != null && clipboard != null) {
            try {
                clipboard.setPrimaryClip(data);
            } catch (Exception e) {
                Logger.error(TAG, e);
                return new ClipboardWriteResponse(false, "Writing to the clipboard failed");
            }

            return new ClipboardWriteResponse(true);
        } else if (clipboard == null) {
            return new ClipboardWriteResponse(false, "Problem getting a reference to the system clipboard");
        } else {
            return new ClipboardWriteResponse(false, "Problem formatting data");
        }
    }

    /**
     * Writes HTML content to the clipboard.
     *
     * @param label User-visible label for the clip data.
     * @param htmlContent The HTML content to be written to the clipboard.
     * @return A response indicating the success status of the write request.
     */
    public ClipboardWriteResponse writeHtml(String label, String htmlContent) {
        if (clipboard != null) {
            try {
                // Create plain text fallback by stripping HTML tags
                String plainText = Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY).toString();
                
                // Create ClipData with both HTML and plain text
                ClipData data = ClipData.newHtmlText(label, plainText, htmlContent);
                clipboard.setPrimaryClip(data);
                
                return new ClipboardWriteResponse(true);
            } catch (Exception e) {
                Logger.error(TAG, e);
                return new ClipboardWriteResponse(false, "Writing HTML to the clipboard failed");
            }
        } else {
            return new ClipboardWriteResponse(false, "Problem getting a reference to the system clipboard");
        }
    }

    /**
     * Reads data from the clipboard.
     * @return Data from the clipboard or null if no reference to the system clipboard.
     */
    public ClipboardData read() {
        if (clipboard != null) {
            CharSequence value = null;
            String htmlText = null;
            String type = "text/plain";

            if (clipboard.hasPrimaryClip()) {
                ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                ClipDescription description = clipboard.getPrimaryClipDescription();

                // Check for HTML first (more specific)
                if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
                    Logger.debug(TAG, "Got HTML text");
                    htmlText = item.getHtmlText();
                    if (htmlText != null) {
                        value = htmlText;
                        type = "text/html";
                    } else {
                        // Fallback to plain text if HTML is null
                        value = item.getText();
                    }
                } else if (clipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    Logger.debug(TAG, "Got plaintxt");
                    value = item.getText();
                } else {
                    Logger.debug(TAG, "Not plaintext!");
                    value = item.coerceToText(context).toString();
                }
            }

            ClipboardData clipboardData = new ClipboardData();

            if (value != null) {
                clipboardData.setValue(value.toString());
            }

            // Check if it's a data URL (for images)
            if (value != null && value.toString().startsWith("data:")) {
                type = value.toString().split(";")[0].split(":")[1];
            }

            clipboardData.setType(type);
            return clipboardData;
        }

        return null;
    }
}