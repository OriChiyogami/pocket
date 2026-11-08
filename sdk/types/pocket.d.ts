/**
 * 口袋 Pocket SDK 类型声明
 * 用法：在项目中 `/// <reference path="./pocket.d.ts" />`，
 * 或把此文件加入 tsconfig 的 include。
 */

declare namespace Pocket {

  interface SafeArea {
    top: number;
    right: number;
    bottom: number;
    left: number;
  }

  interface AppInfo {
    id: string;
    name: string;
    version: string;
    description: string;
    accent: string;
    permissions: string[];
    source: 'builtin' | 'file' | 'html';
    installedAt: number;
    launchCount: number;
    origin: string;
  }

  interface DeviceInfo {
    brand: string;
    model: string;
    os: string;
    sdk: number;
    width: number;
    height: number;
    density: number;
    dpWidth: number;
    dpHeight: number;
    locale: string;
    theme: 'light' | 'dark';
    safeArea: SafeArea;
    keyboard: number;
    viewportHeight: number;
    statusBar: number;
    platform: 'android';
    container: 'pocket';
  }

  interface NetworkInfo {
    connected: boolean;
    type: 'wifi' | 'cellular' | 'ethernet' | 'none' | 'unknown';
    metered: boolean;
    online: boolean;
  }

  interface HttpResponse {
    status: number;
    headers: Record<string, string>;
    body: any;
    contentType: string;
    url: string;
  }

  interface HttpOptions {
    url: string;
    method?: string;
    headers?: Record<string, string>;
    body?: string | object;
    timeout?: number;
    responseType?: 'text' | 'base64';
  }

  interface PickedFile {
    name: string;
    mime: string;
    size: number;
    dataUrl: string;
  }

  interface ImageOptions {
    prompt: string;
    size?: 'auto' | '1024x1024' | '1536x1024' | '1024x1536';
    quality?: 'auto' | 'low' | 'medium' | 'high';
    n?: number;
    format?: 'png' | 'jpeg' | 'webp';
    /** 参考图：Data URL 或纯 base64，最多 4 张；有值时走改图接口 */
    images?: string[];
  }

  interface TransferFile {
    id: string;
    name: string;
    size: number;
    mime: string;
    direction: 'in' | 'out';
    peer: string;
    time: number;
  }

  interface TransferPeer {
    id: string;
    name: string;
    platform: 'android' | 'windows' | 'unknown';
    host: string;
    port: number;
    lastSeen: number;
  }

  interface PocketError extends Error {
    code:
      | 'E_PERMISSION'
      | 'E_NETWORK_DENIED'
      | 'E_NETWORK'
      | 'E_INVALID_ARG'
      | 'E_NO_METHOD'
      | 'E_QUOTA'
      | 'E_CANCELLED'
      | 'E_AI'
      | 'E_IMAGE'
      | 'E_TRANSFER'
      | 'E_NOT_FOUND'
      | 'E_IO'
      | 'E_NO_BRIDGE'
      | 'E_INTERNAL';
    isPocketError: true;
  }

  type HapticType = 'light' | 'medium' | 'heavy' | 'success' | 'error';
  type StatusBarStyle = 'auto' | 'light' | 'dark' | 'hidden';
  type EventName =
    | 'resume'
    | 'pause'
    | 'back'
    | 'themechange'
    | 'networkchange'
    | 'keyboard'
    | 'destroy';
}

