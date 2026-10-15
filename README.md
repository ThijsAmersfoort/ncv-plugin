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
info() => Promise<{ platform: string; version: string; }>
```

**Returns:** <code>Promise&lt;{ platform: string; version: string; }&gt;</code>

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

| Prop         | Type                | Description                                                                                                     | Since |
| ------------ | ------------------- | --------------------------------------------------------------------------------------------------------------- | ----- |
| **`string`** | <code>string</code> | Text value to copy.                                                                                             | 1.0.0 |
| **`html`**   | <code>string</code> | Html value to copy.                                                                                             | 1.0.0 |
| **`image`**  | <code>string</code> | Image in [Data URL](https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/Data_URIs) format to copy. | 1.0.0 |
| **`url`**    | <code>string</code> | URL string to copy.                                                                                             | 1.0.0 |
| **`label`**  | <code>string</code> | User visible label to accompany the copied data (Android Only).                                                 | 1.0.0 |


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


#### SecureStorageErrorCode

Foutcodes van de beveiligde opslag. De waarde bestaat, maar is nu niet leesbaar; behandel dit niet als
"geen opgeslagen inlog". `LOCKED` alleen iOS, de overige behalve `OS_ERROR` en `INVALID_RECORD` alleen Android.

<code>'LOCKED' | 'AUTH_REQUIRED' | 'AUTH_CANCELLED' | 'KEY_MISSING' | 'KEY_INVALIDATED' | 'DECRYPT_FAILED' | 'INVALID_RECORD' | 'OS_ERROR' | 'INVALID_ARGUMENT'</code>

</docgen-api>
