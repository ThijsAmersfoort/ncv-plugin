package com.capacitor.ncvplugin;

import static com.capacitor.ncvplugin.SQLiteVerbinding.Resultaat.ALL;
import static com.capacitor.ncvplugin.SQLiteVerbinding.Resultaat.FIRST;
import static com.capacitor.ncvplugin.SQLiteVerbinding.Resultaat.NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.JSObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Contracttests voor SQLiteService op de AndroidX bundled driver; dezelfde gevallen als SQLiteServiceTests.swift,
 * plus de beheerde kopie van de basisdatabase. Assets: androidTest/assets/public/proefbasis.sqlite en proefuser.sqlite.
 */
@RunWith(AndroidJUnit4.class)
public class SQLiteServiceTest {

    private interface Start {
        void start(SQLiteService.Klaar klaar);
    }

    private interface Blok {
        void uitvoeren() throws Exception;
    }

    private Context context;
    private File map;
    private SQLiteService service;

    @Before
    public void opzetten() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        map = new File(context.getCacheDir(), "ncv-sqlite-" + System.nanoTime());
        assertTrue(map.mkdirs());
        //eigen databasemap per test; assets en pakketinfo komen van de echte context
        service = new SQLiteService(
            new ContextWrapper(context) {
                @Override
                public File getDatabasePath(String name) {
                    return new File(map, name);
                }
            }
        );
    }

    @After
    public void opruimen() {
        verwijderMap(map);
    }

    // hulpfuncties

    private static JSObject wacht(Start start) throws Exception {
        CompletableFuture<JSObject> uitkomst = new CompletableFuture<>();
        start.start((resultaat, fout) -> {
            if (fout == null) uitkomst.complete(resultaat);
            else uitkomst.completeExceptionally(fout);
        });
        try {
            return uitkomst.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static SQLiteFout fout(Blok blok) {
        try {
            blok.uitvoeren();
            return null;
        } catch (SQLiteFout f) {
            return f;
        } catch (Exception e) {
            throw new AssertionError("onverwachte fout", e);
        }
    }

    private static String code(Blok blok) {
        SQLiteFout f = fout(blok);
        return f == null ? null : f.code;
    }

    private JSObject open(String naam, String modus, String seed, String contentId) throws Exception {
        return wacht(k -> service.open(naam, modus, seed, contentId, k));
    }

    private JSObject open(String naam) throws Exception {
        return open(naam, "persistent", null, null);
    }

    private static String id(JSObject info) {
        return info.getString("databaseId");
    }

    private static JSONArray waarden(Object... waarden) {
        JSONArray array = new JSONArray();
        for (Object waarde : waarden) array.put(waarde == null ? JSONObject.NULL : waarde);
        return array;
    }

    private static SQLiteVerbinding.Statement st(String sql, Object... waarden) {
        return new SQLiteVerbinding.Statement(sql, waarden(waarden));
    }

    private List<JSONObject> sql(String id, SQLiteVerbinding.Resultaat resultaat, String sql, Object... waarden) throws Exception {
        JSObject res = wacht(k -> service.uitvoeren(id, sql, waarden(waarden), resultaat, k));
        JSONArray rows = res.optJSONArray("rows");
        List<JSONObject> rijen = new ArrayList<>();
        for (int i = 0; rows != null && i < rows.length(); i++) rijen.add(rows.getJSONObject(i));
        return rijen;
    }

    private List<JSONObject> sql(String id, String sql, Object... waarden) throws Exception {
        return sql(id, ALL, sql, waarden);
    }

    private JSObject batch(String id, SQLiteVerbinding.Resultaat resultaat, SQLiteVerbinding.Statement... statements) throws Exception {
        return wacht(k -> service.batch(id, Arrays.asList(statements), resultaat, k));
    }

    private long aantal(String id, String tabel) throws Exception {
        return sql(id, "SELECT count(*) AS n FROM " + tabel).get(0).getLong("n");
    }

    private void sluit(String id) throws Exception {
        wacht(k -> service.sluiten(id, k));
    }

    private void verwijder(String naam) throws Exception {
        wacht(k -> service.verwijderen(naam, k));
    }

    private String sha256Asset(String asset) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(asset)) {
            byte[] buffer = new byte[8192];
            int gelezen;
            while ((gelezen = in.read(buffer)) > 0) sha.update(buffer, 0, gelezen);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private static void schrijf(File bestand, String inhoud) throws IOException {
        try (FileOutputStream out = new FileOutputStream(bestand)) {
            out.write(inhoud.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String lees(File bestand) throws IOException {
        try (FileInputStream in = new FileInputStream(bestand)) {
            byte[] inhoud = new byte[(int) bestand.length()];
            int gelezen = in.read(inhoud);
            return new String(inhoud, 0, Math.max(gelezen, 0), StandardCharsets.UTF_8);
        }
    }

    private static void verwijderMap(File bestand) {
        File[] inhoud = bestand.listFiles();
        if (inhoud != null) for (File kind : inhoud) verwijderMap(kind);
        bestand.delete();
    }

    // tests

    @Test
    public void fts5EnBm25() throws Exception {
        String db = id(open("fts.sqlite"));
        batch(db, NONE, st("CREATE VIRTUAL TABLE t USING fts5(tekst)"), st("INSERT INTO t(tekst) VALUES (?), (?)", "In het begin schiep God", "Het Woord was bij God"));
        List<JSONObject> rijen = sql(db, "SELECT tekst, bm25(t) AS score FROM t WHERE t MATCH ? ORDER BY rank", "woord");
        assertEquals(1, rijen.size());
        assertEquals("Het Woord was bij God", rijen.get(0).getString("tekst"));
        assertTrue(rijen.get(0).get("score") instanceof Double);
    }

    @Test
    public void datatypen() throws Exception {
        String db = id(open("typen.sqlite"));
        sql(db, NONE, "CREATE TABLE t (a, b, c, d, e, f, g, h, i)");
        String groot = "9223372036854775807";
        String blob = Base64.encodeToString(new byte[] { 0, 1, (byte) 255 }, Base64.NO_WRAP);
        sql(
            db,
            NONE,
            "INSERT INTO t VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            JSONObject.NULL,
            "",
            0,
            false,
            1.5,
            "ëmoji 😀 עִבְרִית",
            "a\u0000b",
            new JSONObject().put("$blob", blob),
            new JSONObject().put("$int", groot)
        );
        JSONObject rij = sql(db, FIRST, "SELECT *, typeof(c) AS tc, typeof(d) AS td, length(CAST(g AS BLOB)) AS lg FROM t").get(0);
        assertSame(JSONObject.NULL, rij.get("a"));
        assertEquals("", rij.getString("b"));
        assertEquals("integer", rij.getString("tc"));
        assertEquals("integer", rij.getString("td"));
        assertEquals(1.5, rij.getDouble("e"), 0);
        assertEquals("ëmoji 😀 עִבְרִית", rij.getString("f"));
        assertEquals(3, rij.getInt("lg"));
        assertEquals("a\u0000b", rij.getString("g"));
        assertEquals(blob, rij.getJSONObject("h").getString("$blob"));
        assertEquals(groot, rij.getJSONObject("i").getString("$int"));

        //een geheel getal als double (zo kan een JavaScript-getal binnenkomen) wordt INTEGER
        assertEquals("integer", sql(db, FIRST, "SELECT typeof(?) AS soort", 5.0).get(0).getString("soort"));
        assertEquals("real", sql(db, FIRST, "SELECT typeof(?) AS soort", 5.5).get(0).getString("soort"));
        assertEquals("INVALID_VALUE", code(() -> sql(db, NONE, "SELECT ?", new JSONObject().put("x", 1))));
    }

    @Test
    public void resultaatvormen() throws Exception {
        String db = id(open("vorm.sqlite"));
        sql(db, NONE, "CREATE TABLE t (x)");
        assertEquals(0, sql(db, FIRST, "SELECT * FROM t").size());
        assertEquals(0, sql(db, "SELECT * FROM t").size());
        batch(db, NONE, st("INSERT INTO t VALUES (1)"), st("INSERT INTO t VALUES (2)"));
        List<JSONObject> eerste = sql(db, FIRST, "SELECT x FROM t ORDER BY x");
        assertEquals(1, eerste.size());
        assertEquals(1, eerste.get(0).getInt("x"));
        assertNull(wacht(k -> service.uitvoeren(db, "INSERT INTO t VALUES (3)", waarden(), NONE, k)).opt("rows"));
        JSONArray results = batch(db, ALL, st("SELECT count(*) AS n FROM t"), st("SELECT x FROM t WHERE x > ?", 5)).getJSONArray("results");
        assertEquals(2, results.length());
        assertEquals(3, results.getJSONArray(0).getJSONObject(0).getInt("n"));
        assertEquals(0, results.getJSONArray(1).length());
        //alleen witruimte
        assertEquals(0, sql(db, "   ").size());
    }

    @Test
    public void batchRollbackEnFoutindex() throws Exception {
        String db = id(open("rollback.sqlite"));
        sql(db, NONE, "CREATE TABLE t (x UNIQUE)");
        SQLiteFout f = fout(() -> batch(db, NONE, st("INSERT INTO t VALUES (1)"), st("INSERT INTO t VALUES (1)")));
        assertNotNull(f);
        assertEquals("SQL_ERROR", f.code);
        assertEquals(Integer.valueOf(1), f.statementIndex);
        assertEquals(19, f.sqliteCode & 0xff); //SQLITE_CONSTRAINT
        assertEquals(1, f.data().getInt("statementIndex"));
        assertEquals(0, aantal(db, "t"));
        //de verbinding blijft bruikbaar
        batch(db, NONE, st("INSERT INTO t VALUES (2)"));
        assertEquals(1, aantal(db, "t"));
    }

    @Test
    public void transactiecontroleEnMeerdereStatements() throws Exception {
        String db = id(open("controle.sqlite"));
        sql(db, NONE, "CREATE TABLE t (x)");
        for (String controle : new String[] { "COMMIT", "﻿COMMIT", " /* x */ -- y\n commit", "END", "ROLLBACK", "SAVEPOINT a", "RELEASE a", "BEGIN" }) {
            SQLiteFout f = fout(() -> batch(db, NONE, st("INSERT INTO t VALUES (1)"), st(controle)));
            assertNotNull(controle, f);
            assertEquals(controle, "TRANSACTION_CONTROL", f.code);
            assertEquals(controle, Integer.valueOf(1), f.statementIndex);
        }
        assertEquals(0, aantal(db, "t"));
        assertFalse(SQLiteVerbinding.isTransactiecontrole("CREATE TRIGGER x AFTER INSERT ON t BEGIN SELECT 1; END"));

        //AndroidX leest alleen het eerste statement uit een string, net als de vorige Cordova-engine
        sql(db, NONE, "INSERT INTO t VALUES (1); INSERT INTO t VALUES (2)");
        assertEquals(1, aantal(db, "t"));
        //VACUUM loopt buiten een transactie
        sql(db, NONE, "VACUUM");
        assertEquals(1, aantal(db, "t"));
    }

    @Test
    public void notitieTriggersMetPuntkommas() throws Exception {
        String db = id(open("notes.sqlite"));
        batch(
            db,
            NONE,
            st("CREATE TABLE notes (idate integer NOT NULL, vid integer, title varchar(500), 'text' varchar(8000))"),
            st("CREATE VIRTUAL TABLE notesFTS USING fts5(text,title,content=notes)"),
            st(
                "CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN\n" +
                "    INSERT INTO notesFTS(rowid, \"text\", title) VALUES (new.rowid, new.text, new.title);\n" +
                "END"
            ),
            st(
                "CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN\n" +
                "    INSERT INTO notesFTS(notesFTS, rowid, \"text\", title) VALUES('delete', old.rowid, old.text, old.title);\n" +
                "    INSERT INTO notesFTS(rowid, \"text\", title) VALUES (new.rowid, new.text, new.title);\n" +
                "END"
            ),
            st("PRAGMA user_version = 2")
        );
        sql(db, NONE, "INSERT INTO notes (idate, vid, title, text) VALUES (?, ?, ?, ?)", 1700000000000L, 49001001, "Titel", "genade en vrede");
        assertEquals(1, sql(db, "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'genade'").size());
        //fout in statement 2: ook de FTS-wijziging van statement 1 gaat terug
        assertNotNull(fout(() -> batch(db, NONE, st("UPDATE notes SET text = 'liefde'"), st("INSERT INTO bestaatniet VALUES (1)"))));
        assertEquals(1, sql(db, "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'genade'").size());
        assertEquals(0, sql(db, "SELECT rowid FROM notesFTS WHERE notesFTS MATCH 'liefde'").size());
        assertEquals(2, sql(db, FIRST, "PRAGMA user_version").get(0).getInt("user_version"));
    }

    @Test
    public void seedEenmaligEnWeesJournal() throws Exception {
        //een achtergebleven journal van een verwijderde database mag niet op de seed worden toegepast
        File journal = new File(map, "user.sqlite-journal");
        schrijf(journal, "oud");
        JSObject eerste = open("user.sqlite", "persistent", "proefuser.sqlite", null);
        assertTrue(eerste.optBoolean("copied"));
        assertFalse(journal.exists());
        String db = id(eerste);
        sql(db, NONE, "INSERT INTO t VALUES ('gebruiker')");
        sluit(db);

        JSObject tweede = open("user.sqlite", "persistent", "proefuser.sqlite", null);
        assertFalse(tweede.optBoolean("copied"));
        assertEquals(2, tweede.getInt("userVersion"));
        assertEquals(2, aantal(id(tweede), "t"));
        assertFalse(new File(map, ".user.sqlite.seed").exists());
    }

    @Test
    public void ontbrekendeSeedMaaktGeenLegeDatabase() throws Exception {
        assertEquals("ASSET_NOT_FOUND", code(() -> open("user.sqlite", "persistent", "bestaatniet.sqlite", null)));
        assertFalse(new File(map, "user.sqlite").exists());
        assertEquals("ASSET_NOT_FOUND", code(() -> open("bestaatniet.sqlite", "bundled", null, null)));
        assertFalse(new File(map, "bestaatniet.sqlite").exists());
        assertFalse(new File(map, "bestaatniet.sqlite.nieuw").exists());
        assertEquals("INVALID_ARGUMENT", code(() -> open("../user.sqlite")));
        assertEquals("INVALID_ARGUMENT", code(() -> open(".verborgen.sqlite")));
        assertEquals("INVALID_ARGUMENT", code(() -> open("x.sqlite", "anders", null, null)));
    }

    @Test
    public void bundelAlleenLezenEnHergebruik() throws Exception {
        String sha = sha256Asset("public/proefbasis.sqlite");
        JSObject info = open("proefbasis.sqlite", "bundled", null, sha);
        assertTrue(info.optBoolean("readOnly"));
        assertEquals("copy", info.getString("source"));
        assertTrue(info.optBoolean("copied"));
        assertEquals(14, info.getInt("userVersion"));
        String db = id(info);
        SQLiteFout schrijven = fout(() -> sql(db, NONE, "INSERT INTO t VALUES (2)"));
        assertNotNull(schrijven);
        assertEquals(8, schrijven.sqliteCode & 0xff); //SQLITE_READONLY
        assertNotNull(fout(() -> batch(db, NONE, st("UPDATE t SET x = 3"))));
        assertNotNull(fout(() -> sql(db, NONE, "PRAGMA user_version = 15")));
        assertEquals(1, sql(db, "SELECT x FROM t").get(0).getInt("x"));
        assertEquals(2, sql(db, "SELECT rowid FROM kop WHERE kop MATCH 'god' ORDER BY rank").size());

        //tweede opening (WebView herladen): dezelfde verbinding
        JSObject opnieuw = open("proefbasis.sqlite", "bundled", null, sha);
        assertTrue(opnieuw.optBoolean("reused"));
        assertEquals(db, id(opnieuw));
        //zelfde bestand in een andere modus zou de open database overschrijven
        assertEquals("DB_IN_USE", code(() -> open("proefbasis.sqlite")));
        assertEquals("DB_IN_USE", code(() -> verwijder("proefbasis.sqlite")));
    }

    @Test
    public void basiskopieAlleenBijAndereInhoud() throws Exception {
        String sha = sha256Asset("public/proefbasis.sqlite");
        File doel = new File(map, "proefbasis.sqlite");
        File merk = new File(map, "proefbasis.sqlite.bron");
        sluit(id(open("proefbasis.sqlite", "bundled", null, sha)));
        String merkInhoud = lees(merk);
        assertEquals("sha256:" + sha + "\n" + doel.length(), merkInhoud);

        //zelfde inhoud: niet opnieuw kopiëren
        long tijd = doel.lastModified();
        JSObject tweede = open("proefbasis.sqlite", "bundled", null, sha);
        assertFalse(tweede.optBoolean("copied"));
        sluit(id(tweede));
        assertEquals(tijd, doel.lastModified());

        //bytes in de app wijken af van de build: fout, de oude kopie en de marker blijven
        String andere = new String(new char[64]).replace('\0', '0');
        assertEquals("COPY_FAILED", code(() -> open("proefbasis.sqlite", "bundled", null, andere)));
        assertTrue(doel.isFile());
        assertEquals(merkInhoud, lees(merk));
        assertFalse(new File(map, "proefbasis.sqlite.nieuw").exists());

        //zonder contentId is de appversie de bron: één keer opnieuw kopiëren, daarna niet meer
        JSObject derde = open("proefbasis.sqlite", "bundled", null, null);
        assertTrue(derde.optBoolean("copied"));
        sluit(id(derde));
        JSObject vierde = open("proefbasis.sqlite", "bundled", null, null);
        assertFalse(vierde.optBoolean("copied"));
    }

    @Test
    public void oudeKopieMetJournalWordtVervangen() throws Exception {
        //zoals de vorige Cordova-plugin: een ooit schrijfbare kopie met andere inhoud en een journal ernaast
        schrijf(new File(map, "proefbasis.sqlite"), "geen database");
        File journal = new File(map, "proefbasis.sqlite-journal");
        schrijf(journal, "oud journal");
        JSObject info = open("proefbasis.sqlite", "bundled", null, sha256Asset("public/proefbasis.sqlite"));
        assertTrue(info.optBoolean("copied"));
        assertFalse(journal.exists());
        assertEquals(14, info.getInt("userVersion"));
        assertEquals(1, aantal(id(info), "t"));
    }

    @Test
    public void sluitenEnVerwijderen() throws Exception {
        String db = id(open("weg.sqlite"));
        sql(db, NONE, "CREATE TABLE t (x)");
        assertEquals("DB_IN_USE", code(() -> verwijder("weg.sqlite")));

        //werk dat vóór het sluiten is aangeboden, loopt nog af
        CompletableFuture<SQLiteFout> insert = new CompletableFuture<>();
        service.batch(db, Arrays.asList(st("INSERT INTO t VALUES (1)")), NONE, (r, f) -> insert.complete(f));
        sluit(db);
        assertNull(insert.get(10, TimeUnit.SECONDS));
        assertEquals("DB_CLOSED", code(() -> sql(db, "SELECT 1")));
        assertEquals("DB_CLOSED", code(() -> sluit(db)));
        String weer = id(open("weg.sqlite"));
        assertEquals(1, aantal(weer, "t"));
        sluit(weer);

        //twee objecten voor dezelfde verbinding (zoals na een herlaad): na het sluiten weigert ook de andere direct
        String tweede = id(open("weg2.sqlite"));
        assertEquals(tweede, id(open("weg2.sqlite")));
        CompletableFuture<SQLiteFout> sluiten = new CompletableFuture<>();
        service.sluiten(tweede, (r, f) -> sluiten.complete(f));
        assertEquals("DB_CLOSED", code(() -> sql(tweede, "SELECT 1")));
        assertNull(sluiten.get(10, TimeUnit.SECONDS));

        verwijder("weg.sqlite");
        assertFalse(new File(map, "weg.sqlite").exists());
        verwijder("weg.sqlite"); //ontbrekend bestand is geen fout
    }
}
