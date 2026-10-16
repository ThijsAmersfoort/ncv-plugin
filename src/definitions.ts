export interface NCVpluginPlugin {
  info(): Promise<{ platform: string, version: string }>;
  ClipboardWrite(options: WriteOptions): Promise<void>;
  ClipboardRead(): Promise<ReadResult>;
  openUrl(options: OpenURLOptions): Promise<OpenURLResult>;

  /**
   * Opent een SQLite-database (alleen iOS/Android). Gebruik in de app `openSQLiteDatabase`.
   * Is hetzelfde bestand al geopend (bijvoorbeeld na een herlaad van de WebView), dan komt de bestaande verbinding terug.
   */
  sqliteOpen(options: SQLiteOpenOptions): Promise<SQLiteOpenResult>;
  /** Eén statement, buiten een transactie (ook `VACUUM` en PRAGMA's). */
  sqliteExecute(options: SQLiteExecuteOptions): Promise<SQLiteExecuteResult>;
  /** Alle statements in één native transactie: alles of niets. */
  sqliteBatch(options: SQLiteBatchOptions): Promise<SQLiteBatchResult>;
  /** Sluit na de al aangeboden aanvragen; daarna geeft de database `DB_CLOSED`. */
  sqliteClose(options: { databaseId: string }): Promise<void>;
  /** Verwijdert een gesloten `persistent`-database met journal/WAL-bestanden. */
  sqliteDelete(options: { name: string }): Promise<void>;

  /**
   * Beveiligde opslag van strings (alleen iOS/Android): Keychain op iOS/Mac, Keystore op Android.
   * `value` ontbreekt als de sleutel niet bestaat. Bestaat hij wel maar is hij niet leesbaar, dan volgt een fout
   * met een `SecureStorageErrorCode` als `code`.
   */
  secureGet(options: { key: string }): Promise<{ value?: string }>;
  secureSet(options: { key: string; value: string }): Promise<void>;
  /** Verwijdert ook een nog niet overgezette waarde van de vroegere Cordova-plugin. */
  secureRemove(options: { key: string }): Promise<void>;
  secureKeys(): Promise<{ keys: string[] }>;
  /** Wist alleen de opslag van de app (service `my_app`), niet de rest van de Keychain/Keystore. */
  secureClear(): Promise<void>;
  /**
   * Android: zet waarden van cordova-plugin-secure-storage-echo over. Vraagt zo nodig eenmalig de
   * schermvergrendeling. iOS gebruikt dezelfde Keychain-items als die plugin en zet niets over.
   */
  secureMigrate(options?: SecureMigrateOptions): Promise<SecureMigrateResult>;
}

/** Waarde voor een parameter of kolom. BLOB en int64 buiten het veilige getalbereik krijgen een getagde vorm. */
export type SQLiteValue = null | string | number | boolean | SQLiteBlob | SQLiteBigInt;

export interface SQLiteBlob {
  /** Base64 */
  $blob: string;
}

export interface SQLiteBigInt {
  /** Decimale int64 buiten Number.MAX_SAFE_INTEGER */
  $int: string;
}

export type SQLiteRow = Record<string, SQLiteValue>;

/** Een SQL-string of `[sql, waarden]`; altijd precies één statement (een volledige trigger is één statement). */
export type SQLiteStatement = string | [string, SQLiteValue[]?];

export interface SQLiteOpenOptions {
  /** Bestandsnaam zonder pad, bijvoorbeeld `NCVbasis.sqlite`. */
  name: string;
  /**
   * `bundled`: alleen-lezen appinhoud uit `public/` (Apple direct uit de bundel, Android een beheerde kopie).
   * `persistent`: schrijfbaar bestand op de vaste plek van de app (Apple `Library/LocalDatabase`, Android `getDatabasePath`).
   */
  mode: 'bundled' | 'persistent';
  /** `persistent`: bestand in `public/` dat alleen bij een ontbrekende database wordt gekopieerd. */
  seedAsset?: string;
  /** `bundled`, Android: SHA-256 (hex) van het bestand in de build; bepaalt of de beheerde kopie vernieuwd moet worden. */
  contentId?: string;
}

