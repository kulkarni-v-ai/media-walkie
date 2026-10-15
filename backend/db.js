const { Pool } = require('pg');

const isProduction = process.env.NODE_ENV === 'production';
const databaseUrl = process.env.DATABASE_URL;

// Configure connection pool with SSL support for Render Postgres
const poolConfig = {
  connectionString: databaseUrl || 'postgresql://postgres:postgres@localhost:5432/mediawalkie',
};

// Render Postgres requires SSL with rejectUnauthorized: false when connecting from external networks or Render internal services
if (databaseUrl) {
  const requiresSsl =
    process.env.NODE_ENV === 'production' ||
    process.env.DB_SSL === 'true' ||
    databaseUrl.includes('render.com') ||
    databaseUrl.includes('dpg-') ||
    databaseUrl.includes('sslmode=require') ||
    (!databaseUrl.includes('localhost') && !databaseUrl.includes('127.0.0.1'));

  if (requiresSsl && process.env.DB_SSL !== 'false') {
    poolConfig.ssl = {
      rejectUnauthorized: false
    };
  }
}

const pool = new Pool(poolConfig);

pool.on('error', (err) => {
  console.error('Unexpected error on idle PostgreSQL client:', err);
});

// UUID Validator helper
function isValidUUID(str) {
  if (!str || typeof str !== 'string') return false;
  const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  return uuidRegex.test(str);
}

// Data formatters ensuring full backward-compatibility with MongoDB _id
function formatGroup(row) {
  if (!row) return null;
  const idStr = row.id ? row.id.toString() : '';
  return {
    id: idStr,
    _id: idStr,
    name: row.name,
    frequency: row.frequency,
    pin: row.pin || '',
    rangeDescription: row.range_description || 'Standard Range (100m)',
    createdAt: row.created_at
  };
}

function formatUser(row, assignedGroups = []) {
  if (!row) return null;
  const idStr = row.id ? row.id.toString() : '';
  return {
    id: idStr,
    _id: idStr,
    name: row.name,
    pin: row.pin,
    email: row.email,
    phone: row.phone,
    deviceId: row.device_id,
    isVerified: row.is_verified,
    isAdmin: row.is_admin,
    assignedGroups: assignedGroups,
    createdAt: row.created_at
  };
}

// Schema initialization (auto-creates tables and relationships on startup)
async function initDb() {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Enable pgcrypto for gen_random_uuid() support
    await client.query(`CREATE EXTENSION IF NOT EXISTS "pgcrypto";`);

    // Users table
    await client.query(`
      CREATE TABLE IF NOT EXISTS users (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        name VARCHAR(255) NOT NULL,
        pin VARCHAR(255) NOT NULL,
        email VARCHAR(255),
        phone VARCHAR(255),
        device_id VARCHAR(255),
        is_verified BOOLEAN DEFAULT FALSE,
        is_admin BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Groups table
    await client.query(`
      CREATE TABLE IF NOT EXISTS groups (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        name VARCHAR(255) UNIQUE NOT NULL,
        frequency VARCHAR(50) NOT NULL,
        pin VARCHAR(50) DEFAULT '',
        range_description VARCHAR(255) DEFAULT 'Standard Range (100m)',
        created_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // User-Groups join table (many-to-many relationship)
    await client.query(`
      CREATE TABLE IF NOT EXISTS user_groups (
        user_id UUID REFERENCES users(id) ON DELETE CASCADE,
        group_id UUID REFERENCES groups(id) ON DELETE CASCADE,
        PRIMARY KEY (user_id, group_id)
      );
    `);

    // Performance indices
    await client.query(`CREATE INDEX IF NOT EXISTS idx_users_name_pin ON users (name, pin);`);
    await client.query(`CREATE INDEX IF NOT EXISTS idx_groups_frequency ON groups (frequency);`);
    await client.query(`CREATE INDEX IF NOT EXISTS idx_user_groups_user_id ON user_groups (user_id);`);
    await client.query(`CREATE INDEX IF NOT EXISTS idx_user_groups_group_id ON user_groups (group_id);`);

    await client.query('COMMIT');
    console.log('PostgreSQL database initialized successfully');
  } catch (err) {
    await client.query('ROLLBACK');
    console.error('Failed to initialize PostgreSQL database:', err);
    throw err;
  } finally {
    client.release();
  }
}

// User operations
async function createUser({ name, pin, deviceId, email, phone, isAdmin, isVerified }) {
  const result = await pool.query(
    `INSERT INTO users (name, pin, device_id, email, phone, is_admin, is_verified)
     VALUES ($1, $2, $3, $4, $5, $6, $7)
     RETURNING *`,
    [name, pin, deviceId || null, email || null, phone || null, Boolean(isAdmin), Boolean(isVerified)]
  );
  return formatUser(result.rows[0], []);
}

async function findUserByNameAndPin(name, pin) {
  const userRes = await pool.query(
    `SELECT * FROM users WHERE name = $1 AND pin = $2`,
    [name, pin]
  );
  if (userRes.rows.length === 0) return null;
  const user = userRes.rows[0];

  const groupsRes = await pool.query(
    `SELECT g.* FROM groups g
     JOIN user_groups ug ON g.id = ug.group_id
     WHERE ug.user_id = $1
     ORDER BY g.created_at DESC`,
    [user.id]
  );
  return formatUser(user, groupsRes.rows.map(formatGroup));
}

async function findUserById(userId) {
  if (!isValidUUID(userId)) return null;
  const userRes = await pool.query(
    `SELECT * FROM users WHERE id = $1`,
    [userId]
  );
  if (userRes.rows.length === 0) return null;
  const user = userRes.rows[0];

  const groupsRes = await pool.query(
    `SELECT g.* FROM groups g
     JOIN user_groups ug ON g.id = ug.group_id
     WHERE ug.user_id = $1
     ORDER BY g.created_at DESC`,
    [user.id]
  );
  return formatUser(user, groupsRes.rows.map(formatGroup));
}

async function getAllUsers() {
  const query = `
    SELECT u.*,
      COALESCE(
        json_agg(
          json_build_object(
            'id', g.id,
            '_id', g.id,
            'name', g.name,
            'frequency', g.frequency,
            'pin', g.pin,
            'rangeDescription', g.range_description,
            'createdAt', g.created_at
          )
        ) FILTER (WHERE g.id IS NOT NULL),
        '[]'
      ) as "assignedGroups"
    FROM users u
    LEFT JOIN user_groups ug ON u.id = ug.user_id
    LEFT JOIN groups g ON ug.group_id = g.id
    GROUP BY u.id
    ORDER BY u.created_at DESC
  `;
  const result = await pool.query(query);
  return result.rows.map(row => {
    const assigned = Array.isArray(row.assignedGroups) ? row.assignedGroups : JSON.parse(row.assignedGroups || '[]');
    return formatUser(row, assigned);
  });
}

async function updateUser(userId, { isVerified, assignedGroups, pin }) {
  if (!isValidUUID(userId)) return null;
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    const checkUser = await client.query('SELECT * FROM users WHERE id = $1', [userId]);
    if (checkUser.rows.length === 0) {
      await client.query('ROLLBACK');
      return null;
    }

    const updates = [];
    const values = [];
    let paramIndex = 1;

    if (isVerified !== undefined) {
      updates.push(`is_verified = $${paramIndex++}`);
      values.push(Boolean(isVerified));
    }
    if (pin !== undefined) {
      updates.push(`pin = $${paramIndex++}`);
      values.push(pin);
    }

    if (updates.length > 0) {
      values.push(userId);
      await client.query(
        `UPDATE users SET ${updates.join(', ')} WHERE id = $${paramIndex}`,
        values
      );
    }

    if (assignedGroups !== undefined && Array.isArray(assignedGroups)) {
      await client.query('DELETE FROM user_groups WHERE user_id = $1', [userId]);
      for (const groupId of assignedGroups) {
        if (isValidUUID(groupId)) {
          await client.query(
            `INSERT INTO user_groups (user_id, group_id)
             VALUES ($1, $2)
             ON CONFLICT DO NOTHING`,
            [userId, groupId]
          );
        }
      }
    }

    await client.query('COMMIT');
    return true;
  } catch (err) {
    await client.query('ROLLBACK');
    throw err;
  } finally {
    client.release();
  }
}

