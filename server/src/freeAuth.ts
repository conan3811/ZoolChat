import { createHash, randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';
import type { PoolClient } from 'pg';
import { pool } from './db.js';

function derive(password:string,salt:Buffer,length:number,n:number,r:number,p:number):Promise<Buffer>{
  return new Promise((resolve,reject)=>scryptCb(password,salt,length,{N:n,r,p,maxmem:64*1024*1024},(err,key)=>err?reject(err):resolve(Buffer.from(key))));
}
const ACCESS_TTL_MINUTES = Math.max(10, Math.min(Number(process.env.ACCESS_TOKEN_TTL_MINUTES ?? 60), 24 * 60));
const REFRESH_TTL_DAYS = Math.max(1, Math.min(Number(process.env.REFRESH_TOKEN_TTL_DAYS ?? 3650), 3650));
const PASSWORD_N = 16384;
const PASSWORD_R = 8;
const PASSWORD_P = 1;

export function tokenHash(token: string) {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

export async function hashPassword(password: string) {
  const salt = randomBytes(16);
  const derived = await derive(password,salt,64,PASSWORD_N,PASSWORD_R,PASSWORD_P);
  return `scrypt$${PASSWORD_N}$${PASSWORD_R}$${PASSWORD_P}$${salt.toString('base64url')}$${derived.toString('base64url')}`;
}

export async function verifyPassword(password: string, encoded: string) {
  const parts = encoded.split('$');
  if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
  const n = Number(parts[1]), r = Number(parts[2]), p = Number(parts[3]);
  if (![n,r,p].every(Number.isFinite)) return false;
  const salt = Buffer.from(parts[4], 'base64url');
  const expected = Buffer.from(parts[5], 'base64url');
  const actual = await derive(password,salt,expected.length,n,r,p);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

function newOpaqueToken(prefix: string) {
  return `${prefix}_${randomBytes(32).toString('base64url')}`;
}

export async function createSession(userId: string, deviceName?: string, client?: PoolClient) {
  const c = client ?? pool;
  const accessToken = newOpaqueToken('zcat');
  const refreshToken = newOpaqueToken('zcrt');
  const accessExpiresAt = new Date(Date.now() + ACCESS_TTL_MINUTES * 60_000);
  const refreshExpiresAt = new Date(Date.now() + REFRESH_TTL_DAYS * 24 * 60 * 60_000);
  const r = await c.query(`INSERT INTO auth_sessions(user_id,access_token_hash,refresh_token_hash,device_name,access_expires_at,refresh_expires_at)
    VALUES($1,$2,$3,$4,$5,$6) RETURNING id`, [userId, tokenHash(accessToken), tokenHash(refreshToken), deviceName?.slice(0,100) ?? null, accessExpiresAt, refreshExpiresAt]);
  return { sessionId:r.rows[0].id, accessToken, refreshToken, accessExpiresAt:accessExpiresAt.toISOString(), refreshExpiresAt:refreshExpiresAt.toISOString() };
}

export async function resolveAccessToken(token: string) {
  if (!token.startsWith('zcat_')) return null;
  const r = await pool.query(`SELECT s.id session_id,u.id,u.firebase_uid,u.phone_e164,u.display_name,u.account_status
    FROM auth_sessions s JOIN users u ON u.id=s.user_id
    WHERE s.access_token_hash=$1 AND s.revoked_at IS NULL AND s.access_expires_at>now() AND u.account_status='active' LIMIT 1`, [tokenHash(token)]);
  if (!r.rowCount) return null;
  await pool.query(`UPDATE auth_sessions SET last_seen_at=now() WHERE id=$1`, [r.rows[0].session_id]).catch(()=>{});
  return r.rows[0];
}

export async function rotateRefreshToken(refreshToken: string, deviceName?: string) {
  if (!refreshToken.startsWith('zcrt_')) return null;
  const r = await pool.query(`SELECT s.id,s.user_id FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.refresh_token_hash=$1 AND s.revoked_at IS NULL AND s.refresh_expires_at>now() AND u.account_status='active' LIMIT 1 FOR UPDATE`, [tokenHash(refreshToken)]);
  if (!r.rowCount) return null;
  const oldId = r.rows[0].id, userId = r.rows[0].user_id;
  const nextAccess = newOpaqueToken('zcat');
  const nextRefresh = newOpaqueToken('zcrt');
  const accessExpiresAt = new Date(Date.now() + ACCESS_TTL_MINUTES * 60_000);
  const refreshExpiresAt = new Date(Date.now() + REFRESH_TTL_DAYS * 24 * 60 * 60_000);
  await pool.query(`UPDATE auth_sessions SET access_token_hash=$2,refresh_token_hash=$3,device_name=COALESCE($4,device_name),access_expires_at=$5,refresh_expires_at=$6,last_seen_at=now() WHERE id=$1`,
    [oldId, tokenHash(nextAccess), tokenHash(nextRefresh), deviceName?.slice(0,100) ?? null, accessExpiresAt, refreshExpiresAt]);
  return { sessionId:oldId, userId, accessToken:nextAccess, refreshToken:nextRefresh, accessExpiresAt:accessExpiresAt.toISOString(), refreshExpiresAt:refreshExpiresAt.toISOString() };
}

export async function revokeRefreshToken(refreshToken: string) {
  if (!refreshToken.startsWith('zcrt_')) return;
  await pool.query(`UPDATE auth_sessions SET revoked_at=now() WHERE refresh_token_hash=$1`, [tokenHash(refreshToken)]);
}