export interface SQLiteOpenResult {
  databaseId: string;
  readOnly: boolean;
  /** `bundle`: direct uit de appbundel, `copy`: beheerde kopie van de asset, `file`: gebruikersbestand. */
  source: 'bundle' | 'copy' | 'file';
  /** Bij deze opening gekopieerd (seed of nieuwe Android-basis). */
  copied: boolean;
  /** Bestaande verbinding hergebruikt. */
  reused: boolean;
  sqliteVersion: string;
  userVersion: number;
}

export type SQLiteResultMode = 'none' | 'first' | 'all';

export interface SQLiteExecuteOptions {
  databaseId: string;
  sql: string;
  values: SQLiteValue[];
  result: SQLiteResultMode;
}

export interface SQLiteExecuteResult {
  rows?: SQLiteRow[];
}

export interface SQLiteBatchOptions {
  databaseId: string;
  statements: { sql: string; values: SQLiteValue[] }[];
  /** `none`: schrijftransactie (`BEGIN IMMEDIATE`), `all`: rijen per statement (`BEGIN DEFERRED`). */
  result: 'none' | 'all';
}

export interface SQLiteBatchResult {
  results?: SQLiteRow[][];
}

export interface WriteOptions {
  /**
   * Text value to copy.
   *
   * @since 1.0.0
   */
  string?: string;

  /**
   * Html value to copy.
   *
   * @since 1.0.0
   */
  html?: string;

  /**
   * Image in [Data URL](https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/Data_URIs) format to copy.
   *
   * @since 1.0.0
   */
  image?: string;

  /**
   * URL string to copy.
   *
   * @since 1.0.0
   */
  url?: string;

  /**
   * User visible label to accompany the copied data (Android Only).
   *
   * @since 1.0.0
   */
  label?: string;
}

/**
 * Represents the data read from the clipboard.
 *
 * @since 1.0.0
 */
export interface ReadResult {
  /**
   * Data read from the clipboard.
   *
   * @since 1.0.0
   */
  value: string;

  /**
   * Type of data in the clipboard.
   *
   * @since 1.0.0
   */
  type: string;
}

/**
 * @deprecated Use `WriteOptions`.
 * @since 1.0.0
 */
export type ClipboardWrite = WriteOptions;

/**
 * @deprecated Use `ReadResult`.
 * @since 1.0.0
 */
export type ClipboardReadResult = ReadResult;

export interface OpenURLOptions {
  url: string;
}

export interface OpenURLResult {
  completed: boolean;
}
/**
 * Foutcodes van de beveiligde opslag. De waarde bestaat, maar is nu niet leesbaar; behandel dit niet als
 * "geen opgeslagen inlog". `LOCKED` alleen iOS, de overige behalve `OS_ERROR` en `INVALID_RECORD` alleen Android.
 */
export type SecureStorageErrorCode =
  | 'LOCKED'
  | 'AUTH_REQUIRED'
  | 'AUTH_CANCELLED'
  | 'KEY_MISSING'
  | 'KEY_INVALIDATED'
  | 'DECRYPT_FAILED'
  | 'INVALID_RECORD'
  | 'OS_ERROR'
  | 'INVALID_ARGUMENT';

export interface SecureMigrateOptions {
  /** Titel van de systeemvraag om de schermvergrendeling (Android). */
  title?: string;
  /** Uitleg bij de systeemvraag om de schermvergrendeling (Android). */
  description?: string;
}

export interface SecureMigrateResult {
  /** Sleutels die nu zijn overgezet. */
  migrated: string[];
  /** Sleutels die (nog) niet zijn overgezet, met de foutcode; ze blijven staan voor een volgende poging. */
  failed: { [key: string]: SecureStorageErrorCode };
}
