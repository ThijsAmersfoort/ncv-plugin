package com.capacitor.ncvplugin;

import android.database.SQLException;
import android.util.Base64;
import androidx.sqlite.SQLite;
import androidx.sqlite.SQLiteConnection;
import androidx.sqlite.SQLiteStatement;
import androidx.sqlite.driver.bundled.BundledSQLite;
import androidx.sqlite.driver.bundled.BundledSQLiteDriver;
import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Eén SQLite-verbinding (AndroidX bundled, met FTS5). Alleen gebruiken vanaf de executor van de bijbehorende database. */
final class SQLiteVerbinding {

    enum Resultaat {
        NONE,
        FIRST,
        ALL;

        static Resultaat van(String naam) {
            switch (naam == null ? "none" : naam) {
                case "none":
                    return NONE;
                case "first":
                    return FIRST;
                case "all":
                    return ALL;
                default:
                    return null;
            }
        }
    }

    static final class Statement {

        final String sql;
        final JSONArray waarden;

        Statement(String sql, JSONArray waarden) {
            this.sql = sql;
            this.waarden = waarden;
        }
    }

    //grootste geheel getal dat JavaScript exact kent; daarbuiten {"$int": "..."}
    private static final long MAX_VEILIG = 9007199254740991L;

    final String naam;
    private SQLiteConnection db;

    SQLiteVerbinding(BundledSQLiteDriver driver, String pad, int flags, String naam) throws SQLiteFout {
        this.naam = naam;
        try {
            db = driver.open(pad, flags | BundledSQLite.SQLITE_OPEN_EXRESCODE);
        } catch (SQLException e) {
            SQLiteFout oorzaak = SQLiteFout.van(e, naam);
            SQLiteFout fout = new SQLiteFout("OPEN_FAILED", naam + " openen mislukt: " + oorzaak.getMessage(), naam);
            fout.sqliteCode = oorzaak.sqliteCode;
            throw fout;
        }
    }

    void sluiten() {
        if (db != null) {
            try {
                db.close();
            } catch (RuntimeException ignored) {}
        }
        db = null;
    }

    /** Vaste instellingen, gelijk aan de compileeropties van de vorige Cordova-engine (SYNCHRONOUS=3, TEMP_STORE=2). */
    void instellen(boolean schrijfbaar) throws SQLiteFout {
        exec("PRAGMA temp_store = MEMORY");
        if (schrijfbaar) {
            exec("PRAGMA synchronous = EXTRA");
            exec("PRAGMA foreign_keys = OFF");
        }
        //leest het schema: een bestand dat geen database is valt hier al af
        uitvoeren("SELECT count(*) FROM sqlite_master", null, Resultaat.NONE);
    }

    /** Controleert een kandidaatbestand (het schema is leesbaar) en sluit de verbinding weer. */
    void sluitNaControle() throws SQLiteFout {
        try {
            instellen(false);
        } finally {
            sluiten();
        }
    }

    int userVersion() throws SQLiteFout {
        List<JSObject> rijen = uitvoeren("PRAGMA user_version", null, Resultaat.FIRST);
        return rijen.isEmpty() ? 0 : rijen.get(0).optInt("user_version", 0);
    }

    private SQLiteConnection handle() throws SQLiteFout {
        if (db == null) throw new SQLiteFout("DB_CLOSED", "Database " + naam + " is gesloten", naam);
        return db;
    }

    void exec(String sql) throws SQLiteFout {
        SQLiteConnection verbinding = handle();
        try {
            SQLite.execSQL(verbinding, sql);
        } catch (SQLException e) {
            throw SQLiteFout.van(e, naam);
        }
    }

    /**
     * Voert één statement uit. Rijen alleen bij FIRST/ALL; het statement loopt altijd tot het einde.
     * AndroidX leest alleen het eerste statement uit de string: meer statements geven MULTIPLE_STATEMENTS, zoals op iOS (X7). Voorheen
     * liep alleen het eerste en kwam er succes terug, zodat bv. een migratie "ALTER ...; UPDATE ..." half liep.
     */
    List<JSObject> uitvoeren(String sql, JSONArray waarden, Resultaat resultaat) throws SQLiteFout {
        SQLiteConnection verbinding = handle();
        List<JSObject> rijen = new ArrayList<>();
        if (sql.trim().isEmpty()) return rijen;
        if (!alleenRest(sql, eindeEersteStatement(sql))) {
            throw new SQLiteFout("MULTIPLE_STATEMENTS", "Meer dan één SQL-statement in één aanroep", naam);
        }
        SQLiteStatement statement;
        try {
            statement = verbinding.prepare(sql);
        } catch (SQLException e) {
            throw SQLiteFout.van(e, naam);
        }
        try {
            binden(statement, waarden);
            String[] namen = null;
            //sqlRow: een SELECT hoeft na de eerste rij niet verder; andere statements lopen tot het einde
            boolean stopNaEerste = resultaat == Resultaat.FIRST && isSelect(sql);
            while (statement.step()) {
                if (resultaat == Resultaat.ALL || (resultaat == Resultaat.FIRST && rijen.isEmpty())) {
                    if (namen == null) namen = statement.getColumnNames().toArray(new String[0]);
                    JSObject rij = new JSObject();
                    for (int kolom = 0; kolom < namen.length; kolom++) {
                        rij.put(namen[kolom], waarde(statement, kolom));
                    }
                    rijen.add(rij);
                    if (stopNaEerste) break;
                }
            }
            return rijen;
        } catch (SQLException e) {
            throw SQLiteFout.van(e, naam);
        } finally {
            statement.close();
        }
    }

