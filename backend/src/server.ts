import 'dotenv/config';
import crypto from 'node:crypto';
import express, { type NextFunction, type Request, type Response } from 'express';
import rateLimit from 'express-rate-limit';
import helmet from 'helmet';
import { GoogleGenAI } from '@google/genai';

const port = Number(process.env.PORT ?? 8787);
const apiKey = process.env.GEMINI_API_KEY;
const clientToken = process.env.GATEWAY_CLIENT_TOKEN;

if (!apiKey) {
  console.warn('GEMINI_API_KEY is not set. The gateway will start but AI requests will fail.');
}

const gemini = apiKey ? new GoogleGenAI({ apiKey }) : null;
const app = express();
app.disable('x-powered-by');
app.use(helmet());
app.use(express.json({ limit: '256kb' }));
app.use(rateLimit({ windowMs: 60_000, limit: 60, standardHeaders: true, legacyHeaders: false }));

app.get('/health', (_req, res) => res.json({
  ok: true,
  service: 'transciber-gemini-gateway',
  capabilities: { transcription: 'device', translation: 'gemini', summary: 'device' },
}));

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

async function generateText(prompt: string, model: string): Promise<string> {
  if (!gemini) throw new Error('Le serveur Gemini n’est pas configuré.');
  const response = await gemini.models.generateContent({ model, contents: prompt });
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
