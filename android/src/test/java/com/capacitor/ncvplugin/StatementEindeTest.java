package com.capacitor.ncvplugin;

import static org.junit.Assert.*;

import org.junit.Test;

/** X7: het einde van het eerste statement, zodat Android meer statements in één string weigert zoals iOS */
public class StatementEindeTest {

    private static boolean eenStatement(String sql) {
        return SQLiteVerbinding.alleenRest(sql, SQLiteVerbinding.eindeEersteStatement(sql));
    }

    @Test
    public void meerStatementsWordenHerkend() {
        assertFalse(eenStatement("INSERT INTO t VALUES (1); INSERT INTO t VALUES (2)"));
        assertFalse(eenStatement("ALTER TABLE t ADD x; UPDATE t SET x = 1"));
        assertFalse(eenStatement("SELECT 1;/* c */ SELECT 2"));
    }

    @Test
    public void eenStatementMetPuntkommasEnCommentaar() {
        assertTrue(eenStatement("SELECT 1"));
        assertTrue(eenStatement("SELECT 1;"));
        assertTrue(eenStatement("INSERT INTO t VALUES (3); -- klaar"));
        assertTrue(eenStatement("SELECT 'a;b', \"c;d\", `e;f`, [g;h] FROM t;"));
        assertTrue(eenStatement("SELECT 'it''s; ok'"));
        assertTrue(eenStatement("-- a; b\nSELECT 1 /* ; */;"));
        assertTrue(eenStatement(";"));
    }

    @Test
    public void triggerMetPuntkommasIsEenStatement() {
        assertTrue(eenStatement("CREATE TRIGGER x AFTER INSERT ON t BEGIN INSERT INTO u VALUES (1); DELETE FROM v; END"));
        assertTrue(eenStatement("CREATE TEMP TRIGGER x AFTER INSERT ON t BEGIN SELECT 1; END;"));
        assertTrue(eenStatement("create trigger notes_ai after insert on notes begin insert into notesFTS(rowid,text) values (new.rowid,new.text); end;"));
        assertFalse(eenStatement("CREATE TRIGGER x AFTER INSERT ON t BEGIN SELECT 1; END; SELECT 2"));
        //een END dat een CASE afsluit, is niet het einde van de trigger
        assertTrue(eenStatement("CREATE TRIGGER tr AFTER INSERT ON t BEGIN SELECT CASE WHEN NEW.x>0 THEN 1 ELSE 0 END; INSERT INTO u VALUES(2); END;"));
        assertTrue(eenStatement("CREATE TRIGGER tr AFTER INSERT ON t BEGIN SELECT CASE WHEN 1 THEN CASE 2 WHEN 2 THEN 3 END END; END"));
        assertFalse(eenStatement("CREATE TRIGGER tr AFTER INSERT ON t BEGIN SELECT CASE 1 WHEN 1 THEN 2 END; END; SELECT 1"));
        //een tabel met de naam trigger is geen trigger
        assertFalse(eenStatement("CREATE TABLE \"trigger\" (x); SELECT 1"));
    }

    @Test
    public void select() {
        assertTrue(SQLiteVerbinding.isSelect("SELECT 1"));
        assertTrue(SQLiteVerbinding.isSelect(" -- x\n /* y */ select * from t"));
        assertFalse(SQLiteVerbinding.isSelect("SELECTIE"));
        assertFalse(SQLiteVerbinding.isSelect("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x"));
        assertFalse(SQLiteVerbinding.isSelect("INSERT INTO t SELECT 1"));
    }
}
