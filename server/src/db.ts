import pg from 'pg';
export const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
export async function tx<T>(fn:(client:pg.PoolClient)=>Promise<T>):Promise<T>{const c=await pool.connect();try{await c.query('BEGIN');const out=await fn(c);await c.query('COMMIT');return out}catch(e){await c.query('ROLLBACK');throw e}finally{c.release()}}
