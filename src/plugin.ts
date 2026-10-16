import { registerPlugin } from '@capacitor/core';

import type { NCVpluginPlugin } from './definitions';

export const NCVplugin = registerPlugin<NCVpluginPlugin>('NCVplugin', {
  web: () => import('./web').then((m) => new m.NCVpluginWeb()),
});
