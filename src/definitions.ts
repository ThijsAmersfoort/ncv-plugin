export interface NCVpluginPlugin {
  info(): Promise<{ platform: string, version: string }>;
  ClipboardWrite(options: WriteOptions): Promise<void>;
  ClipboardRead(): Promise<ReadResult>;
  openUrl(options: OpenURLOptions): Promise<OpenURLResult>;
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