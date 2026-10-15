package com.capacitor.ncvplugin;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.sqlite.driver.bundled.BundledSQLite;
import androidx.sqlite.driver.bundled.BundledSQLiteDriver;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SQLite met de AndroidX bundled driver (eigen engine met FTS5; de systeem-SQLite van Android heeft geen FTS5).
 * Eén executor per geopend bestand; openen, sluiten en verwijderen lopen over één beheerexecutor.
 * De verbindingen horen bij het proces, niet bij een pagina: na een herlaad van de WebView komt bij openen de bestaande verbinding terug.
 */
final class SQLiteService {

    interface Klaar {
        void klaar(JSObject resultaat, SQLiteFout fout);
    }

    private interface Taak {
        JSObject uitvoeren() throws SQLiteFout;
    }

    private static final class Geopend {

        final String id;
        final String naam;
        final String modus;
        final SQLiteVerbinding verbinding;
        final ExecutorService queue = Executors.newSingleThreadExecutor();
        final JSObject info;

        Geopend(String id, String naam, String modus, SQLiteVerbinding verbinding, JSObject info) {
            this.id = id;
            this.naam = naam;
            this.modus = modus;
            this.verbinding = verbinding;
            this.info = info;
        }
    }

    private static SQLiteService gedeeld;

    static synchronized SQLiteService gedeeld(Context context) {
        if (gedeeld == null) gedeeld = new SQLiteService(context.getApplicationContext());
        return gedeeld;
    }

    private final Context context;
    private final BundledSQLiteDriver driver = new BundledSQLiteDriver();
    private final ExecutorService beheer = Executors.newSingleThreadExecutor();
    private final Map<String, Geopend> geopend = new HashMap<>(); //databaseId → verbinding
    //naam → databaseId; beide modi staan in getDatabasePath, dus de naam is het fysieke bestand
    private final Map<String, String> perBestand = new HashMap<>();

    //package-private voor de instrumented tests (eigen databasemap via een ContextWrapper); de plugin gebruikt gedeeld()
    SQLiteService(Context context) {
        this.context = context;
    }

    private static void starten(ExecutorService queue, Taak taak, Klaar klaar) {
        try {
            queue.execute(() -> {
                JSObject resultaat;
                try {
                    resultaat = taak.uitvoeren();
                } catch (SQLiteFout fout) {
                    klaar.klaar(null, fout);
                    return;
                } catch (RuntimeException e) {
                    klaar.klaar(null, new SQLiteFout("SQLITE_ERROR", String.valueOf(e.getMessage())));
                    return;
                }
                klaar.klaar(resultaat, null);
            });
        } catch (RejectedExecutionException e) {
            klaar.klaar(null, new SQLiteFout("DB_CLOSED", "Database is niet (meer) open"));
        }
    }

    // openen, sluiten, verwijderen

    void open(String naam, String modus, String seed, String contentId, Klaar klaar) {
        starten(beheer, () -> openNu(naam, modus, seed, contentId), klaar);
    }

