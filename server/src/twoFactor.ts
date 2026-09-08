import type { Express } from 'express';
import { createHash, randomBytes, randomInt } from 'node:crypto';
import { rateLimit } from 'express-rate-limit';
import nodemailer from 'nodemailer';
import { z } from 'zod';
import { pool, tx } from './db.js';
import { requireAuth } from './auth.js';
import { createSession, hashPassword, verifyPassword } from './freeAuth.js';

const limiter = rateLimit({
  windowMs: 15 * 60_000,
  limit: Number(process.env.TWO_FACTOR_RATE_LIMIT_PER_15_MINUTES ?? 20),
  standardHeaders: 'draft-8',
  legacyHeaders: false,
});

type Purpose = 'login' | 'enable' | 'disable';

function deliveryConfigured() {
  return Boolean(process.env.MAIL_HOST && process.env.MAIL_FROM);
}

function mailTransportOptions() {
  const port = Number(process.env.MAIL_PORT ?? 587);
  const secure = process.env.MAIL_SECURE ? process.env.MAIL_SECURE === 'true' : port === 465;
  const requireTLS = process.env.MAIL_REQUIRE_TLS ? process.env.MAIL_REQUIRE_TLS === 'true' : !secure;
  return {
    host: process.env.MAIL_HOST,
    port,
    secure,
    requireTLS,
    auth: process.env.MAIL_USER ? { user: process.env.MAIL_USER, pass: process.env.MAIL_PASSWORD } : undefined,
    connectionTimeout: 10_000,
    socketTimeout: 15_000,
  };
}

const challengeHash = (token: string) => createHash('sha256').update(token, 'utf8').digest('hex');

async function sendCode(email: string, code: string, purpose: Purpose) {
  if (!deliveryConfigured()) throw new Error('email_delivery_unavailable');
  const transport = nodemailer.createTransport(mailTransportOptions());
  try {
    const reason = purpose === 'enable'
      ? 'enable two-step verification'
      : purpose === 'disable'
        ? 'disable two-step verification'
        : 'complete your sign in';
    await transport.sendMail({
      from: process.env.MAIL_FROM,
      to: email,
      subject: 'ZoolChat two-step verification code',
      text: `Your ZoolChat verification code is ${code}. Use it to ${reason}. It expires in 10 minutes. If you did not request this, do not share the code.`,
    });
  } finally {
    transport.close();
  }
}

async function issueChallenge(userId: string, email: string, purpose: Purpose, deviceName?: string) {
  const token = `zc2_${randomBytes(32).toString('base64url')}`;
  const code = String(randomInt(0, 100_000_000)).padStart(8, '0');
  const codeHash = await hashPassword(code);
  const id = await tx(async c => {
    await c.query('SELECT id FROM users WHERE id=$1 FOR UPDATE', [userId]);
    const recent = await c.query(
      "SELECT 1 FROM two_factor_challenges WHERE user_id=$1 AND purpose=$2 AND created_at>now()-interval '45 seconds' AND consumed_at IS NULL",
      [userId, purpose],
    );
    if (recent.rowCount) throw new Error('two_factor_rate_limited');
    await c.query('UPDATE two_factor_challenges SET consumed_at=now() WHERE user_id=$1 AND purpose=$2 AND consumed_at IS NULL', [userId, purpose]);
    const r = await c.query(
      `INSERT INTO two_factor_challenges(user_id,purpose,challenge_hash,code_hash,device_name)
       VALUES($1,$2,$3,$4,$5) RETURNING id`,
      [userId, purpose, challengeHash(token), codeHash, deviceName?.slice(0, 100) ?? null],
    );
    return String(r.rows[0].id);
  });
  try {
    await sendCode(email, code, purpose);
  } catch (e) {
    await pool.query('UPDATE two_factor_challenges SET consumed_at=now() WHERE id=$1', [id]).catch(() => {});
    throw e;
  }
  return token;
}

