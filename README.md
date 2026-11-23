# ncv-plugin

A Capacitor plugin for the NCV Bijbelstudie app that implements clipboard functionality with a similar API as the native Capacitor Clipboard plugin, but adds support for HTML, and adds an extra function to open urls on the system instead of in the webview, and get platform information that supports the app running on Mac in iPad mode.

## Install

```bash
npm install ncv-plugin
npx cap sync
```

## API

<docgen-index>

* [`info()`](#info)
* [`ClipboardWrite(...)`](#clipboardwrite)
* [`ClipboardRead()`](#clipboardread)
* [`openUrl(...)`](#openurl)
* [`share(...)`](#share)
* [`sqliteOpen(...)`](#sqliteopen)
* [`sqliteExecute(...)`](#sqliteexecute)
* [`sqliteBatch(...)`](#sqlitebatch)
* [`sqliteClose(...)`](#sqliteclose)
* [`sqliteDelete(...)`](#sqlitedelete)
* [`secureGet(...)`](#secureget)
* [`secureSet(...)`](#secureset)
* [`secureRemove(...)`](#secureremove)
* [`secureKeys()`](#securekeys)
* [`secureClear()`](#secureclear)
* [`secureMigrate(...)`](#securemigrate)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### info()

```typescript
info() => Promise<{ platform: string; version: string; catalyst?: boolean; osVersie?: string; }>
```

catalyst: de Mac-build (Mac Catalyst); osVersie: alleen het versienummer, gelijk op beide platforms

**Returns:** <code>Promise&lt;{ platform: string; version: string; catalyst?: boolean; osVersie?: string; }&gt;</code>

--------------------


### ClipboardWrite(...)

```typescript
ClipboardWrite(options: WriteOptions) => Promise<void>
```

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#writeoptions">WriteOptions</a></code> |

--------------------


### ClipboardRead()

```typescript
ClipboardRead() => Promise<ReadResult>
```

**Returns:** <code>Promise&lt;<a href="#readresult">ReadResult</a>&gt;</code>

--------------------


### openUrl(...)

```typescript
openUrl(options: OpenURLOptions) => Promise<OpenURLResult>
```

| Param         | Type                                                      |
| ------------- | --------------------------------------------------------- |
| **`options`** | <code><a href="#openurloptions">OpenURLOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#openurlresult">OpenURLResult</a>&gt;</code>

--------------------


### share(...)

```typescript
share(options: ShareOptions) => Promise<void>
```

Alleen Android: het deelmenu van het systeem (de Android-WebView heeft geen `navigator.share`). Tekst, titel en url,
en/of bestanden (base64). Lost op zodra het menu geopend is; of er echt gedeeld werd, meldt Android niet.

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#shareoptions">ShareOptions</a></code> |

--------------------


### sqliteOpen(...)

```typescript
sqliteOpen(options: SQLiteOpenOptions) => Promise<SQLiteOpenResult>
```

Opent een SQLite-database (alleen iOS/Android). Gebruik in de app `openSQLiteDatabase`.
Is hetzelfde bestand al geopend (bijvoorbeeld na een herlaad van de WebView), dan komt de bestaande verbinding terug.

| Param         | Type                                                            |
| ------------- | --------------------------------------------------------------- |
| **`options`** | <code><a href="#sqliteopenoptions">SQLiteOpenOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#sqliteopenresult">SQLiteOpenResult</a>&gt;</code>

--------------------


### sqliteExecute(...)

```typescript
sqliteExecute(options: SQLiteExecuteOptions) => Promise<SQLiteExecuteResult>
```

Eén statement, buiten een transactie (ook `VACUUM` en PRAGMA's).

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#sqliteexecuteoptions">SQLiteExecuteOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#sqliteexecuteresult">SQLiteExecuteResult</a>&gt;</code>

--------------------


### sqliteBatch(...)

```typescript
sqliteBatch(options: SQLiteBatchOptions) => Promise<SQLiteBatchResult>
```

Alle statements in één native transactie: alles of niets.

| Param         | Type                                                              |
| ------------- | ----------------------------------------------------------------- |
| **`options`** | <code><a href="#sqlitebatchoptions">SQLiteBatchOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#sqlitebatchresult">SQLiteBatchResult</a>&gt;</code>

--------------------


### sqliteClose(...)

```typescript
sqliteClose(options: { databaseId: string; }) => Promise<void>
```

Sluit na de al aangeboden aanvragen; daarna geeft de database `DB_CLOSED`.

| Param         | Type                                 |
| ------------- | ------------------------------------ |
| **`options`** | <code>{ databaseId: string; }</code> |

--------------------


### sqliteDelete(...)

```typescript
sqliteDelete(options: { name: string; backup?: boolean; }) => Promise<void>
```

Verwijdert een gesloten `persistent`-database met journal/WAL-bestanden.

| Param         | Type                                             |
| ------------- | ------------------------------------------------ |
| **`options`** | <code>{ name: string; backup?: boolean; }</code> |

--------------------


### secureGet(...)

```typescript
secureGet(options: { key: string; }) => Promise<{ value?: string; }>
```

Beveiligde opslag van strings (alleen iOS/Android): Keychain op iOS/Mac, Keystore op Android.
`value` ontbreekt als de sleutel niet bestaat. Bestaat hij wel maar is hij niet leesbaar, dan volgt een fout
met een <a href="#securestorageerrorcode">`SecureStorageErrorCode`</a> als `code`.

| Param         | Type                          |
| ------------- | ----------------------------- |
| **`options`** | <code>{ key: string; }</code> |

**Returns:** <code>Promise&lt;{ value?: string; }&gt;</code>

--------------------


### secureSet(...)

```typescript
secureSet(options: { key: string; value: string; }) => Promise<void>
```

| Param         | Type                                         |
| ------------- | -------------------------------------------- |
| **`options`** | <code>{ key: string; value: string; }</code> |

--------------------


### secureRemove(...)

```typescript
secureRemove(options: { key: string; }) => Promise<void>
```

Verwijdert ook een nog niet overgezette waarde van de vroegere Cordova-plugin.

| Param         | Type                          |
| ------------- | ----------------------------- |
| **`options`** | <code>{ key: string; }</code> |

--------------------


### secureKeys()

```typescript
secureKeys() => Promise<{ keys: string[]; }>
```

**Returns:** <code>Promise&lt;{ keys: string[]; }&gt;</code>

--------------------


### secureClear()

```typescript
secureClear() => Promise<void>
```

Wist alleen de opslag van de app (service `my_app`), niet de rest van de Keychain/Keystore.

--------------------


### secureMigrate(...)

```typescript
secureMigrate(options?: SecureMigrateOptions | undefined) => Promise<SecureMigrateResult>
```

Android: zet waarden van cordova-plugin-secure-storage-echo over. Vraagt zo nodig eenmalig de
schermvergrendeling. iOS gebruikt dezelfde Keychain-items als die plugin en zet niets over.

| Param         | Type                                                                  |
| ------------- | --------------------------------------------------------------------- |
| **`options`** | <code><a href="#securemigrateoptions">SecureMigrateOptions</a></code> |

**Returns:** <code>Promise&lt;<a href="#securemigrateresult">SecureMigrateResult</a>&gt;</code>

--------------------


### Interfaces


#### WriteOptions

| Prop         | Type                | Description                                                                                                                                                                                           | Since |
| ------------ | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----- |
| **`string`** | <code>string</code> | Text value to copy.                                                                                                                                                                                   | 1.0.0 |
| **`html`**   | <code>string</code> | Html value to copy.                                                                                                                                                                                   | 1.0.0 |
| **`text`**   | <code>string</code> | Platte tekst bij `html`. Zonder deze waarde maakt de plugin hem zelf uit de HTML (iOS: met NSAttributedString op de main thread, wat bij een grote selectie hapert; de app heeft de platte tekst al). |       |
| **`image`**  | <code>string</code> | Image in [Data URL](https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/Data_URIs) format to copy.                                                                                       | 1.0.0 |
| **`url`**    | <code>string</code> | URL string to copy.                                                                                                                                                                                   | 1.0.0 |
| **`label`**  | <code>string</code> | User visible label to accompany the copied data (Android Only).                                                                                                                                       | 1.0.0 |


#### ReadResult

Represents the data read from the clipboard.

| Prop        | Type                | Description                    | Since |
| ----------- | ------------------- | ------------------------------ | ----- |
| **`value`** | <code>string</code> | Data read from the clipboard.  | 1.0.0 |
| **`type`**  | <code>string</code> | Type of data in the clipboard. | 1.0.0 |


#### OpenURLResult

| Prop            | Type                 |
| --------------- | -------------------- |
| **`completed`** | <code>boolean</code> |


#### OpenURLOptions

| Prop      | Type                |
| --------- | ------------------- |
| **`url`** | <code>string</code> |


#### ShareOptions

| Prop        | Type                     |
| ----------- | ------------------------ |
| **`title`** | <code>string</code>      |
| **`text`**  | <code>string</code>      |
| **`url`**   | <code>string</code>      |
| **`files`** | <code>ShareFile[]</code> |


#### ShareFile

| Prop       | Type                | Description                                          |
| ---------- | ------------------- | ---------------------------------------------------- |
| **`name`** | <code>string</code> | Bestandsnaam zonder pad, bijvoorbeeld `Analyse.png`. |
| **`type`** | <code>string</code> | MIME-type, bijvoorbeeld `image/png`.                 |
| **`data`** | <code>string</code> | Inhoud als base64 (zonder `data:`-voorvoegsel).      |


#### SQLiteOpenResult

| Prop                | Type                                      | Description                                                                                        |
| ------------------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------- |
| **`databaseId`**    | <code>string</code>                       |                                                                                                    |
| **`readOnly`**      | <code>boolean</code>                      |                                                                                                    |
| **`source`**        | <code>'bundle' \| 'copy' \| 'file'</code> | `bundle`: direct uit de appbundel, `copy`: beheerde kopie van de asset, `file`: gebruikersbestand. |
| **`copied`**        | <code>boolean</code>                      | Bij deze opening gekopieerd (seed of nieuwe Android-basis).                                        |
| **`reused`**        | <code>boolean</code>                      | Bestaande verbinding hergebruikt.                                                                  |
| **`sqliteVersion`** | <code>string</code>                       |                                                                                                    |
| **`userVersion`**   | <code>number</code>                       |                                                                                                    |
| **`journalMode`**   | <code>string</code>                       | `delete`, `wal`, … (PRAGMA journal_mode).                                                          |


#### SQLiteOpenOptions

| Prop            | Type                                   | Description                                                                                                                                                                                                                                                                                                                                              |
| --------------- | -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`name`**      | <code>string</code>                    | Bestandsnaam zonder pad, bijvoorbeeld `NCVbasis.sqlite`.                                                                                                                                                                                                                                                                                                 |
| **`mode`**      | <code>'bundled' \| 'persistent'</code> | `bundled`: alleen-lezen appinhoud uit `public/` (Apple direct uit de bundel, Android een beheerde kopie). `persistent`: schrijfbaar bestand op de vaste plek van de app (Apple `Library/LocalDatabase`, Android `getDatabasePath`).                                                                                                                      |
| **`seedAsset`** | <code>string</code>                    | `persistent`: bestand in `public/` dat alleen bij een ontbrekende database wordt gekopieerd.                                                                                                                                                                                                                                                             |
| **`contentId`** | <code>string</code>                    | `bundled`, Android: SHA-256 (hex) van het bestand in de build; bepaalt of de beheerde kopie vernieuwd moet worden.                                                                                                                                                                                                                                       |
| **`wal`**       | <code>boolean</code>                   | `persistent`: `journal_mode=WAL` met `synchronous=NORMAL` (anders `synchronous=EXTRA`), en een checkpoint als de app naar de achtergrond gaat. Lukt WAL niet, dan blijft het de vaste instelling (zie `journalMode`).                                                                                                                                    |
| **`backup`**    | <code>boolean</code>                   | `persistent`, Apple: in `Library/Application Support/NCV/Databases` in plaats van `Library/LocalDatabase`, zodat het bestand wel in de iCloud-back-up (en Time Machine) gaat. Android negeert dit: daar bepalen `backup_rules.xml` en `data_extraction_rules.xml` van de app wat in de back-up gaat. Gebruik dezelfde waarde bij `deleteSQLiteDatabase`. |


#### SQLiteExecuteResult

| Prop       | Type                     | Description                                                   |
| ---------- | ------------------------ | ------------------------------------------------------------- |
| **`rows`** | <code>SQLiteRow[]</code> | De vorige vorm (een object per rij); `sqlite.ts` leest beide. |


#### SQLiteBlob

| Prop        | Type                | Description |
| ----------- | ------------------- | ----------- |
| **`$blob`** | <code>string</code> | Base64      |


#### SQLiteBigInt

| Prop       | Type                | Description                                   |
| ---------- | ------------------- | --------------------------------------------- |
| **`$int`** | <code>string</code> | Decimale int64 buiten Number.MAX_SAFE_INTEGER |


#### SQLiteExecuteOptions

| Prop             | Type                                                          |
| ---------------- | ------------------------------------------------------------- |
| **`databaseId`** | <code>string</code>                                           |
| **`sql`**        | <code>string</code>                                           |
| **`values`**     | <code>SQLiteValue[]</code>                                    |
| **`result`**     | <code><a href="#sqliteresultmode">SQLiteResultMode</a></code> |


#### SQLiteBatchResult

| Prop          | Type                                                                 |
| ------------- | -------------------------------------------------------------------- |
| **`results`** | <code>(SQLiteRow[] \| <a href="#sqliterows">SQLiteRows</a>)[]</code> |


#### SQLiteRows

Rijen over de bridge: kolomnamen en per rij de waarden in die volgorde (`sqlite.ts` maakt er objecten van).

| Prop          | Type                         |
| ------------- | ---------------------------- |
| **`columns`** | <code>string[]</code>        |
| **`values`**  | <code>SQLiteValue[][]</code> |


#### SQLiteBatchOptions

| Prop             | Type                                                   | Description                                                                                   |
| ---------------- | ------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| **`databaseId`** | <code>string</code>                                    |                                                                                               |
| **`statements`** | <code>{ sql: string; values: SQLiteValue[]; }[]</code> |                                                                                               |
| **`result`**     | <code>'none' \| 'all'</code>                           | `none`: schrijftransactie (`BEGIN IMMEDIATE`), `all`: rijen per statement (`BEGIN DEFERRED`). |


#### SecureMigrateResult

| Prop           | Type                                                                                          | Description                                                                                         |
| -------------- | --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| **`migrated`** | <code>string[]</code>                                                                         | Sleutels die nu zijn overgezet.                                                                     |
| **`failed`**   | <code>{ [key: string]: <a href="#securestorageerrorcode">SecureStorageErrorCode</a>; }</code> | Sleutels die (nog) niet zijn overgezet, met de foutcode; ze blijven staan voor een volgende poging. |


#### SecureMigrateOptions

| Prop              | Type                | Description                                                     |
| ----------------- | ------------------- | --------------------------------------------------------------- |
| **`title`**       | <code>string</code> | Titel van de systeemvraag om de schermvergrendeling (Android).  |
| **`description`** | <code>string</code> | Uitleg bij de systeemvraag om de schermvergrendeling (Android). |


### Type Aliases


#### SQLiteRow

<code><a href="#record">Record</a>&lt;string, <a href="#sqlitevalue">SQLiteValue</a>&gt;</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### SQLiteValue

Waarde voor een parameter of kolom. BLOB en int64 buiten het veilige getalbereik krijgen een getagde vorm.

<code>null | string | number | boolean | <a href="#sqliteblob">SQLiteBlob</a> | <a href="#sqlitebigint">SQLiteBigInt</a></code>


#### SQLiteResultMode

<code>'none' | 'first' | 'all'</code>


#### SecureStorageErrorCode

Foutcodes van de beveiligde opslag. De waarde bestaat, maar is nu niet leesbaar; behandel dit niet als
"geen opgeslagen inlog". `LOCKED` alleen iOS, de overige behalve `OS_ERROR` en `INVALID_RECORD` alleen Android.

<code>'LOCKED' | 'AUTH_REQUIRED' | 'AUTH_CANCELLED' | 'KEY_MISSING' | 'KEY_INVALIDATED' | 'DECRYPT_FAILED' | 'INVALID_RECORD' | 'OS_ERROR' | 'INVALID_ARGUMENT'</code>

</docgen-api>