    private JSObject openNu(String naam, String modus, String seed, String contentId) throws SQLiteFout {
        controleerNaam(naam);
        if (seed != null) controleerNaam(seed);
        synchronized (this) {
            Geopend bestaand = geopend.get(perBestand.get(naam));
            if (bestaand != null) {
                //hetzelfde bestand in een andere modus zou de open database vervangen of anders openen
                if (!bestaand.modus.equals(modus)) throw new SQLiteFout("DB_IN_USE", naam + " is al open als " + bestaand.modus, naam);
                return kopie(bestaand.info).put("reused", true);
            }
        }

        //dezelfde plek als de vorige Cordova-plugin: Context.getDatabasePath
        File doel = context.getDatabasePath(naam);
        File map = doel.getParentFile();
        if (map != null && !map.isDirectory() && !map.mkdirs()) throw new SQLiteFout("OPEN_FAILED", "Databasemap niet aan te maken", naam);

        SQLiteVerbinding verbinding;
        JSObject info = new JSObject();
        boolean schrijfbaar;
        if ("bundled".equals(modus)) {
            boolean gekopieerd = basisBijwerken(naam, contentId, doel);
            verbinding = new SQLiteVerbinding(driver, alleenLezen(doel), BundledSQLite.SQLITE_OPEN_READONLY | BundledSQLite.SQLITE_OPEN_URI, naam);
            info.put("readOnly", true).put("source", "copy").put("copied", gekopieerd);
            schrijfbaar = false;
        } else if ("persistent".equals(modus)) {
            boolean gekopieerd = false;
            if (seed != null && !doel.exists()) {
                kopieerSeed(seed, doel);
                gekopieerd = true;
            }
            //met een seed nooit stil een lege database aanmaken
            int flags = BundledSQLite.SQLITE_OPEN_READWRITE | (seed == null ? BundledSQLite.SQLITE_OPEN_CREATE : 0);
            verbinding = new SQLiteVerbinding(driver, doel.getAbsolutePath(), flags, naam);
            info.put("readOnly", false).put("source", "file").put("copied", gekopieerd);
            schrijfbaar = true;
        } else {
            throw new SQLiteFout("INVALID_ARGUMENT", "Onbekende modus " + modus, naam);
        }

        try {
            verbinding.instellen(schrijfbaar);
            info.put("userVersion", verbinding.userVersion());
        } catch (SQLiteFout fout) {
            verbinding.sluiten();
            throw fout;
        }
        String id = UUID.randomUUID().toString();
        info.put("databaseId", id).put("reused", false).put("sqliteVersion", sqliteVersie(verbinding));
        synchronized (this) {
            geopend.put(id, new Geopend(id, naam, modus, verbinding, info));
            perBestand.put(naam, id);
        }
        return kopie(info);
    }

    /**
     * Beheerde kopie van de alleen-lezen basisdatabase. Een APK-asset is geen los bestand, dus SQLite kan hem niet direct openen.
     * Vervangen alleen als de inhoud uit de app anders is (contentId uit de build, anders bij elke app-update),
     * via een gecontroleerde kandidaat en een atomaire rename; de oude kopie blijft staan tot de nieuwe klaar is.
     */
    private boolean basisBijwerken(String naam, String contentId, File doel) throws SQLiteFout {
        String bron = contentId != null && !contentId.isEmpty() ? "sha256:" + contentId.toLowerCase(Locale.ROOT) : "app:" + appBijgewerkt();
        File merk = new File(doel.getParentFile(), naam + ".bron");
        if (doel.isFile() && (bron + "\n" + doel.length()).equals(lees(merk))) return false;

        File kandidaat = new File(doel.getParentFile(), naam + ".nieuw");
        try {
            String hash = kopieerAsset("public/" + naam, kandidaat, naam);
            if (contentId != null && !contentId.isEmpty() && !hash.equalsIgnoreCase(contentId)) {
                throw new SQLiteFout("COPY_FAILED", naam + " in de app wijkt af van de build (SHA-256)", naam);
            }
            //de kandidaat moet een bruikbare database zijn voordat de oude kopie verdwijnt
            new SQLiteVerbinding(driver, alleenLezen(kandidaat), BundledSQLite.SQLITE_OPEN_READONLY | BundledSQLite.SQLITE_OPEN_URI, naam).sluitNaControle();
            //een journal van de oude, ooit schrijfbare kopie mag nooit op de nieuwe worden toegepast
            verwijderBijbestanden(doel);
            if (!kandidaat.renameTo(doel)) throw new SQLiteFout("COPY_FAILED", naam + " vervangen mislukt", naam);
            schrijfAtomair(merk, bron + "\n" + doel.length());
            return true;
        } finally {
            if (kandidaat.exists()) kandidaat.delete();
        }
    }

