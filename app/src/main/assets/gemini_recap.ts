export type RecapSegment = {
  text: string;
  source_start_ms: number;
  source_end_ms: number;
};

export type GeminiRecapOptions = {
  model?: string;
  segmentMinSeconds?: number;
  segmentMaxSeconds?: number;
  tone?: string;
};

type GeminiFile = { name: string; uri: string; mimeType?: string; state?: string };
type GeminiResponse = {
  candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
};

const API_ROOT = 'https://generativelanguage.googleapis.com';

export class GeminiRecapError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = 'GeminiRecapError';
  }
}

export function buildBurmeseRecapPrompt(options: GeminiRecapOptions = {}): string {
  const min = options.segmentMinSeconds ?? 2;
  const max = options.segmentMaxSeconds ?? 8;
  const tone = options.tone ?? 'cinematic, emotional, accurate';
  return [
    'You are SIGYI AI Recap Studio, a professional TikTok movie-recap writer.',
    'Watch the attached foreign movie video carefully and write a complete Burmese Unicode voice-over.',
    'Follow only the visible story in chronological order. Never invent scenes, names, motives, or endings.',
    'Paraphrase dialogue; do not copy long original dialogue or add an English explanation.',
    `Use natural spoken Myanmar with a ${tone} tone. Split it into ${min}-${max} second narration segments.`,
    'Return ONLY a valid JSON array with no markdown fences.',
    'Each item must be exactly {"text":"Myanmar narration","source_start_ms":0,"source_end_ms":1000}.',
    'All source times must be non-negative integers.'
  ].join('\n');
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  let body: unknown;
  try { body = JSON.parse(text); } catch { body = text; }
  if (!response.ok) throw new GeminiRecapError(`Gemini request failed (${response.status})`, response.status);
  return body;
}

async function uploadVideo(video: File, apiKey: string): Promise<GeminiFile> {
  const metadata = new Blob([JSON.stringify({ file: { display_name: video.name } })], {
    type: 'application/json'
  });
  const form = new FormData();
  form.append('metadata', metadata);
  form.append('file', video, video.name);
  const response = await fetch(`${API_ROOT}/upload/v1beta/files?key=${encodeURIComponent(apiKey)}`, {
    method: 'POST',
    headers: { 'X-Goog-Upload-Protocol': 'multipart' },
    body: form
  });
  const body = await readJson(response) as { file?: GeminiFile };
  if (!body.file?.name || !body.file.uri) throw new GeminiRecapError('Gemini upload response is incomplete');
  return body.file;
}

async function waitForFile(fileName: string, apiKey: string): Promise<GeminiFile> {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const response = await fetch(`${API_ROOT}/v1beta/${fileName}?key=${encodeURIComponent(apiKey)}`);
    const body = await readJson(response) as { file?: GeminiFile };
    const file = body.file;
    if (!file) throw new GeminiRecapError('Gemini file status response is incomplete');
    if (!file.state || file.state === 'ACTIVE') return file;
    if (file.state === 'FAILED') throw new GeminiRecapError('Gemini video processing failed');
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  throw new GeminiRecapError('Gemini video processing timeout');
}

function parseSegments(text: string): RecapSegment[] {
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start < 0 || end <= start) throw new GeminiRecapError('Gemini did not return a JSON array');
  let parsed: unknown;
  try { parsed = JSON.parse(text.slice(start, end + 1)); }
  catch { throw new GeminiRecapError('Gemini returned invalid segment JSON'); }
  if (!Array.isArray(parsed)) throw new GeminiRecapError('Gemini segment output is not an array');
  const segments = parsed.flatMap((item): RecapSegment[] => {
    if (!item || typeof item !== 'object') return [];
    const value = item as Record<string, unknown>;
    const narration = String(value.text ?? '').trim();
    if (!narration) return [];
    const startMs = Math.max(0, Number(value.source_start_ms ?? 0) || 0);
    const endMs = Math.max(startMs, Number(value.source_end_ms ?? 0) || 0);
    return [{ text: narration, source_start_ms: startMs, source_end_ms: endMs }];
  });
  if (!segments.length) throw new GeminiRecapError('Gemini returned no usable recap segments');
  return segments;
}

export async function generateBurmeseRecap(
  video: File,
  apiKey: string,
  options: GeminiRecapOptions = {}
): Promise<RecapSegment[]> {
  if (!apiKey.trim()) throw new GeminiRecapError('Gemini API key is required');
  const uploaded = await uploadVideo(video, apiKey);
  const ready = await waitForFile(uploaded.name, apiKey);
  const model = options.model ?? 'gemini-2.5-flash';
  const response = await fetch(
    `${API_ROOT}/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(apiKey)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [
          { file_data: { mime_type: (ready.mimeType ?? video.type) || 'video/mp4', file_uri: ready.uri } },
          { text: buildBurmeseRecapPrompt(options) }
        ] }],
        generationConfig: { temperature: 0.35, maxOutputTokens: 8192 }
      })
    }
  );
  const body = await readJson(response) as GeminiResponse;
  const text = body.candidates?.[0]?.content?.parts?.map(part => part.text ?? '').join('').trim();
  if (!text) throw new GeminiRecapError('Gemini returned an empty recap');
  return parseSegments(text);
}