declare const pocket: {
  readonly __isPocketSdk: true;
  readonly version: string;
  readonly platform: 'android';

  ready(): Promise<typeof pocket>;

  app: {
    info(): Promise<Pocket.AppInfo>;
    close(): Promise<void>;
    restart(): Promise<void>;
  };

  ui: {
    toast(text: string): Promise<void>;
    haptic(type?: Pocket.HapticType): Promise<void>;
    fullscreen(enabled: boolean): Promise<void>;
    keepScreenOn(enabled: boolean): Promise<void>;
    statusBar(options: { style?: Pocket.StatusBarStyle; color?: string }): Promise<void>;
    alert(options: { title?: string; message?: string; button?: string }): Promise<void>;
    alert(title: string, message?: string, button?: string): Promise<void>;
    confirm(
      options: { title?: string; message?: string; okText?: string; cancelText?: string }
    ): Promise<boolean>;
    confirm(message: string, okText?: string, cancelText?: string): Promise<boolean>;
    prompt(options: {
      title?: string;
      message?: string;
      placeholder?: string;
      value?: string;
      multiline?: boolean;
      okText?: string;
      cancelText?: string;
    }): Promise<string | null>;
    prompt(title: string, message?: string, value?: string): Promise<string | null>;
    actionSheet(options: {
      title?: string;
      items: string[];
      cancelText?: string;
    }): Promise<number | null>;
    actionSheet(title: string, items: string[]): Promise<number | null>;
  };

  storage: {
    get<T = any>(key: string): Promise<T | null>;
    get<T = any>(key: string, fallback: T): Promise<T>;
    set(key: string, value: any): Promise<void>;
    remove(key: string): Promise<void>;
    clear(): Promise<void>;
    keys(): Promise<string[]>;
    size(): Promise<number>;
  };

  http: {
    request(options: Pocket.HttpOptions): Promise<Pocket.HttpResponse>;
    request(url: string, options?: Omit<Pocket.HttpOptions, 'url'>): Promise<Pocket.HttpResponse>;
  };

  device: {
    info(): Promise<Pocket.DeviceInfo>;
    network(): Promise<Pocket.NetworkInfo>;
  };

  viewport: {
    refresh(): Pocket.DeviceInfo | null;
    safeArea(): Pocket.SafeArea;
  };

  clipboard: {
    read(): Promise<string>;
    write(text: string): Promise<void>;
  };

  share: {
    text(options: { text: string; subject?: string }): Promise<void>;
    text(text: string, subject?: string): Promise<void>;
  };

  file: {
    pick(options?: { accept?: string; multiple?: boolean }): Promise<Pocket.PickedFile[] | null>;
  };

  notify: {
    show(options: { title?: string; body?: string }): Promise<void>;
    show(title: string, body?: string): Promise<void>;
  };

  image: {
    status(): Promise<{ available: boolean; model: string; baseUrl: string }>;
    generate(options: Pocket.ImageOptions): Promise<string[]>;
    generate(prompt: string): Promise<string[]>;
  };

  files: {
    list(options?: { direction?: '' | 'in' | 'out' }): Promise<Pocket.TransferFile[]>;
    rename(id: string, name: string): Promise<Pocket.TransferFile>;
    remove(ids: string | string[]): Promise<{ ok: boolean; deleted: number }>;
    save(id: string): Promise<{ ok: boolean; path: string }>;
    url(id: string): string;
  };

  transfer: {
    status(): Promise<{
      running: boolean;
      port: number;
      ip: string;
      deviceId: string;
      deviceName: string;
      peers: number;
    }>;
    devices(): Promise<Pocket.TransferPeer[]>;
    pickAndSend(deviceId: string): Promise<{ ok: boolean; sent: string[] }>;
    sendStored(deviceId: string, ids: string | string[]): Promise<{ ok: boolean; sent: string[] }>;
  };

  system: {
    openExternal(url: string): Promise<void>;
    exit(): Promise<void>;
  };

  on(name: Pocket.EventName, handler: (payload: any) => any): () => void;
  off(name: Pocket.EventName, handler: (payload: any) => any): void;
};

interface Window {
  pocket: typeof pocket;
  PocketNative?: {
    call(payload: string): string;
    callAsync(payload: string): void;
  };
  __pocketResolve?: (id: number, payload: string) => void;
  __pocketEvent?: (name: string, payload: string) => void;
  __pocketDispatchBack?: () => string;
}