    /** Kopie via een tijdelijk bestand in dezelfde map; pas na de rename bestaat de database. */
    private void kopieerSeed(String seed, File doel) throws SQLiteFout {
        //losse journal/WAL-bestanden van een eerder verwijderde database mogen niet op de nieuwe seed worden toegepast
        verwijderBijbestanden(doel);
        File tijdelijk = new File(doel.getParentFile(), "." + doel.getName() + ".seed");
        try {
            kopieerAsset("public/" + seed, tijdelijk, doel.getName());
            if (!tijdelijk.renameTo(doel)) throw new SQLiteFout("COPY_FAILED", seed + " kopiëren mislukt", doel.getName());
        } finally {
            if (tijdelijk.exists()) tijdelijk.delete();
        }
    }

    /** Stroomt een asset naar een bestand (fsync) en geeft de SHA-256 terug. */
    private String kopieerAsset(String asset, File naar, String database) throws SQLiteFout {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new SQLiteFout("COPY_FAILED", "SHA-256 niet beschikbaar", database);
        }
        try (InputStream in = context.getAssets().open(asset); FileOutputStream out = new FileOutputStream(naar)) {
            byte[] buffer = new byte[1 << 16];
            int gelezen;
            while ((gelezen = in.read(buffer)) > 0) {
                sha.update(buffer, 0, gelezen);
                out.write(buffer, 0, gelezen);
            }
            out.getFD().sync();
        } catch (FileNotFoundException e) {
            throw new SQLiteFout("ASSET_NOT_FOUND", asset + " staat niet in de app", database);
        } catch (IOException e) {
            throw new SQLiteFout("COPY_FAILED", asset + " kopiëren mislukt: " + e.getMessage(), database);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private void verwijderBijbestanden(File database) throws SQLiteFout {
        for (String achtervoegsel : new String[] { "-journal", "-wal", "-shm" }) {
            File bestand = new File(database.getPath() + achtervoegsel);
            if (bestand.exists() && !bestand.delete()) {
                throw new SQLiteFout("DELETE_FAILED", bestand.getName() + " niet te verwijderen", database.getName());
            }
        }
    }

    private static String lees(File bestand) {
        if (!bestand.isFile()) return null;
        try (FileInputStream in = new FileInputStream(bestand)) {
            byte[] inhoud = new byte[(int) Math.min(bestand.length(), 4096)];
            int gelezen = in.read(inhoud);
            return gelezen <= 0 ? "" : new String(inhoud, 0, gelezen, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void schrijfAtomair(File bestand, String inhoud) throws SQLiteFout {
        File tijdelijk = new File(bestand.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tijdelijk)) {
            out.write(inhoud.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            tijdelijk.delete();
            throw new SQLiteFout("COPY_FAILED", bestand.getName() + " schrijven mislukt: " + e.getMessage());
        }
        if (!tijdelijk.renameTo(bestand)) {
            tijdelijk.delete();
            throw new SQLiteFout("COPY_FAILED", bestand.getName() + " schrijven mislukt");
        }
    }

    private long appBijgewerkt() throws SQLiteFout {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new SQLiteFout("COPY_FAILED", "Appversie onbekend");
        }
    }

    /** file:-URI voor een onveranderlijk bestand: alleen lezen, zonder locking en wijzigingsdetectie. */
    private static String alleenLezen(File bestand) {
        StringBuilder uri = new StringBuilder("file:");
        for (byte b : bestand.getAbsolutePath().getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "/-._~".indexOf(c) >= 0) uri.append(c);
            else uri.append(String.format(Locale.ROOT, "%%%02X", b & 0xff));
        }
        return uri.append("?mode=ro&immutable=1").toString();
    }

    private static String sqliteVersie(SQLiteVerbinding verbinding) throws SQLiteFout {
        List<JSObject> rijen = verbinding.uitvoeren("SELECT sqlite_version() AS versie", null, SQLiteVerbinding.Resultaat.FIRST);
        return rijen.isEmpty() ? "" : rijen.get(0).optString("versie");
    }

    /**
     * Sluit na alle eerder aangeboden aanvragen van deze database; nieuwe aanvragen krijgen direct DB_CLOSED.
     * Afmelden en het sluiten in de executor zetten gebeurt onder hetzelfde slot als het toelaten van aanvragen.
     * Het resultaat komt via de beheerexecutor, zodat een daarna aangeboden openen of verwijderen pas na het sluiten loopt.
     */
    void sluiten(String id, Klaar klaar) {
        Geopend item;
        synchronized (this) {
            item = geopend.remove(id);
            if (item != null) {
                if (id.equals(perBestand.get(item.naam))) perBestand.remove(item.naam);
                item.queue.execute(item.verbinding::sluiten);
                item.queue.shutdown();
            }
        }
        if (item == null) {
            klaar.klaar(null, new SQLiteFout("DB_CLOSED", "Database is niet (meer) open"));
            return;
        }
        starten(
            beheer,
            () -> {
                boolean onderbroken = false;
                while (true) {
                    try {
                        if (item.queue.awaitTermination(1, TimeUnit.MINUTES)) break;
                    } catch (InterruptedException e) {
                        onderbroken = true;
                    }
                }
                if (onderbroken) Thread.currentThread().interrupt();
                return new JSObject();
            },
            klaar
        );
    }

    /** Verwijdert een gesloten persistent-database met journal/WAL-bestanden. Een ontbrekend bestand is geen fout. */
    void verwijderen(String naam, Klaar klaar) {
        starten(
            beheer,
            () -> {
                controleerNaam(naam);
                synchronized (this) {
                    if (perBestand.containsKey(naam)) throw new SQLiteFout("DB_IN_USE", naam + " is nog open", naam);
                }
                File doel = context.getDatabasePath(naam);
                if (doel.exists() && !doel.delete()) throw new SQLiteFout("DELETE_FAILED", naam + " verwijderen mislukt", naam);
                verwijderBijbestanden(doel);
                return new JSObject();
            },
            klaar
        );
    }

    // uitvoeren

    private interface Werk {
        JSObject uitvoeren(SQLiteVerbinding verbinding) throws SQLiteFout;
    }

    /** Zet het werk onder het slot in de executor: een gelijktijdig sluiten komt er altijd vóór of na, nooit tussendoor. */
    private synchronized void aanbieden(String id, Werk werk, Klaar klaar) {
        Geopend item = geopend.get(id);
        if (item == null) {
            klaar.klaar(null, new SQLiteFout("DB_CLOSED", "Database is niet (meer) open"));
            return;
        }
        starten(item.queue, () -> werk.uitvoeren(item.verbinding), klaar);
    }

    void uitvoeren(String id, String sql, org.json.JSONArray waarden, SQLiteVerbinding.Resultaat resultaat, Klaar klaar) {
        aanbieden(
            id,
            verbinding -> {
                List<JSObject> rijen = verbinding.uitvoeren(sql, waarden, resultaat);
                return resultaat == SQLiteVerbinding.Resultaat.NONE ? new JSObject() : new JSObject().put("rows", new JSArray(rijen));
            },
            klaar
        );
    }

    void batch(String id, List<SQLiteVerbinding.Statement> statements, SQLiteVerbinding.Resultaat resultaat, Klaar klaar) {
        aanbieden(
            id,
            verbinding -> {
                List<List<JSObject>> uitkomst = verbinding.batch(statements, resultaat);
                if (resultaat == SQLiteVerbinding.Resultaat.NONE) return new JSObject();
                JSArray results = new JSArray();
                for (List<JSObject> rijen : uitkomst) results.put(new JSArray(rijen));
                return new JSObject().put("results", results);
            },
            klaar
        );
    }

    private static JSObject kopie(JSObject info) {
        try {
            return new JSObject(info.toString());
        } catch (org.json.JSONException e) {
            return new JSObject();
        }
    }

    private static void controleerNaam(String naam) throws SQLiteFout {
        if (naam == null || naam.isEmpty() || naam.startsWith(".") || !naam.matches("[A-Za-z0-9._-]+")) {
            throw new SQLiteFout("INVALID_ARGUMENT", "Ongeldige bestandsnaam: " + naam);
        }
    }
}
