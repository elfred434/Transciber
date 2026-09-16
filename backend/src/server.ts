import 'dotenv/config';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import express, { type Request, type Response, type NextFunction } from 'express';
import rateLimit from 'express-rate-limit';
import helmet from 'helmet';
import multer from 'multer';
import { GoogleGenAI } from '@google/genai';

const port = Number(process.env.PORT ?? 8787);
const apiKey = process.env.GEMINI_API_KEY;
const clientToken = process.env.GATEWAY_CLIENT_TOKEN;
const maxAudioBytes = Number(process.env.MAX_AUDIO_MB ?? 25) * 1024 * 1024;

if (!apiKey) {
  console.warn('GEMINI_API_KEY is not set. The gateway will start but AI requests will fail.');
}

const gemini = apiKey ? new GoogleGenAI({ apiKey }) : null;
const app = express();
app.disable('x-powered-by');
app.use(helmet());
app.use(express.json({ limit: '256kb' }));
app.use(rateLimit({ windowMs: 60_000, limit: 60, standardHeaders: true, legacyHeaders: false }));

const upload = multer({
  dest: path.join(os.tmpdir(), 'transciber'),
  limits: { fileSize: maxAudioBytes },
});

app.get('/health', (_req, res) => res.json({ ok: true, service: 'transciber-gemini-gateway' }));

app.use((req: Request, res: Response, next: NextFunction) => {
  if (!clientToken || req.path === '/health') return next();
  if (req.header('X-Client-Token') !== clientToken) {
    return res.status(401).json({ error: 'Client non autorisé.' });
  }
  return next();
});

app.post('/v1/translate', async (req, res) => {
  try {
    const text = String(req.body?.text ?? '').trim();
    const sourceLanguage = String(req.body?.sourceLanguage ?? 'auto').trim();
    const targetLanguage = String(req.body?.targetLanguage ?? 'English').trim();
    if (!text) return res.status(400).json({ error: 'Le texte à traduire est obligatoire.' });
    if (text.length > 40_000) return res.status(413).json({ error: 'Texte trop long.' });

    const result = await generateText(`
Tu es le moteur de traduction de l'application Transciber.
Traduis le texte ci-dessous de ${sourceLanguage} vers ${targetLanguage}.
Retourne uniquement un objet JSON valide avec la clé "translation".
Conserve le sens, le ton, les noms propres, les nombres et les emojis.
N'ajoute aucune explication et n'invente aucun contenu.

Texte :
${text}
`, process.env.GEMINI_TRANSLATION_MODEL ?? 'gemini-3.1-flash-lite');

    const parsed = parseJson(result);
    return res.json({
      translation: String(parsed.translation ?? result).trim(),
      sourceLanguage,
      targetLanguage,
      requestId: crypto.randomUUID(),
    });
  } catch (error) {
    return sendError(res, error);
  }
});

app.post('/v1/summarize', async (req, res) => {
  try {
    const text = String(req.body?.text ?? '').trim();
    const language = String(req.body?.language ?? 'Français').trim();
    if (!text) return res.status(400).json({ error: 'Le texte à résumer est obligatoire.' });

    const result = await generateText(`
Tu es le moteur de résumé de l'application Transciber.
Résume le texte suivant en ${language}.
Retourne uniquement un objet JSON valide avec la clé "summary".
Le résumé doit être court, fidèle et utile. Conserve les noms, dates et actions importantes.

Texte :
${text}
`, process.env.GEMINI_TRANSLATION_MODEL ?? 'gemini-3.1-flash-lite');

    const parsed = parseJson(result);
    return res.json({ summary: String(parsed.summary ?? result).trim(), requestId: crypto.randomUUID() });
  } catch (error) {
    return sendError(res, error);
  }
});

app.post('/v1/transcribe', upload.single('audio'), async (req, res) => {
  const file = req.file;
  if (!file) return res.status(400).json({ error: 'Le fichier audio est obligatoire.' });
  try {
    const result = await transcribeFile(file.path, file.mimetype || String(req.body?.mimeType ?? 'audio/ogg'));
    const parsed = parseJson(result);
    return res.json({
      text: String(parsed.text ?? result).trim(),
      sourceLanguage: String(parsed.sourceLanguage ?? 'auto'),
      requestId: crypto.randomUUID(),
    });
  } catch (error) {
    return sendError(res, error);
  } finally {
    await fs.rm(file.path, { force: true }).catch(() => undefined);
  }
});

async function generateText(prompt: string, model: string): Promise<string> {
  if (!gemini) throw new Error('Le serveur Gemini n’est pas configuré.');
  const response = await gemini.models.generateContent({ model, contents: prompt });
  return response.text?.trim() ?? '';
}

async function transcribeFile(filePath: string, mimeType: string): Promise<string> {
  if (!gemini) throw new Error('Le serveur Gemini n’est pas configuré.');
  const uploaded = await gemini.files.upload({ file: filePath, config: { mimeType } });
  const response = await gemini.models.generateContent({
    model: process.env.GEMINI_AUDIO_MODEL ?? 'gemini-3.5-flash',
    contents: [
      {
        role: 'user',
        parts: [
          { fileData: { fileUri: uploaded.uri, mimeType: uploaded.mimeType ?? mimeType } },
          {
            text: 'Transcris fidèlement cet audio. Retourne uniquement un objet JSON valide avec les clés "text" et "sourceLanguage". Ne résume pas et ne traduis pas.',
          },
        ],
      },
    ],
  });
  return response.text?.trim() ?? '';
}

function parseJson(text: string): Record<string, unknown> {
  const cleaned = text.replace(/^```(?:json)?/i, '').replace(/```$/i, '').trim();
  try {
    return JSON.parse(cleaned) as Record<string, unknown>;
  } catch {
    return { text: cleaned, translation: cleaned, summary: cleaned };
  }
}

function sendError(res: Response, error: unknown) {
  const message = error instanceof Error ? error.message : 'Erreur interne.';
  console.error(message);
  return res.status(502).json({ error: message });
}

app.listen(port, '0.0.0.0', () => {
  console.log(`Transciber Gemini gateway listening on :${port}`);
});
