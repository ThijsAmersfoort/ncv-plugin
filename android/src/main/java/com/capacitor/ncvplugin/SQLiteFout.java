package com.capacitor.ncvplugin;

import android.database.SQLException;
import com.getcapacitor.JSObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fout met een vaste code voor JavaScript (error.code) en waar mogelijk de (uitgebreide) SQLite-code. */
class SQLiteFout extends Exception {

    //AndroidX geeft de SQLite-code alleen in de melding: "Error code: 19, message: ..."
    private static final Pattern FOUTCODE = Pattern.compile("^Error code: (-?\\d+)(?:, message: (.*))?$", Pattern.DOTALL);

    final String code;
    Integer sqliteCode;
    Integer statementIndex;
    final String database;

    SQLiteFout(String code, String message) {
        this(code, message, null);
    }

    SQLiteFout(String code, String message, String database) {
        super(message);
        this.code = code;
        this.database = database;
    }

    static SQLiteFout van(SQLException e, String database) {
        String melding = e.getMessage() == null ? "" : e.getMessage();
        Matcher m = FOUTCODE.matcher(melding);
        if (!m.matches()) {
            return new SQLiteFout("SQL_ERROR", melding, database);
        }
        SQLiteFout fout = new SQLiteFout("SQL_ERROR", m.group(2) != null ? m.group(2) : melding, database);
        fout.sqliteCode = Integer.parseInt(m.group(1));
        return fout;
    }

    JSObject data() {
        JSObject data = new JSObject();
        if (sqliteCode != null) data.put("sqliteCode", (int) sqliteCode);
        if (statementIndex != null) data.put("statementIndex", (int) statementIndex);
        if (database != null) data.put("database", database);
        return data;
    }
}
