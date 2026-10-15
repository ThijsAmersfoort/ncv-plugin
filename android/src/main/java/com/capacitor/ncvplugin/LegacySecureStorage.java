package com.capacitor.ncvplugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Base64;
import com.getcapacitor.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Leest de opslag van cordova-plugin-secure-storage-echo 5.1.1 (service my_app), alleen om waarden over te zetten.
 *
 * Indeling volgens SecureStorage.java, SharedPreferencesHandler.java, RSA.java en AES.java van die plugin:
 * SharedPreferences "&lt;package&gt;.my_app_SS", sleutel "_SS_" + naam, waarde JSON
 * {key: AES-sleutel versleuteld met RSA/ECB/PKCS1Padding, value: {iv, mode: "GCM", cipher: "AES", adata, ct}, native: true},
 * binaire velden als Base64.DEFAULT (met regelafbrekingen). De RSA-sleutel staat in de AndroidKeyStore onder
 * "&lt;package&gt;.my_app" en vereist apparaatauthenticatie in de laatste 24 uur.
 *
 * Maakt nooit een sleutel aan: een nieuwe sleutel onder deze alias ontsleutelt de bestaande records niet.
 * De sleutel wordt pas verwijderd als er geen oude waarden meer zijn.
 */
class LegacySecureStorage {

    private static final String SERVICE = "my_app";
    private static final String PREFIX = "_SS_";
    private static final String MIGRATED_PREFIX = "_SS_MIGRATED_"; //markering van een nog oudere pluginversie, geen waarde
    private static final int GCM_TAG_BITS = 128; //de oude plugin gaf alleen de IV mee: GCM met de standaardtag

    private final SharedPreferences prefs;
    private final String alias;

    LegacySecureStorage(Context context) {
        alias = context.getPackageName() + "." + SERVICE;
        prefs = context.getSharedPreferences(alias + "_SS", Context.MODE_PRIVATE);
    }

    Set<String> keys() {
        Set<String> keys = new HashSet<>();
        for (String naam : prefs.getAll().keySet()) {
            if (naam.startsWith(PREFIX) && !naam.startsWith(MIGRATED_PREFIX)) {
                keys.add(naam.substring(PREFIX.length()));
            }
        }
        return keys;
    }

    boolean has(String key) {
        return prefs.contains(PREFIX + key);
    }

    /** null als de sleutel niet bestaat */
    String get(String key) throws SecureStorageException {
        Object record = prefs.getAll().get(PREFIX + key);
        if (record == null) {
            return null;
        }
        if (!(record instanceof String)) {
            throw new SecureStorageException(SecureStorageException.INVALID_RECORD, "Oude waarde heeft een onbekende indeling");
        }

        byte[] encKey, iv, adata, ct;
        try {
            JSONObject json = new JSONObject((String) record);
            JSONObject value = json.getJSONObject("value");
            if (!"GCM".equals(value.optString("mode")) || !"AES".equals(value.optString("cipher"))) {
                throw new SecureStorageException(SecureStorageException.INVALID_RECORD, "Oude waarde heeft een onbekende indeling");
            }
            encKey = Base64.decode(json.getString("key"), Base64.DEFAULT);
            iv = Base64.decode(value.getString("iv"), Base64.DEFAULT);
            adata = Base64.decode(value.getString("adata"), Base64.DEFAULT);
            ct = Base64.decode(value.getString("ct"), Base64.DEFAULT);
        } catch (JSONException | IllegalArgumentException e) {
            throw new SecureStorageException(SecureStorageException.INVALID_RECORD, "Oude waarde is onleesbaar", e);
        }

        PrivateKey privateKey = privateKey();
        byte[] aesKey = null;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, privateKey);
            aesKey = rsa.doFinal(encKey);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            aes.updateAAD(adata);
            return new String(aes.doFinal(ct), StandardCharsets.UTF_8);
        } catch (UserNotAuthenticatedException e) {
            throw new SecureStorageException(SecureStorageException.AUTH_REQUIRED, "De oude sleutel vereist ontgrendeling van het apparaat", e);
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new SecureStorageException(SecureStorageException.KEY_INVALIDATED, "De oude sleutel is ongeldig geworden", e);
        } catch (GeneralSecurityException e) {
            throw new SecureStorageException(SecureStorageException.DECRYPT_FAILED, "Ontsleutelen van oude waarde mislukt: " + e.getClass().getSimpleName(), e);
        } catch (ProviderException e) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Keystore-fout: " + e.getClass().getSimpleName(), e);
        } finally {
            if (aesKey != null) {
                Arrays.fill(aesKey, (byte) 0);
            }
        }
    }

    void remove(String key) throws SecureStorageException {
        if (prefs.contains(PREFIX + key) && !SecureStorage.commit(prefs, editor -> editor.remove(PREFIX + key))) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Oude waarde verwijderen mislukt");
        }
        opruimenAlsLeeg();
    }

    void clear() throws SecureStorageException {
        if (!prefs.getAll().isEmpty() && !SecureStorage.commit(prefs, SharedPreferences.Editor::clear)) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Oude opslag wissen mislukt");
        }
        verwijderSleutel();
    }

    /** Geen oude waarden meer: ook de markeringen en de oude RSA-sleutel opruimen */
    void opruimenAlsLeeg() {
        if (!keys().isEmpty()) {
            return;
        }
        try {
            clear();
        } catch (SecureStorageException e) {
            Logger.warn("SecureStorage", e.getMessage());
        }
    }

    private void verwijderSleutel() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias);
            }
        } catch (GeneralSecurityException | IOException | ProviderException e) {
            Logger.warn("SecureStorage", "Oude sleutel verwijderen mislukt: " + e.getClass().getSimpleName());
        }
    }

    private PrivateKey privateKey() throws SecureStorageException {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            Key key = keyStore.getKey(alias, null);
            if (key instanceof PrivateKey) {
                return (PrivateKey) key;
            }
        } catch (UnrecoverableKeyException e) {
            throw new SecureStorageException(SecureStorageException.KEY_INVALIDATED, "De oude sleutel is onbruikbaar", e);
        } catch (GeneralSecurityException | IOException | ProviderException e) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Keystore niet bereikbaar: " + e.getClass().getSimpleName(), e);
        }
        //bijv. verwijderd bij het uitzetten van de schermvergrendeling, of niet meegekomen bij een herstelde backup
        throw new SecureStorageException(SecureStorageException.KEY_MISSING, "De oude sleutel ontbreekt");
    }
}