async function consumeChallenge(token: string, code: string, purpose: Purpose, expectedUserId?: string) {
  if (!token.startsWith('zc2_')) return null;
  return tx(async c => {
    const r = await c.query(
      `SELECT * FROM two_factor_challenges
       WHERE challenge_hash=$1 AND purpose=$2 AND consumed_at IS NULL AND expires_at>now() AND attempts<5
       ORDER BY created_at DESC LIMIT 1 FOR UPDATE`,
      [challengeHash(token), purpose],
    );
    if (!r.rowCount) return null;
    const row = r.rows[0];
    if (expectedUserId && String(row.user_id) !== expectedUserId) return null;
    await c.query('UPDATE two_factor_challenges SET attempts=attempts+1 WHERE id=$1', [row.id]);
    if (!(await verifyPassword(code, String(row.code_hash)))) return null;
    await c.query('UPDATE two_factor_challenges SET consumed_at=now() WHERE id=$1', [row.id]);
    return row;
  });
}

export async function beginLoginTwoFactorIfEnabled(userId: string, deviceName?: string) {
  const r = await pool.query(
    `SELECT u.email,u.email_verified_at,COALESCE(t.enabled,false) enabled
     FROM users u LEFT JOIN user_two_factor_settings t ON t.user_id=u.id WHERE u.id=$1`,
    [userId],
  );
  if (!r.rowCount || !r.rows[0].enabled) return null;
  const email = String(r.rows[0].email ?? '').trim().toLowerCase();
  if (!email || !r.rows[0].email_verified_at) throw new Error('two_factor_email_unavailable');
  const challengeToken = await issueChallenge(userId, email, 'login', deviceName);
  return { twoFactorRequired: true, challengeToken, expiresInSeconds: 600 };
}

function userJson(r: any) {
  return {
    id: r.id,
    displayName: r.display_name,
    username: r.username ?? null,
    email: r.email ?? null,
    emailVerified: Boolean(r.email_verified_at),
    phoneE164: r.phone_e164 ?? null,
    photoUrl: r.profile_photo_media_id ? `/api/media/${r.profile_photo_media_id}` : (r.photo_url ?? null),
    about: r.about ?? '',
  };
}