// Group operations
async function createGroup({ name, frequency, pin, rangeDescription }) {
  const result = await pool.query(
    `INSERT INTO groups (name, frequency, pin, range_description)
     VALUES ($1, $2, $3, $4)
     RETURNING *`,
    [name, frequency, pin || '', rangeDescription || 'Standard Range (100m)']
  );
  return formatGroup(result.rows[0]);
}

async function getAllGroups() {
  const result = await pool.query(`SELECT * FROM groups ORDER BY created_at DESC`);
  return result.rows.map(formatGroup);
}

async function updateGroup(groupId, { name, frequency, pin, rangeDescription }) {
  if (!isValidUUID(groupId)) return null;
  const updates = [];
  const values = [];
  let paramIndex = 1;

  if (name !== undefined) {
    updates.push(`name = $${paramIndex++}`);
    values.push(name);
  }
  if (frequency !== undefined) {
    updates.push(`frequency = $${paramIndex++}`);
    values.push(frequency);
  }
  if (pin !== undefined) {
    updates.push(`pin = $${paramIndex++}`);
    values.push(pin);
  }
  if (rangeDescription !== undefined) {
    updates.push(`range_description = $${paramIndex++}`);
    values.push(rangeDescription);
  }

  if (updates.length === 0) {
    const existing = await pool.query('SELECT * FROM groups WHERE id = $1', [groupId]);
    return existing.rows[0] ? formatGroup(existing.rows[0]) : null;
  }

  values.push(groupId);
  const result = await pool.query(
    `UPDATE groups SET ${updates.join(', ')} WHERE id = $${paramIndex} RETURNING *`,
    values
  );
  return result.rows[0] ? formatGroup(result.rows[0]) : null;
}

async function deleteGroup(groupId) {
  if (!isValidUUID(groupId)) return false;
  // Cascading foreign keys in user_groups will automatically delete associations
  const result = await pool.query('DELETE FROM groups WHERE id = $1 RETURNING id', [groupId]);
  return result.rowCount > 0;
}

module.exports = {
  pool,
  initDb,
  isValidUUID,
  formatGroup,
  formatUser,
  createUser,
  findUserByNameAndPin,
  findUserById,
  getAllUsers,
  updateUser,
  createGroup,
  getAllGroups,
  updateGroup,
  deleteGroup,
};
