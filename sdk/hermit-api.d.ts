export type HermitErrorCode =
  | "E_INVALID_ARGUMENT" | "E_UNSUPPORTED" | "E_ORIGIN_DENIED" | "E_CAPABILITY_DENIED"
  | "E_OS_PERMISSION_DENIED" | "E_SESSION_EXPIRED" | "E_CANCELLED" | "E_TIMEOUT"
  | "E_CONFLICT" | "E_QUOTA" | "E_STORAGE" | "E_NETWORK" | "E_INTERNAL";

export interface HermitRecord<T = unknown> { collection: string; key: string; value: T; revision: string }
export interface HermitFile { logicalFileId: string; name: string; mime: string; size: number; sha256: string }
export interface HermitCapability { name: string; implemented: boolean; supported: boolean; usable: boolean; lifecycle: string }
export interface HermitApi {
  call<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>;
  runtime: {
    info(): Promise<{ apiMajor: 1; apiMinor: number; sessionId: string; appId: string; role: "store" | "web_app" }>;
    capabilities(): Promise<{ capabilities: HermitCapability[] }>;
  };
  app: { info(): Promise<Record<string, unknown>>; reload(): Promise<{ reloading: true }>; ready(): Promise<{ recorded: true }>; checkUpdate(): Promise<{ canCheck: boolean }> };
  permissions: {
    status(params: { capability: "speech" | "location.approximate" | "location.precise" | "camera.capture" | "clipboard.read" | "network"; scope?: string }): Promise<Record<string, unknown>>;
    request(params: { capability: "speech" | "location.approximate" | "location.precise" | "camera.capture" | "clipboard.read" | "network"; scope?: string }): Promise<{ capability: string; scope: string; addressClass?: "public" | "private" | null; usable: true }>;
  };
  data: {
    get<T = unknown>(params: { collection: string; key: string }): Promise<HermitRecord<T> | null>;
    put<T = unknown>(params: { collection: string; key: string; value: T; expectedRevision?: string }): Promise<Pick<HermitRecord, "collection" | "key" | "revision">>;
    delete(params: { collection: string; key: string; expectedRevision?: string }): Promise<{ deleted: boolean }>;
    scan<T = unknown>(params: { collection: string; prefix?: string; afterKey?: string; limit?: number }): Promise<{ items: HermitRecord<T>[]; nextAfterKey: string | null }>;
    batch(params: { operations: Array<Record<string, unknown>> }): Promise<{ results: Array<Record<string, unknown>> }>;
  };
  files: {
    import(): Promise<HermitFile & { cancelled?: boolean }>;
    writeText(params: { name: string; text: string }): Promise<HermitFile>;
    readText(params: { logicalFileId: string }): Promise<HermitFile & { text: string }>;
    list(): Promise<{ files: HermitFile[] }>;
    export(params: { logicalFileId: string }): Promise<{ exported?: true; cancelled: boolean }>;
    delete(params: { logicalFileId: string }): Promise<{ deleted: boolean }>;
    share(params: { logicalFileId: string; title?: string }): Promise<{ chooserOpened: true }>;
  };
  tts: { voices(): Promise<{ voices: Array<Record<string, unknown>> }>; speak(params: { text: string; language?: string; rate?: number; pitch?: number }): Promise<{ utteranceId: string }>; stop(): Promise<{ stopped: true }>; export(params: { text: string; name?: string; language?: string; rate?: number; pitch?: number }): Promise<HermitFile> };
  speech: { availability(): Promise<{ available: boolean; onDeviceAvailable: boolean }>; start(params?: Record<string, unknown>): Promise<{ subscriptionId: string }>; stop(params?: { subscriptionId?: string }): Promise<Record<string, unknown>>; cancel(): Promise<Record<string, unknown>> };
  location: { getCurrent(params?: { precise?: boolean; timeoutMs?: number; maxAgeMs?: number }): Promise<Record<string, number | boolean | null>>; watch(params?: Record<string, unknown>): Promise<{ subscriptionId: string }>; clearWatch(params: { subscriptionId: string }): Promise<{ cleared: boolean }> };
  camera: { capturePhoto(): Promise<(HermitFile & { cancelled: false }) | { cancelled: true }> };
  share: { text(params: { text: string; title?: string }): Promise<{ chooserOpened: true }>; file(params: { logicalFileId: string; title?: string }): Promise<{ chooserOpened: true }> };
  clipboard: { write(params: { text: string; label?: string }): Promise<{ written: true }>; read(): Promise<{ text: string | null }> };
  haptics: { vibrate(params?: { durationMs?: number }): Promise<{ started: true }>; impact(params?: { style?: "light" | "medium" | "heavy" }): Promise<{ started: true }> };
  network: { status(): Promise<{ connected: boolean }>; request(params: Record<string, unknown>): Promise<Record<string, unknown>> };
  on(event: string, listener: (data: unknown) => void): () => void;
  readonly isReady: boolean;
}

declare global { interface Window { readonly hermit: HermitApi } const hermit: HermitApi }
export {};
