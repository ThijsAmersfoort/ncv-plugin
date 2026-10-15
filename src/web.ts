import { WebPlugin } from '@capacitor/core';

import type { NCVpluginPlugin, 
              ReadResult, 
              WriteOptions,
              OpenURLOptions,
              OpenURLResult,
              SecureMigrateResult } from './definitions';

declare global {
  interface Clipboard {
    read(): Promise<any>;
    write(data: any[]): Promise<any>;
  }
}

declare let ClipboardItem: any;

export class NCVpluginWeb extends WebPlugin implements NCVpluginPlugin {
  async info(): Promise<{ platform: string, version: string }> {
    return {platform:'web',version:''};
  }
  async ClipboardWrite(options: WriteOptions): Promise<void> {
    if (typeof navigator === 'undefined' || !navigator.clipboard) {
      throw this.unavailable('Clipboard API not available in this browser');
    }

    if (options.string !== undefined) {
      await this.writeText(options.string);
    } else if (options.url) {
      await this.writeText(options.url);
    } else if (options.html) {
      await this.writeHtml(options.html);
    } else if (options.image) {
      if (typeof ClipboardItem !== 'undefined') {
        try {
          const blob = await (await fetch(options.image)).blob();
          const clipboardItemInput = new ClipboardItem({ [blob.type]: blob });
          await navigator.clipboard.write([clipboardItemInput]);
        } catch (err) {
          throw new Error('Failed to write image');
        }
      } else {
        throw this.unavailable('Writing images to the clipboard is not supported in this browser');
      }
    } else {
      throw new Error('Nothing to write');
    }
  }

  async ClipboardRead(): Promise<ReadResult> {
    if (typeof navigator === 'undefined' || !navigator.clipboard) {
      throw this.unavailable('Clipboard API not available in this browser');
    }

    if (typeof ClipboardItem !== 'undefined') {
      try {
        const clipboardItems = await navigator.clipboard.read();
        
        // Check for HTML first (more specific)
        if (clipboardItems[0].types.includes('text/html')) {
          const clipboardBlob = await clipboardItems[0].getType('text/html');
          const data = await this._getBlobData(clipboardBlob, 'text/html');
          return { value: data, type: 'text/html' };
        }
        
        // Fall back to the first available type
        const type = clipboardItems[0].types[0];
        const clipboardBlob = await clipboardItems[0].getType(type);
        const data = await this._getBlobData(clipboardBlob, type);
        return { value: data, type };
      } catch (err) {
        return this.readText();
      }
    } else {
      return this.readText();
    }
  }

  private async readText() {
    if (typeof navigator === 'undefined' || !navigator.clipboard || !navigator.clipboard.readText) {
      throw this.unavailable('Reading from clipboard not supported in this browser');
    }

    const text = await navigator.clipboard.readText();
    return { value: text, type: 'text/plain' };
  }

  private async writeText(text: string) {
    if (typeof navigator === 'undefined' || !navigator.clipboard || !navigator.clipboard.writeText) {
      throw this.unavailable('Writting to clipboard not supported in this browser');
    }

    await navigator.clipboard.writeText(text);
  }

  private async writeHtml(html: string) {
    if (typeof navigator === 'undefined' || !navigator.clipboard) {
      throw this.unavailable('Clipboard API not available in this browser');
    }

    if (typeof ClipboardItem !== 'undefined') {
      try {
        const htmlBlob = new Blob([html], { type: 'text/html' });
        const textBlob = new Blob([this._stripHtmlTags(html)], { type: 'text/plain' });
        
        const clipboardItemInput = new ClipboardItem({
          'text/html': htmlBlob,
          'text/plain': textBlob
        });
        
        await navigator.clipboard.write([clipboardItemInput]);
      } catch (err) {
        throw new Error('Failed to write HTML to clipboard');
      }
    } else {
      throw this.unavailable('Writing HTML to the clipboard is not supported in this browser');
    }
  }

  private _stripHtmlTags(html: string): string {
    const tmp = document.createElement('div');
    tmp.innerHTML = html;
    return tmp.textContent || tmp.innerText || '';
  }

  private _getBlobData(clipboardBlob: Blob, type: string): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      if (type.includes('image')) {
        reader.readAsDataURL(clipboardBlob);
      } else {
        reader.readAsText(clipboardBlob);
      }
      reader.onloadend = () => {
        const r = reader.result as string;
        resolve(r);
      };
      reader.onerror = (e) => {
        reject(e);
      };
    });
  }

  async openUrl(options: OpenURLOptions): Promise<OpenURLResult> {
    window.open(options.url, '_blank');
    return { completed: true };
  }

  //beveiligde opslag ook alleen native; de online PWA logt in via de server
  async secureGet(): Promise<{ value?: string }> {
    throw this.unavailable('Beveiligde opslag is alleen beschikbaar in de native app');
  }

  async secureSet(): Promise<void> {
    throw this.unavailable('Beveiligde opslag is alleen beschikbaar in de native app');
  }

  async secureRemove(): Promise<void> {
    throw this.unavailable('Beveiligde opslag is alleen beschikbaar in de native app');
  }

  async secureKeys(): Promise<{ keys: string[] }> {
    throw this.unavailable('Beveiligde opslag is alleen beschikbaar in de native app');
  }

  async secureClear(): Promise<void> {
    throw this.unavailable('Beveiligde opslag is alleen beschikbaar in de native app');
  }

  async secureMigrate(): Promise<SecureMigrateResult> {
    throw this.unavailable('Beveiligde opslag is alleen beschikbaar in de native app');
  }
}
