
-- This script should be compatible with PostgreSQL versions 11.18 and later.
-- The core function of this script is to create and setup the required WebCTRL tables in your database.

-- Schema to contain all relevant tables
CREATE SCHEMA webctrl;

-- Ensure plpgsql extension is installed
CREATE EXTENSION IF NOT EXISTS plpgsql;

-- Ensure pgcrypto extension is installed
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA webctrl;
ALTER EXTENSION pgcrypto SET SCHEMA webctrl;

-- Table that records one row for each connected server
CREATE TABLE webctrl.servers (
  -- Unique id to identify each server
  "id" SERIAL PRIMARY KEY,
  -- Display name of the root of the geographic tree
  "name" TEXT,
  -- Version string for WebCTRL (e.g, 8.0.002.20201210-107996)
  "version" TEXT,
  -- Version string for the PostgreSQL_Connect addon installed on this server
  "addon_version" TEXT,
  -- The IP address of the server as defined by inet_client_addr()
  "ip_address" INET,
  -- Timestamp which records the time of the latest synchronization
  "last_sync" TIMESTAMPTZ,
  -- The product name specified in the WebCTRL license file
  "product_name" TEXT
);

-- Table that records one row for each operator on each server
CREATE TABLE webctrl.operators (
  -- Corresponds to the id column of webctrl.servers
  "server_id" INTEGER NOT NULL,
  -- Login name of the operator
  "username" TEXT,
  -- Display name of the operator
  "display_name" TEXT
);
CREATE INDEX webctrl_operators_id ON webctrl.operators ("server_id" ASC);

-- Table that records one row for each event that occurs on each server
CREATE TABLE webctrl.events (
  -- Corresponds to the id column of webctrl.servers
  "server_id" INTEGER NOT NULL,
  -- Specifies the type of event that occurred
  "event" TEXT,
  -- Specifies a timestamp for the event
  "time" TIMESTAMPTZ
);
CREATE INDEX webctrl_events_id ON webctrl.events ("server_id" ASC);
CREATE INDEX webctrl_events_time ON webctrl.events ("time" DESC);

-- Table that records one row for each log message on each server
CREATE TABLE webctrl.log (
  -- Corresponds to the id column of webctrl.servers
  "server_id" INTEGER NOT NULL,
  -- Specifies a timestamp for the message
  "time" TIMESTAMPTZ,
  -- Specifies whether or not the message is an error stack trace
  "error" BOOLEAN,
  -- The logged message
  "message" TEXT
);
CREATE INDEX webctrl_log_id ON webctrl.log ("server_id" ASC);
CREATE INDEX webctrl_log_time ON webctrl.log ("time" DESC);

-- Table that records one row for each addon on each server
CREATE TABLE webctrl.addons (
  -- Corresponds to the id column of webctrl.servers
  "server_id" INTEGER NOT NULL,
  -- Reference name of the addon
  "name" TEXT,
  -- Display name of the addon
  "display_name" TEXT,
  -- Description of the addon
  "description" TEXT,
  -- Vendor of the addon
  "vendor" TEXT,
  -- Version of the addon
  "version" TEXT,
  -- Current status of the addon (e.g, RUNNING or DISABLED)
  "state" TEXT
);
CREATE INDEX webctrl_addons_id ON webctrl.addons ("server_id" ASC);

-- Operators in this table are synchronized to each connected server
CREATE TABLE webctrl.operator_whitelist (
  -- Username of the operator
  "username" TEXT PRIMARY KEY,
  -- Display name of the operator
  "display_name" TEXT,
  -- Salted Sha512 password hash for the operator
  -- Refer to the function defined below: webctrl_password_hash
  "password" TEXT,
  -- Number of seconds to wait before automatically logging off the operator
  -- 0 disables automatic logoff
  -- Negative numbers specify to use the system-wide timeout (usually 30 minutes)
  "lvl5_auto_logout" INTEGER,
  -- Whether to automatically collapse tree nodes when navigating WebCTRL
  "lvl5_auto_collapse" BOOLEAN
);

