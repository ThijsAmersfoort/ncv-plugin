import { WebPlugin } from '@capacitor/core';

import type { NCVpluginPlugin } from './definitions';

export class NCVpluginWeb extends WebPlugin implements NCVpluginPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
