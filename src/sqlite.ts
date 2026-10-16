import type {
  SQLiteOpenOptions,
  SQLiteOpenResult,
  SQLiteResultMode,
  SQLiteRow,
  SQLiteStatement,
  SQLiteValue,
} from './definitions';
import { NCVplugin } from './plugin';

/**
 * Promise-interface van een geopende database, gelijk aan het contract van de gedeelde routes
 * (`sql`, `sqlRow`, `sqlAll`, `batch`, `batchAll`, `close`).
 */
export interface SQLiteDatabase {
  readonly info: SQLiteOpenResult;
  /** Eén statement zonder resultaat, buiten een transactie (ook `VACUUM`). */
  sql(sql: string, values?: SQLiteValue[]): Promise<void>;
  /** Eerste rij of `undefined`. */
  sqlRow(sql: string, values?: SQLiteValue[]): Promise<SQLiteRow | undefined>;
  sqlAll(sql: string, values?: SQLiteValue[]): Promise<SQLiteRow[]>;
  /** Eén schrijftransactie: alles of niets. */
  batch(statements: SQLiteStatement[]): Promise<void>;
  /** Rijen per statement, in één leestransactie. */
  batchAll(statements: SQLiteStatement[]): Promise<SQLiteRow[][]>;
  close(): Promise<void>;
}

type Transport = { sql: string; values: SQLiteValue[] };

function fout(code: string, message: string): Error {
  return Object.assign(new Error(message), { code });
}

//waarden direct bij de aanroep kopiëren: de aanroeper mag zijn arrays daarna leegmaken of hergebruiken
function kopieerWaarde(waarde: unknown): SQLiteValue {
  if (waarde === null || waarde === undefined) return null;
  switch (typeof waarde) {
    case 'string':
    case 'boolean':
      return waarde;
    case 'number':
      if (!Number.isFinite(waarde)) throw fout('INVALID_VALUE', `Ongeldige SQLite-waarde: ${waarde}`);
      return waarde;
    case 'bigint':
      return { $int: String(waarde) };
    case 'object': {
      const obj = waarde as Record<string, unknown>;
      if (typeof obj.$blob == 'string') return { $blob: obj.$blob };
      if (typeof obj.$int == 'string') return { $int: obj.$int };
    }
  }
  throw fout('INVALID_VALUE', 'Ongeldige SQLite-waarde van type ' + typeof waarde);
}

function kopieerWaarden(waarden?: SQLiteValue[]): SQLiteValue[] {
  if (waarden == null) return [];
  if (!Array.isArray(waarden)) throw fout('INVALID_VALUE', 'Waarden moeten een array zijn');
  return Array.from(waarden, kopieerWaarde);
}

function kopieerStatement(statement: SQLiteStatement): Transport {
  if (typeof statement == 'string') return { sql: statement, values: [] };
  if (Array.isArray(statement) && typeof statement[0] == 'string')
    return { sql: statement[0], values: kopieerWaarden(statement[1]) };
  throw fout('INVALID_STATEMENT', 'Een statement is een SQL-string of [sql, waarden]');
}

//methoden als pijlfuncties: ze blijven werken als een aanroeper ze losmaakt (const { sqlAll } = db), net als de vorige helper
class Database implements SQLiteDatabase {
  private gesloten = false;

  constructor(readonly info: SQLiteOpenResult) {}

  private id(): string {
    if (this.gesloten) throw fout('DB_CLOSED', 'Database is gesloten');
    return this.info.databaseId;
  }

  private uitvoeren = async (sql: string, waarden: SQLiteValue[] | undefined, result: SQLiteResultMode): Promise<SQLiteRow[]> => {
    const values = kopieerWaarden(waarden);
    const { rows } = await NCVplugin.sqliteExecute({ databaseId: this.id(), sql, values, result });
    return rows ?? [];
  };

  sql = async (sql: string, values?: SQLiteValue[]): Promise<void> => {
    await this.uitvoeren(sql, values, 'none');
  };

  sqlRow = async (sql: string, values?: SQLiteValue[]): Promise<SQLiteRow | undefined> => {
    const [rij] = await this.uitvoeren(sql, values, 'first');
    return rij ?? undefined;
  };

  sqlAll = (sql: string, values?: SQLiteValue[]): Promise<SQLiteRow[]> => this.uitvoeren(sql, values, 'all');

  batch = async (statements: SQLiteStatement[]): Promise<void> => {
    const kopie = Array.from(statements, kopieerStatement);
    if (!kopie.length) return;
    await NCVplugin.sqliteBatch({ databaseId: this.id(), statements: kopie, result: 'none' });
  };

  batchAll = async (statements: SQLiteStatement[]): Promise<SQLiteRow[][]> => {
    const kopie = Array.from(statements, kopieerStatement);
    if (!kopie.length) return [];
    const { results } = await NCVplugin.sqliteBatch({ databaseId: this.id(), statements: kopie, result: 'all' });
    return results ?? [];
  };

  close = async (): Promise<void> => {
    const databaseId = this.id();
    this.gesloten = true;
    await NCVplugin.sqliteClose({ databaseId });
  };
}

/** Opent een database via de native plugin (iOS/Android); op het web volgt een `UNAVAILABLE`-fout. */
export async function openSQLiteDatabase(options: SQLiteOpenOptions): Promise<SQLiteDatabase> {
  const { name, mode, seedAsset, contentId } = options;
  return new Database(await NCVplugin.sqliteOpen({ name, mode, seedAsset, contentId }));
}

/** Verwijdert een gesloten `persistent`-database (met journal/WAL-bestanden). */
export async function deleteSQLiteDatabase(name: string): Promise<void> {
  await NCVplugin.sqliteDelete({ name });
}
