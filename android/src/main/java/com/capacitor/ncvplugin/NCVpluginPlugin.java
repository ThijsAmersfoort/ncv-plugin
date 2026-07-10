package com.capacitor.ncvplugin;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "NCVplugin")
public class NCVpluginPlugin extends Plugin {

    @PluginMethod
    public void info(PluginCall call) {
        String platform = "android";
        String release = android.os.Build.VERSION.RELEASE;

        JSObject ret = new JSObject();
        ret.put("platform", platform);
        ret.put("version", release);
        call.resolve(ret);
    }

    private Clipboard CBimplementation;

    @Override
    public void load() {
        CBimplementation = new Clipboard(getContext());
    }

    @PluginMethod
    public void ClipboardWrite(PluginCall call) {
        String htmlVal = call.getString("html");
        String strVal = call.getString("string");
        String imageVal = call.getString("image");
        String urlVal = call.getString("url");
        String label = call.getString("label");

        ClipboardWriteResponse response;

        if (htmlVal != null) {
            response = CBimplementation.writeHtml(label, htmlVal);
        } else if (strVal != null) {
            response = CBimplementation.write(label, strVal);
        } else if (imageVal != null) {
            response = CBimplementation.write(label, imageVal);
        } else if (urlVal != null) {
            response = CBimplementation.write(label, urlVal);
        } else {
            call.reject("No data provided");
            return;
        }

        if (response.isSuccess()) {
            call.resolve();
        } else {
            call.reject(response.getErrorMessage());
        }
    }

    @PluginMethod
    public void ClipboardRead(PluginCall call) {
        ClipboardData result = CBimplementation.read();

        if (result == null) {
            call.reject("Unable to read clipboard from the given Context");
        } else if (result.getValue() == null) {
            call.reject("There is no data on the clipboard");
        } else {
            JSObject resultJS = new JSObject();
            resultJS.put("value", result.getValue());
            resultJS.put("type", result.getType());

            call.resolve(resultJS);
        }
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("completed", true);
        call.resolve(ret);
    }
}