    /** Alle statements in één transactie: BEGIN IMMEDIATE om te schrijven, BEGIN DEFERRED voor rijen per statement. */
    List<List<JSObject>> batch(List<Statement> statements, Resultaat resultaat) throws SQLiteFout {
        SQLiteConnection verbinding = handle();
        exec(resultaat == Resultaat.NONE ? "BEGIN IMMEDIATE" : "BEGIN DEFERRED");
        List<List<JSObject>> uitkomst = new ArrayList<>();
        try {
            for (int index = 0; index < statements.size(); index++) {
                Statement statement = statements.get(index);
                if (isTransactiecontrole(statement.sql)) {
                    SQLiteFout fout = new SQLiteFout("TRANSACTION_CONTROL", "Transactiecontrole hoort niet in een batch", naam);
                    fout.statementIndex = index;
                    throw fout;
                }
                try {
                    List<JSObject> rijen = uitvoeren(statement.sql, statement.waarden, resultaat);
                    if (resultaat != Resultaat.NONE) uitkomst.add(rijen);
                } catch (SQLiteFout fout) {
                    fout.statementIndex = index;
                    throw fout;
                }
                //vangnet: de transactie mag tussendoor niet beëindigd zijn
                if (!verbinding.inTransaction()) {
                    SQLiteFout fout = new SQLiteFout("TRANSACTION_CONTROL", "Transactiecontrole hoort niet in een batch", naam);
                    fout.statementIndex = index;
                    throw fout;
                }
            }
            exec("COMMIT");
            return uitkomst;
        } catch (SQLiteFout | RuntimeException fout) {
            //ook als COMMIT mislukt; de oorspronkelijke fout blijft de uitkomst
            terugdraaien(verbinding);
            throw fout;
        }
    }

