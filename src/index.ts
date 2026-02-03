import { registerPlugin } from '@capacitor/core';

import type { NCVpluginPlugin } from './definitions';

const NCVplugin = registerPlugin<NCVpluginPlugin>('NCVplugin', {
  web: () => import('./web').then((m) => new m.NCVpluginWeb()),
});

export * from './definitions';
export { NCVplugin };
