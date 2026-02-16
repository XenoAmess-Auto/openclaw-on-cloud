import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.ooc.app',
  appName: 'OOC',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
    cleartext: true  // 允许明文通信（HTTP），用于连接后端
  },
  android: {
    buildOptions: {
      keystorePath: undefined,
      keystoreAlias: undefined,
      keystorePassword: undefined,
      keystoreAliasPassword: undefined,
      releaseType: 'APK'
    },
    // 允许 HTTP 流量
    allowMixedContent: true
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 2000,
      launchAutoHide: true,
      backgroundColor: '#1a1a2e',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP'
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#1a1a2e'
    }
  }
};

export default config;
