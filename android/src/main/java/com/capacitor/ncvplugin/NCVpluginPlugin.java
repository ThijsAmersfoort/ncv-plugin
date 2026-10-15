package com.capacitor.ncvplugin;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Map;

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
    private SecureStorage secureStorage;

    @Override
    public void load() {
        CBimplementation = new Clipboard(getContext());
        secureStorage = new SecureStorage(getContext().getApplicationContext());
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

    // Beveiligde opslag (de opslag- en overzetlogica staat in SecureStorage en LegacySecureStorage)

    @PluginMethod
    public void secureGet(PluginCall call) {
        String key = call.getString("key");
        if (key == null) {
            call.reject("key is verplicht", "INVALID_ARGUMENT");
            return;
        }
        try {
            String value = secureStorage.get(key);
            JSObject ret = new JSObject();
            if (value != null) ret.put("value", value); //ontbrekende sleutel: geen value
            call.resolve(ret);
        } catch (SecureStorageException e) {
            call.reject(e.getMessage(), e.code);
        }
    }

    @PluginMethod
    public void secureSet(PluginCall call) {
        String key = call.getString("key");
        String value = call.getString("value");
        if (key == null || value == null) {
            call.reject("key en value zijn verplicht", "INVALID_ARGUMENT");
            return;
        }
        try {
            secureStorage.set(key, value);
            call.resolve();
        } catch (SecureStorageException e) {
            call.reject(e.getMessage(), e.code);
        }
    }

    @PluginMethod
    public void secureRemove(PluginCall call) {
        String key = call.getString("key");
        if (key == null) {
            call.reject("key is verplicht", "INVALID_ARGUMENT");
            return;
        }
        try {
            secureStorage.remove(key);
            call.resolve();
        } catch (SecureStorageException e) {
            call.reject(e.getMessage(), e.code);
        }
    }

    @PluginMethod
    public void secureKeys(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("keys", new JSArray(secureStorage.keys()));
        call.resolve(ret);
    }

    @PluginMethod
    public void secureClear(PluginCall call) {
        try {
            secureStorage.clear();
            call.resolve();
        } catch (SecureStorageException e) {
            call.reject(e.getMessage(), e.code);
        }
    }

    /**
     * Zet waarden van de vroegere Cordova-plugin over. Vereist de oude sleutel recente ontgrendeling (zoals bij de
     * start van de oude app), dan vraagt dit eenmalig de schermvergrendeling en probeert het daarna opnieuw.
     */
    @PluginMethod
    public void secureMigrate(PluginCall call) {
        SecureStorage.MigrateResult result = secureStorage.migrate();
        //staat de vraag al open (bijv. na een herlaad van de WebView), dan geen tweede: Capacitor koppelt het resultaat
        //alleen aan de laatst gestarte aanroep. De open vraag zet bij bevestigen alles over
        Intent bevestig = result.authRequired() && !bevestigingOpen ? bevestigApparaatIntent(call) : null;
        if (bevestig == null) {
            call.resolve(migrateResultaat(result, false));
            return;
        }
        bevestigingOpen = true;
        startActivityForResult(call, bevestig, "secureMigrateBevestigd");
    }

    private volatile boolean bevestigingOpen;

    @ActivityCallback
    private void secureMigrateBevestigd(PluginCall call, ActivityResult activityResult) {
        bevestigingOpen = false;
        boolean geannuleerd = activityResult.getResultCode() != Activity.RESULT_OK;
        execute(() -> { //niet op de main thread: Keystore en schijf. Ook zonder aanroep: het ontgrendelen geldt nu
            JSObject resultaat = migrateResultaat(secureStorage.migrate(), geannuleerd);
            if (call != null) {
                call.resolve(resultaat);
                getBridge().releaseCall(call);
            }
        });
    }

    @SuppressWarnings("deprecation") //createConfirmDeviceCredentialIntent: wat de oude plugin ook gebruikte, werkt zonder biometrie-dependency
    private Intent bevestigApparaatIntent(PluginCall call) {
        KeyguardManager keyguard = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure() || getActivity() == null) return null;
        return keyguard.createConfirmDeviceCredentialIntent(call.getString("title"), call.getString("description"));
    }

    private static JSObject migrateResultaat(SecureStorage.MigrateResult result, boolean geannuleerd) {
        JSObject failed = new JSObject();
        for (Map.Entry<String, String> fout : result.failed.entrySet()) {
            boolean auth = SecureStorageException.AUTH_REQUIRED.equals(fout.getValue());
            failed.put(fout.getKey(), auth && geannuleerd ? SecureStorageException.AUTH_CANCELLED : fout.getValue());
        }
        JSObject ret = new JSObject();
        ret.put("migrated", new JSArray(result.migrated));
        ret.put("failed", failed);
        return ret;
    }
}
