export interface NCVpluginPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