    /**
     * Index direct na het eerste statement (na zijn puntkomma), of de lengte van de string. Houdt rekening met strings, '"', `, [..],
     * commentaar en de puntkomma's binnen CREATE [TEMP] TRIGGER ... BEGIN ... END (daar eindigt pas END; het statement), zoals
     * sqlite3_complete.
     */
    static int eindeEersteStatement(String sql) {
        int i = 0;
        int n = sql.length();
        List<String> eerste = new ArrayList<>(); //de eerste woorden, om CREATE TRIGGER te herkennen
        boolean alleenWoorden = true;
        boolean trigger = false;
        int caseDiepte = 0; //binnen een trigger: een END dat een CASE afsluit, sluit de trigger niet
        String vorige = null;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char sluit = c == '[' ? ']' : c;
                int e = i + 1;
                while (e < n) {
                    if (sql.charAt(e) == sluit) {
                        if (sluit != ']' && e + 1 < n && sql.charAt(e + 1) == sluit) {
                            e += 2;
                            continue;
                        }
                        break;
                    }
                    e++;
                }
                i = Math.min(n, e + 1);
                vorige = null;
                alleenWoorden = false;
            } else if (c == ';') {
                if (!trigger || "END".equals(vorige)) return i + 1;
                i++;
                vorige = null;
            } else if (Character.isLetter(c) || c == '_') {
                int e = i;
                while (e < n && (Character.isLetterOrDigit(sql.charAt(e)) || sql.charAt(e) == '_' || sql.charAt(e) == '$')) e++;
                String woord = sql.substring(i, e).toUpperCase(Locale.ROOT);
                if (alleenWoorden && eerste.size() < 4) {
                    eerste.add(woord);
                    List<String> w = !eerste.isEmpty() && eerste.get(0).equals("EXPLAIN") ? eerste.subList(1, eerste.size()) : eerste;
                    if (w.size() >= 2 && w.get(0).equals("CREATE") &&
                        (w.get(1).equals("TRIGGER") || (w.size() >= 3 && (w.get(1).equals("TEMP") || w.get(1).equals("TEMPORARY")) && w.get(2).equals("TRIGGER")))) {
                        trigger = true;
                    }
                }
                if (trigger && woord.equals("CASE")) {
                    caseDiepte++;
                } else if (trigger && woord.equals("END") && caseDiepte > 0) {
                    caseDiepte--;
                    woord = "END CASE";
                }
                vorige = woord;
                i = e;
            } else {
                alleenWoorden = false;
                vorige = null;
                i++;
            }
        }
        return n;
    }

    /** Of het statement met SELECT begint (na witruimte en commentaar): alleen lezen */
    static boolean isSelect(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else {
                break;
            }
        }
        return sql.regionMatches(true, i, "SELECT", 0, 6) && (i + 6 >= n || !Character.isLetterOrDigit(sql.charAt(i + 6)));
    }

    /** Of er vanaf index alleen witruimte, commentaar en puntkomma's staan */
    static boolean alleenRest(String sql, int index) {
        int i = index;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '\uFEFF') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? n : e + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
            } else {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> TRANSACTIE = new HashSet<>(Arrays.asList("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE"));

    /**
     * BEGIN, COMMIT, END, ROLLBACK, SAVEPOINT en RELEASE horen niet in een batch: ze zouden de transactie stil beëindigen.
     * Vóór uitvoering herkend aan het eerste woord (na witruimte en commentaar); een trigger begint met CREATE.
     */
    static boolean isTransactiecontrole(String sql) {
        int i = 0;
        int lengte = sql.length();
        while (i < lengte) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == ';' || c == '\uFEFF') { //SQLite slaat ook een BOM over
                i++;
            } else if (sql.startsWith("--", i)) {
                int einde = sql.indexOf('\n', i);
                i = einde < 0 ? lengte : einde + 1;
            } else if (sql.startsWith("/*", i)) {
                int einde = sql.indexOf("*/", i + 2);
                if (einde < 0) return false;
                i = einde + 2;
            } else {
                break;
            }
        }
        int begin = i;
        while (i < lengte && Character.isLetter(sql.charAt(i))) i++;
        return TRANSACTIE.contains(sql.substring(begin, i).toUpperCase(Locale.ROOT));
    }

    private void terugdraaien(SQLiteConnection verbinding) {
        try {
            if (verbinding.inTransaction()) SQLite.execSQL(verbinding, "ROLLBACK");
        } catch (RuntimeException ignored) {}
    }

    private void binden(SQLiteStatement statement, JSONArray waarden) throws SQLiteFout {
        if (waarden == null) return;
        for (int positie = 0; positie < waarden.length(); positie++) {
            Object waarde = waarden.opt(positie);
            int index = positie + 1;
            if (waarde == null || waarde == JSONObject.NULL) {
                statement.bindNull(index);
            } else if (waarde instanceof Boolean) {
                statement.bindLong(index, (Boolean) waarde ? 1 : 0);
            } else if (waarde instanceof Integer || waarde instanceof Long) {
                statement.bindLong(index, ((Number) waarde).longValue());
            } else if (waarde instanceof Number) {
                double getal = ((Number) waarde).doubleValue();
                if (!Double.isFinite(getal)) throw ongeldig(index);
                //JavaScript kent geen verschil tussen 5 en 5.0: gehele getallen worden INTEGER
                if (getal == Math.rint(getal) && Math.abs(getal) <= MAX_VEILIG) statement.bindLong(index, (long) getal);
                else statement.bindDouble(index, getal);
            } else if (waarde instanceof String) {
                statement.bindText(index, (String) waarde);
            } else if (waarde instanceof JSONObject) {
                bindGetagd(statement, index, (JSONObject) waarde);
            } else {
                throw ongeldig(index);
            }
        }
    }

    private void bindGetagd(SQLiteStatement statement, int index, JSONObject object) throws SQLiteFout {
        try {
            if (object.length() == 1 && object.opt("$blob") instanceof String) {
                statement.bindBlob(index, Base64.decode(object.getString("$blob"), Base64.DEFAULT));
                return;
            }
            if (object.length() == 1 && object.opt("$int") instanceof String) {
                statement.bindLong(index, Long.parseLong(object.getString("$int")));
                return;
            }
        } catch (IllegalArgumentException | org.json.JSONException e) {
            throw ongeldig(index);
        }
        throw ongeldig(index);
    }

    private SQLiteFout ongeldig(int index) {
        return new SQLiteFout("INVALID_VALUE", "Ongeldige waarde voor parameter " + index, naam);
    }

    private static Object waarde(SQLiteStatement statement, int kolom) {
        int soort = statement.getColumnType(kolom);
        if (soort == SQLite.SQLITE_DATA_INTEGER) {
            long getal = statement.getLong(kolom);
            return getal >= -MAX_VEILIG && getal <= MAX_VEILIG ? (Object) getal : new JSObject().put("$int", Long.toString(getal));
        }
        if (soort == SQLite.SQLITE_DATA_FLOAT) {
            double getal = statement.getDouble(kolom);
            return Double.isFinite(getal) ? (Object) getal : JSONObject.NULL; //JSON kent geen Infinity
        }
        if (soort == SQLite.SQLITE_DATA_TEXT) return statement.getText(kolom);
        if (soort == SQLite.SQLITE_DATA_BLOB) return new JSObject().put("$blob", Base64.encodeToString(statement.getBlob(kolom), Base64.NO_WRAP));
        return JSONObject.NULL;
    }
}
