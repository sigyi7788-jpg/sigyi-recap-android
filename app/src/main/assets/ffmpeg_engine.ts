/*
 * Typed source for the WebView FFmpeg.wasm bridge.
 * ffmpeg_engine.html contains the browser-ready runtime equivalent.
 */

declare const FFmpegWASM: { FFmpeg: new () => FfmpegInstance };
declare const FFmpegUtil: {
  toBlobURL(url: string, mime: string): Promise<string>;
};

type TimedWord = { text: string; start_ms: number; end_ms: number };
type SubtitleSegment = {
  text: string;
  start_ms: number;
  end_ms: number;
  words?: TimedWord[];
};
type RenderOptions = { include_subtitles?: boolean };

type FfmpegInstance = {
  on(event: 'progress', callback: (event: { progress: number }) => void): void;
  load(options: { coreURL: string; wasmURL: string }): Promise<void>;
  writeFile(name: string, data: Uint8Array): Promise<void>;
  deleteFile(name: string): Promise<void>;
  exec(args: string[]): Promise<number>;
  readFile(name: string): Promise<Uint8Array>;
};

type NativeChunkBridge = {
  getVideoSize(): number;
  readVideoChunk(offset: number, length: number): string;
  getAudioSize(index: number): number;
  readAudioChunk(index: number, offset: number, length: number): string;
  onOutputStart(totalChunks: number): void;
  onOutputChunk(index: number, totalChunks: number, base64: string): void;
  onOutputComplete(): void;
};

declare global {
  interface Window {
    SigyiNative: NativeChunkBridge;
  }
}

const CHUNK_SIZE = 512 * 1024;

const decodeBase64 = (value: string): Uint8Array => {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
};

const encodeBase64 = (bytes: Uint8Array): string => {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
};

export async function writeNativeFile(
  ffmpeg: FfmpegInstance,
  name: string,
  size: number,
  readChunk: (offset: number, length: number) => string
): Promise<void> {
  const merged = new Uint8Array(size);
  for (let offset = 0; offset < size; offset += CHUNK_SIZE) {
    const length = Math.min(CHUNK_SIZE, size - offset);
    const chunk = decodeBase64(readChunk(offset, length));
    if (chunk.length !== length) throw new Error(`${name} chunk is incomplete`);
    merged.set(chunk, offset);
  }
  await ffmpeg.writeFile(name, merged);
};

export const srtTime = (milliseconds: number): string => {
  const total = Math.max(0, Math.round(milliseconds));
  const hours = Math.floor(total / 3_600_000);
  const minutes = Math.floor((total % 3_600_000) / 60_000);
  const seconds = Math.floor((total % 60_000) / 1_000);
  const millis = total % 1_000;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:` +
    `${String(seconds).padStart(2, '0')},${String(millis).padStart(3, '0')}`;
};

export const buildSrt = (segments: SubtitleSegment[]): string => {
  let number = 1;
  const entries: string[] = [];
  for (const segment of segments) {
    const words = segment.words?.length ? segment.words : [{
      text: segment.text,
      start_ms: segment.start_ms,
      end_ms: segment.end_ms,
    }];
    for (const word of words) {
      entries.push(`${number++}\n${srtTime(word.start_ms)} --> ${srtTime(word.end_ms)}\n` +
        `${word.text.replace(/[\r\n]+/g, ' ')}\n`);
    }
  }
  return entries.join('\n');
};

export const buildTtsOnlyMuxCommand = (
  audioCount: number,
  options: Required<RenderOptions>
): string[] => {
  if (audioCount < 1) throw new Error('Narration audio segment မရှိပါ။');
  const inputs: string[] = [];
  const labels: string[] = [];
  for (let index = 0; index < audioCount; index += 1) {
    inputs.push('-i', `audio${index}.wav`);
    labels.push(`[${index + 1}:a]`);
  }
  const narration = `${labels.join('')}concat=n=${audioCount}:v=0:a=1[narration]`;
  const command = [
    '-i', 'input.mp4', ...inputs,
    '-filter_complex', narration,
    '-map', '0:v:0',
    '-map', '[narration]',
    '-map_metadata', '-1',
    '-map_chapters', '-1'
  ];
  if (options.include_subtitles) command.push('-vf', 'subtitles=subtitle.srt');
  return [...command, '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '27',
    '-c:a', 'aac', '-b:a', '128k', '-shortest', '-movflags', '+faststart', 'output.mp4'];
};

export { decodeBase64, encodeBase64 };
