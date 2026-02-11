package com.example.feudal.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;

public class Database {
    private final JavaPlugin plugin;
    private Connection conn;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void open() throws Exception {
        File dbFile = new File(plugin.getDataFolder(), "feudal.db");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        conn = DriverManager.getConnection(url);

        try (Statement st = conn.createStatement()) {

            // --------------------
            // 기본 테이블
            // --------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS families (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT UNIQUE NOT NULL,
                  lord_uuid TEXT NOT NULL
                )
            """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS members (
                  uuid TEXT PRIMARY KEY,
                  family_id INTEGER,
                  rank TEXT NOT NULL,
                  job TEXT NOT NULL DEFAULT 'NONE',
                  is_serf INTEGER NOT NULL DEFAULT 0,

                  -- ✅ 농노 12345 상태값들(플레이어용)
                  serf_miss_count INTEGER NOT NULL DEFAULT 0,
                  serf_deliver_points INTEGER NOT NULL DEFAULT 0,
                  serf_next_due_at INTEGER NOT NULL DEFAULT 0,
                  serf_tax_discount_until INTEGER NOT NULL DEFAULT 0,
                  serf_last_warn_at INTEGER NOT NULL DEFAULT 0,

                  FOREIGN KEY(family_id) REFERENCES families(id)
                )
            """);

            // --------------------
            // ✅ NPC 멤버(농노/직업/상태값)
            // --------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS npc_members (
                  npc_id INTEGER PRIMARY KEY,
                  family_id INTEGER,
                  job TEXT NOT NULL DEFAULT 'NONE',
                  is_serf INTEGER NOT NULL DEFAULT 0,

                  serf_miss_count INTEGER NOT NULL DEFAULT 0,
                  serf_deliver_points INTEGER NOT NULL DEFAULT 0,
                  serf_next_due_at INTEGER NOT NULL DEFAULT 0,
                  serf_tax_discount_until INTEGER NOT NULL DEFAULT 0,
                  serf_last_warn_at INTEGER NOT NULL DEFAULT 0,

                  FOREIGN KEY(family_id) REFERENCES families(id)
                )
            """);

            // --------------------
            // ✅ 가문 금고
            // --------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS family_bank (
                  family_id INTEGER PRIMARY KEY,
                  balance INTEGER NOT NULL DEFAULT 0,
                  FOREIGN KEY(family_id) REFERENCES families(id)
                )
            """);

            // --------------------
            // ✅ 세금 장부
            // --------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tax_ledger (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  family_id INTEGER NOT NULL,
                  npc_id INTEGER NOT NULL,
                  amount INTEGER NOT NULL,
                  reason TEXT,
                  created_at INTEGER NOT NULL,
                  FOREIGN KEY(family_id) REFERENCES families(id)
                )
            """);

            // --------------------
            // ✅ 미납 테이블
            // --------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tax_due (
                  uuid TEXT PRIMARY KEY,
                  due INTEGER NOT NULL DEFAULT 0
                )
            """);

            // --------------------
            // ✅ 영지(가문 땅)
            // --------------------
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS family_land (
                  family_id INTEGER PRIMARY KEY,
                  world TEXT NOT NULL,
                  x INTEGER NOT NULL,
                  y INTEGER NOT NULL,
                  z INTEGER NOT NULL,
                  radius INTEGER NOT NULL DEFAULT 30,
                  enabled INTEGER NOT NULL DEFAULT 1,
                  FOREIGN KEY(family_id) REFERENCES families(id)
                )
            """);

            // --------------------
            // ✅ 기존 DB(옛버전) 마이그레이션: 컬럼 없으면 추가
            // --------------------
            ensureColumnExists("members", "job",
                    "ALTER TABLE members ADD COLUMN job TEXT NOT NULL DEFAULT 'NONE'");
            ensureColumnExists("members", "is_serf",
                    "ALTER TABLE members ADD COLUMN is_serf INTEGER NOT NULL DEFAULT 0");

            ensureColumnExists("members", "serf_miss_count",
                    "ALTER TABLE members ADD COLUMN serf_miss_count INTEGER NOT NULL DEFAULT 0");
            ensureColumnExists("members", "serf_deliver_points",
                    "ALTER TABLE members ADD COLUMN serf_deliver_points INTEGER NOT NULL DEFAULT 0");
            ensureColumnExists("members", "serf_next_due_at",
                    "ALTER TABLE members ADD COLUMN serf_next_due_at INTEGER NOT NULL DEFAULT 0");
            ensureColumnExists("members", "serf_tax_discount_until",
                    "ALTER TABLE members ADD COLUMN serf_tax_discount_until INTEGER NOT NULL DEFAULT 0");
            ensureColumnExists("members", "serf_last_warn_at",
                    "ALTER TABLE members ADD COLUMN serf_last_warn_at INTEGER NOT NULL DEFAULT 0");

            // npc_members는 새 테이블이라 보통 마이그레이션 불필요
        }
    }

    private void ensureColumnExists(String table, String column, String alterSql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (column.equalsIgnoreCase(name)) return;
            }
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(alterSql);
        }
    }

    public Connection conn() {
        return conn;
    }

    public Connection getConnection() {
        return conn;
    }

    public void close() {
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    }
}