-- Operators in this table are deleted from each connected server
CREATE TABLE webctrl.operator_blacklist (
  -- Username of the operator
  "username" TEXT PRIMARY KEY
);

-- If a particular client login conflicts with the blacklist, add a row to this table
CREATE TABLE webctrl.operator_blacklist_exceptions (
  -- Corresponds to the id column of webctrl.servers
  "server_id" INTEGER NOT NULL,
  -- Username of the operator
  "username" TEXT
);

-- Create a function which computes a password hash compatible with WebCTRL (salted Sha512)
CREATE OR REPLACE FUNCTION webctrl.webctrl_password_hash("password" TEXT) RETURNS TEXT AS $$
  DECLARE
    "salt" BYTEA := gen_random_bytes(8);
  BEGIN
    RETURN REGEXP_REPLACE('{SSHA512}' || encode(digest(convert_to("password", 'UTF8') || "salt", 'sha512') || "salt", 'base64'), '[\n\r]+', '', 'g');
  END;
$$ LANGUAGE plpgsql;

-- Create a function to validate a given password against the stored hash value
CREATE OR REPLACE FUNCTION webctrl.webctrl_password_validate("password" TEXT, "hash" TEXT) RETURNS BOOLEAN AS $$
  DECLARE
    "data" BYTEA := decode(right("hash", -9), 'base64');
    "len" INTEGER := length("data");
    "salt" BYTEA := substr("data", "len"-7);
    "hash_" BYTEA := substr("data", 1, "len"-8);
  BEGIN
    RETURN digest(convert_to("password", 'UTF8') || "salt", 'sha512')="hash_";
  END;
$$ LANGUAGE plpgsql;

-- Create a function to ensure all passwords are hashed
CREATE OR REPLACE FUNCTION webctrl.webctrl_password_hash_trigger() RETURNS TRIGGER AS $$
  BEGIN
    IF NEW."password" IS NOT NULL AND NEW."password" <> '' AND NEW."password" NOT LIKE '{SSHA512}%' THEN
      NEW."password" := webctrl_password_hash(NEW."password");
    END IF;
    RETURN NEW;
  END;
$$ LANGUAGE plpgsql;

-- Ensure all passwords are hashed when operator_whitelist is modified
CREATE TRIGGER trigger_webctrl_password_hash_trigger
BEFORE INSERT OR UPDATE ON webctrl.operator_whitelist
FOR EACH ROW EXECUTE FUNCTION webctrl.webctrl_password_hash_trigger();

-- Addons in this table are synchronized to each connected server
CREATE TABLE webctrl.addon_whitelist (
  -- Reference name of the addon
  "name" TEXT NOT NULL,
  -- Version of the addon
  "version" TEXT,
  -- Whether to keep newer versions of the addon when they already exist
  "keep_newer" BOOLEAN,
  -- Download path in the FTP server
  "download_path" TEXT,
  -- Minimum WebCTRL version required for this addon
  "min_webctrl_version" TEXT,
  -- Maximum WebCTRL version required for this addon
  "max_webctrl_version" TEXT,
  -- Whether to remove addon data during synchronization
  "clear_data" BOOLEAN
);

-- Addons in this table are deleted from each connected server
CREATE TABLE webctrl.addon_blacklist (
  -- Reference name of the addon
  "name" TEXT NOT NULL,
  -- The addon will only be deleted from servers whose verison is at least this value
  "min_webctrl_version" TEXT,
  -- The addon will only be deleted from servers whose verison is at most this value
  "max_webctrl_version" TEXT
);

-- Defines trend sources to collect data from
CREATE TABLE webctrl.trend_mappings (
  -- Unique id to identify each trend mapping
  "id" SERIAL PRIMARY KEY,
  -- Corresponds to the id column of webctrl.servers
  "server_id" INTEGER NOT NULL,
  -- User-friendly name to identity the trend mapping
  "name" TEXT,
  -- com.controlj.green.addonsupport.access.Location.getPersistentLookupString(true)
  "persistent_identifier" TEXT,
  -- How many days of historical data should be kept in the database
  "retain_data" INTEGER,
  -- Whether to collect field data from controllers
  "field_access" BOOLEAN
);

