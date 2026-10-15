package com.capacitor.ncvplugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.ProviderException;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Beveiligde opslag (inloggegevens) van de app.
 *
 * Waarden staan in de SharedPreferences "ncv_secure_storage" als "1:" + Base64(iv) + ":" + Base64(ciphertext en tag),
 * versleuteld met AES-256-GCM en de sleutelnaam als AAD. De AES-sleutel staat in de AndroidKeyStore (alias
 * "ncv_secure_storage") en vereist geen apparaatauthenticatie, net als de Keychain-items op iOS.
 *
 * Waarden van de vroegere Cordova-plugin (LegacySecureStorage) worden overgezet met migrate() of bij het eerste get().
 * Een waarde in deze opslag gaat altijd voor; set, remove en clear verwijderen ook de oude waarde. Daardoor komt een
 * oude inlog na uitloggen of een reset niet terug en is geen aparte migratiestatus nodig.
 * Het bestand hoort niet in backups (zie de backupregels van de app): zonder de Keystore-sleutel is het onbruikbaar.
 */
class SecureStorage {

    static final String NAAM = "ncv_secure_storage"; //SharedPreferences en Keystore-alias
    private static final String VERSIE = "1";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences prefs;
    private final LegacySecureStorage legacy;

    SecureStorage(Context context) {
        prefs = context.getSharedPreferences(NAAM, Context.MODE_PRIVATE);
        legacy = new LegacySecureStorage(context);
    }

    /**
     * SharedPreferences.commit() past het geheugen al aan voordat het naar schijf schrijft, ook als dat daarna mislukt.
     * Zet het geheugen dan terug, zodat contains/getAll de schijf blijven volgen: anders zou een oude waarde worden
     * gewist voor een nieuwe die niet is opgeslagen, of een tweede poging tot wissen "lukken" zonder te schrijven.
     */
    static boolean commit(SharedPreferences prefs, Consumer<SharedPreferences.Editor> wijziging) {
        Map<String, ?> voor = new HashMap<>(prefs.getAll());
        SharedPreferences.Editor editor = prefs.edit();
        wijziging.accept(editor);
        if (editor.commit()) {
            return true;
        }
        SharedPreferences.Editor terug = prefs.edit().clear();
        for (Map.Entry<String, ?> waarde : voor.entrySet()) {
            if (waarde.getValue() instanceof String) {
                terug.putString(waarde.getKey(), (String) waarde.getValue());
            }
        }
        terug.commit();
        return false;
    }

    static class MigrateResult {

        final List<String> migrated = new ArrayList<>();
        final Map<String, String> failed = new LinkedHashMap<>(); //sleutel -> foutcode

        boolean authRequired() {
            return failed.containsValue(SecureStorageException.AUTH_REQUIRED);
        }
    }

    /** null als de sleutel niet bestaat; een sleutel die wel bestaat maar niet leesbaar is geeft een fout */
    synchronized String get(String key) throws SecureStorageException {
        Object record = prefs.getAll().get(key);
        if (record != null) {
            return decrypt(key, record);
        }
        if (!legacy.has(key)) {
            return null;
        }
        String value = legacy.get(key);
        if (value != null) {
            try {
                zetOver(key, value);
            } catch (SecureStorageException e) {
                Logger.warn("SecureStorage", e.getMessage()); //de oude waarde blijft staan voor een volgende poging
            }
        }
        return value;
    }

    synchronized void set(String key, String value) throws SecureStorageException {
        store(key, value);
        if (legacy.has(key)) {
            try {
                legacy.remove(key);
            } catch (SecureStorageException e) {
                //de nieuwe waarde gaat voor, de oude wordt bij een volgende remove of clear alsnog gewist
                Logger.warn("SecureStorage", e.getMessage());
            }
        }
    }