export function installTwoFactor(app: Express) {
  app.post('/api/auth/login/2fa', limiter, async (req, res) => {
    const b = z.object({
      challengeToken: z.string().min(20).max(200),
      code: z.string().regex(/^\d{8}$/),
      deviceName: z.string().trim().max(100).optional(),
    }).safeParse(req.body);
    if (!b.success) return res.status(400).json({ error: 'invalid_request' });
    const challenge = await consumeChallenge(b.data.challengeToken, b.data.code, 'login');
    if (!challenge) return res.status(401).json({ error: 'invalid_two_factor_code' });
    const user = await pool.query("SELECT * FROM users WHERE id=$1 AND account_status='active'", [challenge.user_id]);
    if (!user.rowCount) return res.status(401).json({ error: 'invalid_two_factor_code' });
    const session = await createSession(String(challenge.user_id), b.data.deviceName || challenge.device_name || undefined);
    return res.json({ user: userJson(user.rows[0]), ...session });
  });

  app.get('/api/me/2fa', requireAuth, limiter, async (req, res) => {
    const r = await pool.query(
      `SELECT COALESCE(t.enabled,false) enabled,u.email_verified_at
       FROM users u LEFT JOIN user_two_factor_settings t ON t.user_id=u.id WHERE u.id=$1`,
      [req.zoolUser!.id],
    );
    if (!r.rowCount) return res.status(404).json({ error: 'user_not_found' });
    res.json({
      enabled: Boolean(r.rows[0].enabled),
      available: Boolean(r.rows[0].email_verified_at) && deliveryConfigured(),
      emailVerified: Boolean(r.rows[0].email_verified_at),
    });
  });

  app.post('/api/me/2fa/enable/request', requireAuth, limiter, async (req, res) => {
    const b = z.object({ password: z.string().min(8).max(128) }).safeParse(req.body);
    if (!b.success) return res.status(400).json({ error: 'invalid_request' });
    const uid = req.zoolUser!.id;
    const r = await pool.query(
      `SELECT u.email,u.email_verified_at,uc.password_hash,COALESCE(t.enabled,false) enabled
       FROM users u JOIN user_credentials uc ON uc.user_id=u.id
       LEFT JOIN user_two_factor_settings t ON t.user_id=u.id WHERE u.id=$1`,
      [uid],
    );
    if (!r.rowCount || !(await verifyPassword(b.data.password, String(r.rows[0].password_hash)))) return res.status(403).json({ error: 'invalid_password' });
    if (r.rows[0].enabled) return res.status(409).json({ error: 'two_factor_already_enabled' });
    if (!r.rows[0].email_verified_at || !r.rows[0].email || !deliveryConfigured()) return res.status(409).json({ error: 'verified_email_required' });
    try {
      const challengeToken = await issueChallenge(uid, String(r.rows[0].email), 'enable');
      return res.status(202).json({ challengeToken, expiresInSeconds: 600 });
    } catch (e: any) {
      if (e?.message === 'two_factor_rate_limited') return res.status(429).json({ error: 'two_factor_rate_limited' });
      return res.status(503).json({ error: 'email_delivery_unavailable' });
    }
  });

  app.post('/api/me/2fa/enable/confirm', requireAuth, limiter, async (req, res) => {
    const b = z.object({ challengeToken: z.string().min(20).max(200), code: z.string().regex(/^\d{8}$/) }).safeParse(req.body);
    if (!b.success) return res.status(400).json({ error: 'invalid_request' });
    const row = await consumeChallenge(b.data.challengeToken, b.data.code, 'enable', req.zoolUser!.id);
    if (!row) return res.status(400).json({ error: 'invalid_two_factor_code' });
    await pool.query(
      `INSERT INTO user_two_factor_settings(user_id,enabled,enabled_at,updated_at) VALUES($1,true,now(),now())
       ON CONFLICT(user_id) DO UPDATE SET enabled=true,enabled_at=now(),updated_at=now()`,
      [req.zoolUser!.id],
    );
    res.json({ ok: true, enabled: true });
  });

  app.post('/api/me/2fa/disable/request', requireAuth, limiter, async (req, res) => {
    const b = z.object({ password: z.string().min(8).max(128) }).safeParse(req.body);
    if (!b.success) return res.status(400).json({ error: 'invalid_request' });
    const uid = req.zoolUser!.id;
    const r = await pool.query(
      `SELECT u.email,u.email_verified_at,uc.password_hash,COALESCE(t.enabled,false) enabled
       FROM users u JOIN user_credentials uc ON uc.user_id=u.id
       LEFT JOIN user_two_factor_settings t ON t.user_id=u.id WHERE u.id=$1`,
      [uid],
    );
    if (!r.rowCount || !(await verifyPassword(b.data.password, String(r.rows[0].password_hash)))) return res.status(403).json({ error: 'invalid_password' });
    if (!r.rows[0].enabled) return res.status(409).json({ error: 'two_factor_not_enabled' });
    if (!r.rows[0].email_verified_at || !r.rows[0].email || !deliveryConfigured()) return res.status(409).json({ error: 'verified_email_required' });
    try {
      const challengeToken = await issueChallenge(uid, String(r.rows[0].email), 'disable');
      return res.status(202).json({ challengeToken, expiresInSeconds: 600 });
    } catch (e: any) {
      if (e?.message === 'two_factor_rate_limited') return res.status(429).json({ error: 'two_factor_rate_limited' });
      return res.status(503).json({ error: 'email_delivery_unavailable' });
    }
  });

  app.post('/api/me/2fa/disable/confirm', requireAuth, limiter, async (req, res) => {
    const b = z.object({ challengeToken: z.string().min(20).max(200), code: z.string().regex(/^\d{8}$/) }).safeParse(req.body);
    if (!b.success) return res.status(400).json({ error: 'invalid_request' });
    const row = await consumeChallenge(b.data.challengeToken, b.data.code, 'disable', req.zoolUser!.id);
    if (!row) return res.status(400).json({ error: 'invalid_two_factor_code' });
    await pool.query(
      `INSERT INTO user_two_factor_settings(user_id,enabled,enabled_at,updated_at) VALUES($1,false,NULL,now())
       ON CONFLICT(user_id) DO UPDATE SET enabled=false,enabled_at=NULL,updated_at=now()`,
      [req.zoolUser!.id],
    );
    res.json({ ok: true, enabled: false });
  });
}