-- Where collected trend source data lives
-- For data points, exactly one of the value columns will be non-null
-- For holes, all value columns will be null, and time corresponds to the starting hole timestamp.
-- The ending hole timestamp can be assumed to be the time of the next populated sample.
-- If a populated data sample and a hole have the same timestamp, assume the hole occurs after the data sample.
CREATE TABLE webctrl.trend_data (
  -- Corresponds to the id column of webctrl.trend_mappings
  "id" INTEGER NOT NULL,
  -- When the trend data occurred
  "time" TIMESTAMPTZ,
  -- Used for digital trends
  "booleanValue" BOOLEAN,
  -- Used for equipment color trends
  -- Stores an encoded RGB value
  "intValue" INTEGER,
  -- Used for analog trends
  "doubleValue" DOUBLE PRECISION
);
CREATE INDEX webctrl_trend_data_id ON webctrl.trend_data ("id" ASC);
CREATE INDEX webctrl_trend_data_time ON webctrl.trend_data ("time" DESC);

-- Create a function that deletes data for non-existent servers
CREATE OR REPLACE FUNCTION webctrl.webctrl_clean() RETURNS TRIGGER AS $$
  BEGIN
    WITH "bad" AS (
      SELECT "a"."server_id" FROM (
        SELECT DISTINCT "server_id" FROM webctrl.operators
      ) "a" LEFT JOIN webctrl.servers "b"
      ON "a"."server_id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.operators "a" USING "bad" "b"
    WHERE "a"."server_id" = "b"."server_id";
    WITH "bad" AS (
      SELECT "a"."server_id" FROM (
        SELECT DISTINCT "server_id" FROM webctrl.events
      ) "a" LEFT JOIN webctrl.servers "b"
      ON "a"."server_id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.events "a" USING "bad" "b"
    WHERE "a"."server_id" = "b"."server_id";
    WITH "bad" AS (
      SELECT "a"."server_id" FROM (
        SELECT DISTINCT "server_id" FROM webctrl.log
      ) "a" LEFT JOIN webctrl.servers "b"
      ON "a"."server_id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.log "a" USING "bad" "b"
    WHERE "a"."server_id" = "b"."server_id";
    WITH "bad" AS (
      SELECT "a"."server_id" FROM (
        SELECT DISTINCT "server_id" FROM webctrl.operator_blacklist_exceptions
      ) "a" LEFT JOIN webctrl.servers "b"
      ON "a"."server_id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.operator_blacklist_exceptions "a" USING "bad" "b"
    WHERE "a"."server_id" = "b"."server_id";
    WITH "bad" AS (
      SELECT "a"."server_id" FROM (
        SELECT DISTINCT "server_id" FROM webctrl.addons
      ) "a" LEFT JOIN webctrl.servers "b"
      ON "a"."server_id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.addons "a" USING "bad" "b"
    WHERE "a"."server_id" = "b"."server_id";
    WITH "bad" AS (
      SELECT "a"."server_id" FROM (
        SELECT DISTINCT "server_id" FROM webctrl.trend_mappings
      ) "a" LEFT JOIN webctrl.servers "b"
      ON "a"."server_id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.trend_mappings "a" USING "bad" "b"
    WHERE "a"."server_id" = "b"."server_id";
    WITH "bad" AS (
      SELECT "a"."id" FROM (
        SELECT DISTINCT "id" FROM webctrl.trend_data
      ) "a" LEFT JOIN webctrl.trend_mappings "b"
      ON "a"."id" = "b"."id" WHERE "b"."id" IS NULL
    ) DELETE FROM webctrl.trend_data "a" USING "bad" "b"
    WHERE "a"."id" = "b"."id";
  END;
$$ LANGUAGE plpgsql;

-- Create a function that deletes expired trend data
CREATE OR REPLACE FUNCTION webctrl.webctrl_delete_expired_trends() RETURNS TRIGGER AS $$
  BEGIN
    WITH "thresh" AS (
      SELECT
        "id",
        CURRENT_TIMESTAMP-make_interval(days=>"retain_data") AS "start"
      FROM webctrl.trend_mappings
    ) DELETE FROM webctrl.trend_data "a" USING "thresh" "b"
    WHERE "a"."id" = "b"."id" AND "a"."time" < "b"."start";
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;

-- Invoke the webctrl_clean() function whenever a server is created or deleted
CREATE TRIGGER trigger_clean_webctrl
AFTER INSERT OR DELETE ON webctrl.servers
FOR EACH STATEMENT EXECUTE FUNCTION webctrl.webctrl_clean();

-- Invoke the webctrl_delete_expired_trends() function whenever a trend mapping is created or deleted
CREATE TRIGGER trigger_clean_webctrl_trends
AFTER INSERT OR DELETE ON webctrl.trend_mappings
FOR EACH STATEMENT EXECUTE FUNCTION webctrl.webctrl_delete_expired_trends();

-- Stores various settings
CREATE TABLE webctrl.settings (
  "name" TEXT PRIMARY KEY,
  "value" TEXT
);

-- Populate default values for webctrl.settings
INSERT INTO webctrl.settings VALUES
  -- Version of PostgreSQL connector addon
  ('version','0.4.5'),
  -- Whether debug mode is enabled (e.g, verbose log messages when true)
  ('debug','false'),
  -- Whether to auto-update the PostgreSQL connector addon
  ('auto_update','false'),
  -- Download path in the FTP server for the latest PostgreSQL connector addon
  ('download_path',NULL),
  -- Host name for the FTP server
  ('ftp_host',NULL),
  -- Port for the FTP server
  ('ftp_port',NULL),
  -- Username for the FTP server
  ('ftp_username',NULL),
  -- Password for the FTP server
  ('ftp_password',NULL),
  -- Known hosts raw file contents for host key verification (e.g, output of ssh-keyscan command)
  ('ftp_known_hosts',NULL);

/* If you have a PostgreSQL role with username webctrl, the following queries will grant webctrl all the required permissions
GRANT USAGE ON SCHEMA webctrl TO webctrl;
GRANT ALL ON webctrl.servers TO webctrl;
GRANT ALL ON webctrl.servers_id_seq TO webctrl;
GRANT ALL ON webctrl.operators TO webctrl;
GRANT ALL ON webctrl.events TO webctrl;
GRANT ALL ON webctrl.log TO webctrl;
GRANT ALL ON webctrl.addons TO webctrl;
GRANT ALL ON webctrl.operator_whitelist TO webctrl;
GRANT ALL ON webctrl.operator_blacklist TO webctrl;
GRANT ALL ON webctrl.operator_blacklist_exceptions TO webctrl;
GRANT ALL ON webctrl.addon_whitelist TO webctrl;
GRANT ALL ON webctrl.addon_blacklist TO webctrl;
GRANT ALL ON webctrl.settings TO webctrl;
GRANT ALL ON webctrl.trend_mappings TO webctrl;
GRANT ALL ON webctrl.trend_mappings_id_seq TO webctrl;
GRANT ALL ON webctrl.trend_data TO webctrl;
--*/

-- Use this query to drop all webctrl tables
-- DROP SCHEMA webctrl CASCADE;

/* Use these queries to reset the server data tables
DELETE FROM webctrl.servers;
ALTER SEQUENCE webctrl.servers_id_seq RESTART;
DELETE FROM webctrl.addons;
DELETE FROM webctrl.events;
DELETE FROM webctrl.log;
DELETE FROM webctrl.operator_blacklist_exceptions;
DELETE FROM webctrl.trend_mappings;
ALTER SEQUENCE webctrl.trend_mappings_id_seq RESTART;
DELETE FROM webctrl.trend_data;
--*/

-- Insert administrators from your WebCTRL server into webctrl.operator_whitelist
INSERT INTO webctrl.operator_whitelist VALUES