    synchronized void remove(String key) throws SecureStorageException {
        if (prefs.contains(key) && !commit(prefs, editor -> editor.remove(key))) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Verwijderen mislukt");
        }
        legacy.remove(key);
    }

    synchronized List<String> keys() {
        TreeSet<String> keys = new TreeSet<>(prefs.getAll().keySet());
        keys.addAll(legacy.keys());
        return new ArrayList<>(keys);
    }

    /** Wist alleen deze opslag en de oude opslag van service my_app; de Keystore-sleutel blijft voor nieuwe waarden */
    synchronized void clear() throws SecureStorageException {
        if (!prefs.getAll().isEmpty() && !commit(prefs, SharedPreferences.Editor::clear)) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Wissen mislukt");
        }
        legacy.clear();
    }

    /** Zet alle oude waarden over die nu leesbaar zijn; wat niet lukt blijft staan voor een volgende poging */
    synchronized MigrateResult migrate() {
        MigrateResult result = new MigrateResult();
        for (String key : legacy.keys()) {
            try {
                if (prefs.contains(key)) {
                    legacy.remove(key); //later opgeslagen, dus nieuwer dan de oude waarde
                    continue;
                }
                String value = legacy.get(key);
                if (value != null) {
                    zetOver(key, value);
                    result.migrated.add(key);
                }
            } catch (SecureStorageException e) {
                result.failed.put(key, e.code);
            }
        }
        legacy.opruimenAlsLeeg();
        return result;
    }

    /** Oude waarde opslaan, teruglezen en pas dan de oude verwijderen */
    private void zetOver(String key, String value) throws SecureStorageException {
        store(key, value);
        String terug;
        try {
            terug = decrypt(key, prefs.getAll().get(key));
        } catch (SecureStorageException e) {
            terug = null;
        }
        if (!value.equals(terug)) {
            commit(prefs, editor -> editor.remove(key)); //de oude waarde blijft de bron
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Overgezette waarde komt niet overeen");
        }
        try {
            legacy.remove(key);
        } catch (SecureStorageException e) {
            Logger.warn("SecureStorage", e.getMessage());
        }
    }

    private void store(String key, String value) throws SecureStorageException {
        String record;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sleutel(true)); //de Keystore kiest een willekeurige IV
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            record = VERSIE + ":" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (GeneralSecurityException | ProviderException e) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Versleutelen mislukt: " + e.getClass().getSimpleName(), e);
        }
        String nieuw = record;
        if (!commit(prefs, editor -> editor.putString(key, nieuw))) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Opslaan mislukt");
        }
    }

    private String decrypt(String key, Object record) throws SecureStorageException {
        String[] delen = record instanceof String ? ((String) record).split(":", -1) : new String[0];
        if (delen.length != 3 || !VERSIE.equals(delen[0])) {
            throw new SecureStorageException(SecureStorageException.INVALID_RECORD, "Opgeslagen waarde heeft een onbekende indeling");
        }
        byte[] iv, ct;
        try {
            iv = Base64.decode(delen[1], Base64.NO_WRAP);
            ct = Base64.decode(delen[2], Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            throw new SecureStorageException(SecureStorageException.INVALID_RECORD, "Opgeslagen waarde is onleesbaar", e);
        }
        SecretKey sleutel = sleutel(false);
        if (sleutel == null) {
            throw new SecureStorageException(SecureStorageException.KEY_MISSING, "De sleutel van de opslag ontbreekt");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sleutel, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(key.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new SecureStorageException(SecureStorageException.DECRYPT_FAILED, "Ontsleutelen mislukt: " + e.getClass().getSimpleName(), e);
        } catch (ProviderException e) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Keystore-fout: " + e.getClass().getSimpleName(), e);
        }
    }

    /** De AES-sleutel; maakt hem alleen aan voor het opslaan (een lege opslag heeft geen sleutel nodig) */
    private SecretKey sleutel(boolean maak) throws SecureStorageException {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            try {
                Key key = keyStore.getKey(NAAM, null);
                if (key instanceof SecretKey) {
                    return (SecretKey) key;
                }
            } catch (UnrecoverableKeyException e) {
                if (!maak) {
                    throw new SecureStorageException(SecureStorageException.KEY_INVALIDATED, "De sleutel van de opslag is onbruikbaar", e);
                }
                //onherstelbaar: wat ermee versleuteld is, is al verloren; voor nieuwe waarden een nieuwe sleutel
                keyStore.deleteEntry(NAAM);
            }
            if (!maak) {
                return null;
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                new KeyGenParameterSpec.Builder(NAAM, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            );
            return generator.generateKey();
        } catch (GeneralSecurityException | IOException | ProviderException e) {
            throw new SecureStorageException(SecureStorageException.OS_ERROR, "Keystore niet bereikbaar: " + e.getClass().getSimpleName(), e);
        }
    }
}
