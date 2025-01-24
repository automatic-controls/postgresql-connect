# PostgreSQL_Connect

WebCTRL is a trademark of Automated Logic Corporation.  Any other trademarks mentioned herein are the property of their respective owners.

- [PostgreSQL\_Connect](#postgresql_connect)
  - [Feature Summary](#feature-summary)
  - [Installation](#installation)
  - [Configuration](#configuration)
    - [PostgreSQL Database](#postgresql-database)
      - [DNS](#dns)
      - [Encryption](#encryption)
      - [Client Authentication](#client-authentication)
      - [Schema Setup](#schema-setup)
      - [Add-On Connection](#add-on-connection)
    - [SFTP Server](#sftp-server)
    - [Shared Settings](#shared-settings)
  - [Synchronizaton](#synchronizaton)
    - [Server List](#server-list)
    - [Add-On Whitelist](#add-on-whitelist)
    - [Add-On Blacklist](#add-on-blacklist)
    - [Operator Whitelist](#operator-whitelist)
    - [View Local Operators](#view-local-operators)
    - [Reverse Operator Sync](#reverse-operator-sync)
    - [Operator Blacklist](#operator-blacklist)
    - [Operator Blacklist Exceptions](#operator-blacklist-exceptions)
    - [Pending Commands](#pending-commands)
      - [Examples](#examples)
    - [Find Trends](#find-trends)
    - [Trend Mappings](#trend-mappings)
  - [Technical Information](#technical-information)
    - [Packaged Dependencies](#packaged-dependencies)
    - [Server ID Reset](#server-id-reset)

## Feature Summary

When this WebCTRL add-on is installed and configured, it periodically communicates to an external PostgreSQL database for purposes described below. This add-on is compatible with WebCTRL 8.0 and all later versions. The intention is that this add-on should be installed on multiple WebCTRL servers, and that every server should be pointing to the same external PostgreSQL database. When configured in this manner, the following features are supported:

- Automatically gather the following data points from connected servers:
  - WebCTRL version
  - WebCTRL license
  - Uptime / downtime
  - Installed cumulative updates
  - Installed add-ons
  - Operator list
  - External-facing IP address
- Operator synchronization:
  - Synchronize a subnet of whitelisted operators between all connected servers
  - Create a whitelisted operator in the database, and it gets pushed out everywhere
  - Modify your password on one server, and the change propogates to all other servers
  - Blacklist operators to delete them everywhere
  - Local operators (those that are not white or blacklisted) remain unaltered
  - WebCTRL's operator authentication provider is unaffected
  - For security, operator passwords hashed and salted using the same algorithm WebCTRL uses
- Add-on synchronization:
  - Synchronize a subnet of whitelisted add-ons between all connected servers
  - Deploy a new add-on everywhere in a single motion
  - When a new version of an add-on is released, update the database, and it gets pushed everywhere
  - Blacklist add-ons to uninstall them everywhere
  - Add-ons that are not white or blacklisted remain unaltered
- Manage the PostgreSQL database from the add-on's main page on any connected server
  - Only whitelisted operators are permitted to manage the database
- Remotely push out commands for servers to execute during their next sync
  - Among other things, this function can be used for automated bulk installation of WebCTRL patches
- Import or export a JSON document containing local operators
- Optionally collect data from specific WebCTRL trends

## Installation

1. If signed add-ons are required, copy the authenticating certificate [*ACES.cer*](https://github.com/automatic-controls/addon-dev-script/blob/main/ACES.cer?raw=true) to the *./programdata/addons* directory of your *WebCTRL* installation folder.
2. Install [*PostgreSQL_Connect.addon*](https://github.com/automatic-controls/postgresql-connect/releases/latest/download/PostgreSQL_Connect.addon) using the *WebCTRL* interface.
3. Navigate to the add-on's main page and configure the necessary connection details as described in the next section.

## Configuration

### PostgreSQL Database

The full details of how to configure a PostgreSQL database server are beyond the scope of this documentation, but we will mention a few of the necessary steps.

#### DNS

The database should be publicly accessible from a domain (e.g, *postgresql.domain.com:5432*). One way to do this is to add an **A** record to a DNS server that points to the public static IPv4 address of your PostgreSQL database (see [AWS Route 53](https://aws.amazon.com/route53/)). Optionally, you may want to configure a firewall to restrict inbound connections by IP address.

#### Encryption

The database should be configured to encrypt connections with an SSL certificate. Refer to the [official documentation](https://www.postgresql.org/docs/current/ssl-tcp.html#SSL-SETUP). I suggest writing a script using [Let's Encrypt](https://letsencrypt.org/about/) with [Certbot](https://certbot.eff.org/pages/about) to automate certificate renewal. [*renew.sh*](./resources/renew.sh) is an example renewal script.

- Enable SSL encryption in *postgresql.conf*

```conf
ssl = on
# path to PEM encoded X509v3 certificate file
ssl_cert_file = '/data/ssl/ssl_cert'
# path to PEM encoded X509v3 private key file
ssl_key_file = '/data/ssl/ssl_key'
```

You should download the PEM encoded X509v3 certificate corresponding to the CA your SSL certificate has been signed with. For [Let's Encrypt](https://letsencrypt.org/certificates/), this probably means [ISRG Root X1](https://letsencrypt.org/certs/isrgrootx1.pem). You should upload this to connected WebCTRL servers on the add-on's main page with the *Upload Root Certificate* button.

![](./resources/upload_root_cert.png)

#### Client Authentication

Clients should authenticate themselves to the PostgreSQL server using a signed certificate. Refer to the [official documentation](https://www.postgresql.org/docs/current/ssl-tcp.html#SSL-CLIENT-CERTIFICATES).

- Enable client authentication in *postgresql.conf*

```conf
# path to PEM encoded X509v3 CA certificate used to authenticate clients
ssl_ca_file = '/data/ssl/ssl_ca'
```

- Enable encryption and client authentication in *pg_hba.conf*

```
hostssl all all 0.0.0.0/0 scram-sha-256 clientcert=verify-full
```

Client certificates can be uploaded to connected WebCTRL servers on the add-on's main page with the *Upload Client Keystore* button. The uploaded JKS keystore should have exactly one alias, `user`. This alias should contain the private key and certificate used to authenticate the client to the PostgreSQL server. The keystore password can be entered in the add-on's main page with the *Client Keystore Password* field.

![](./resources/upload_client_key.png)

#### Schema Setup

You should create a PostgreSQL role that the add-on will use to login to the database.

```sql
CREATE USER webctrl WITH PASSWORD 'myNewPassword';
```

Click the *Download Setup SQL* button on the add-on's main page to generate a script you'll need to setup the database. Read through the script completely so that you understand the database's structure. After you are satisfied with your understanding of the script, run it on your server.

![](./resources/download_sql.png)

#### Add-On Connection

Now you are ready to test the add-on's connection to your PostgreSQL server.

![](./resources/postgresql_config.png)

Fill out all the required fields, click *Save Changes* and then click *Sync Now*. Be sure to specify the correct database name in the connection URL. After waiting a minute, go to the log viewer page and verify that the sync was successful. I suggest using `0 0 * * * *` for the cron expression and `900000` milliseconds for the random maximum offset. This cron expression specifies to start a sync at the beginning of each hour. The random maximum offset specifies to start the sync randomly at some time during the first 15 minutes of the hour. This randomization is important when there are many add-on instances trying to connect to the PostgreSQL database. It ensures the database is not flooded with too many requests all at once.

When the add-on connects to the PostgreSQL database for the first time, it generates a unique server ID for itself. The *Reset ID* button can be used to delete the saved ID and generate a new one.

### SFTP Server

Whitelisted add-ons must reside on an SFTP server. [JSch](https://github.com/mwiede/jsch) is a third-party library this add-on uses to establish SFTP connections. If you want to lock down an SSH daemon to serve SFTP only, refer to the following example snippet from *sshd_config*.

```
Match group sftp
ChrootDirectory /data/sftp
X11Forwarding no
AllowAgentForwarding no
AllowTcpForwarding no
ForceCommand internal-sftp
```

You'll need to configure [public-key authentication](https://www.digitalocean.com/community/tutorials/how-to-configure-ssh-key-based-authentication-on-a-linux-server) for your SFTP user. Go to the settings page in the add-on and set `ftp_key` to the full private key your user needs for authentication. Set `ftp_host` to the hostname or IP address of the SFTP server. Also configure `ftp_port` and `ftp_username` appropriately. The [`ssh-keyscan`](https://man.openbsd.org/ssh-keyscan.1) command can be used to retrieve the data required to populate the `ftp_known_hosts` field.

![](./resources/sftp_config.png)

Once you've set these variables, press the *Sync Now* button on the main page, wait a minute, and press the *Test SFTP* button. You should see a status message, *SFTP Connection Successful*, if everything is setup properly.

![](./resources/test_sftp.png)

### Shared Settings

In addition to the SFTP connection settings shown in the previous section, there are a few other shared settings to be mindful of. All settings are described below.

| Setting | Example | Description |
| - | - | - |
| `debug` | `false` | When enabled, log messages will be more verbose. |
| `log_expiration` | `60` | Specifies how many days to retain log messages in the database. |
| `auto_update` | `true` | Specifies whether to attempt automatic updates for this add-on. |
| `version` | `0.5.10` | When `auto_update` is enabled, any connected client whose add-on version is less than this value will be updated. |
| `download_path` | `/webctrl/addons/PostgreSQL_Connect.addon` | When `auto_update` is enabled, this is the SFTP server path where the latest version add-on file will be retrieved. |
| `license_directory` | `/webctrl/licenses` | Specifies an SFTP server directory path for where to store WebCTRL license files. |
| `ftp_host` | `postgresql.domain.com` | SFTP server hostname or IP address. |
| `ftp_port` | `22` | SFTP server port. |
| `ftp_username` | `webctrl` | SFTP server username to use for connections. |
| `ftp_known_hosts` | `postgresql.domain.com ecdsa-sha2-nistp256 ...` | Public key of the SFTP server to verify the server's identity. [Documentation](https://help.salesforce.com/s/articleView?id=001120703&type=1) |
| `ftp_key` | `-----BEGIN OPENSSH PRIVATE KEY----- ...` | Private key which authenticates the client to the SFTP server. |
| `ftp_port_secondary` | `443` | Secondary SFTP port which may be utilized by some servers. This may be useful to bypass certain firewalls. |
| `ftp_port_secondary_ids` | `46;51;60;61;62` | Semi-colon delimited list of server IDs which should use the secondary SFTP port. |

When trying to push out an update for the add-on, you should do things in the following order:

1. Overwrite the *PostgreSQL_Connect.addon* file in your SFTP server to be the desired version.
2. Change the `version` setting to match that of the new add-on file.
3. Ensure `auto_update` is enabled and `download_path` is correct.
4. Wait for clients to update. They will attempt to update at the next sync interval. You are welcome to manually trigger a sync on one or two servers if you want to test the update before the regular sync interval occurs.

You cannot use this mechanism to automatically downgrade the add-on to an earlier version.

## Synchronizaton

Now that your PostgreSQL database and SFTP server is configured, this section explains how to setup synchronization of operators, add-ons, and trends. We will go through each page accessible from the web UI.

### Server List

This page lists all connected servers. If a server is decomissioned or permanently disconnected for any reason, the server can be deleted from the database using this page.

| Column | Example | Description |
| - | - | - |
| ID | `1` | Internal ID which uniquely identifies the server within the PostgreSQL database. (Read-only) |
| Name | `ACES Main Building` | User-friendly display name for the server. This defaults to the display name of the root of the Geo tree. |
| WebCTRL Version | `8.5.002.20230323-123687` | Full version of the WebCTRL server. (Read-only) |
| Add-On Version | `0.5.10` | Installed version of the PostgreSQL_Connect add-on. (Read-only) |
| IP Address | `123.45.67.89` | External IP address of the server as viewed by the PostgreSQL database. (Read-only) |
| Last Sync | `2024-12-02 14:05:32` | Timestamp of the last successful synchronization. If synced within the last 24 hours, the background color is green; otherwise, the background is red. (Read-only) |
| License | `WebCTRL Premium` | Click this field to download WebCTRL's license. (Read-only) |
| Cumulative Update | `20240212` | Latest applied cumulative update. (Read-only) |
| Notes | `*` | You can put any notes you want here. |

### Add-On Whitelist

After uploading the appropriate *.addon* files to the SFTP server, you can create a list of add-ons that should be installed everywhere.

| Column | Example | Description |
| - | - | - |
| Name | `Weather` | Display name for the add-on. |
| Version | `3.0.4` | Version of the add-on stored on the SFTP server. Please strip non-numeric characters out of the version string before inserting it here. For example, 'v0.1.0-beta' should turn into '0.1.0'. |
| Install | `N/A` | When applicable, a button will be shown allowing you to manually trigger installation of optional add-ons. |
| Keep Newer | `true` | Whether to allow newer versions of the add-on to be installed. If 'false', then newer versions will be downgraded. |
| Download Path | `/webctrl/addons/weather.addon` | SFTP server path used to download the add-on. |
| Min Version | `8.5` | If the WebCTRL version is less than this value, the add-on will not be installed. If left blank, this restriction is ignored. |
| Max Version | `9.0` | If the WebCTRL version is greater than this value, the add-on will not be installed. If left blank, this restriction is ignored. |
| Clear Data | `false` | Whether to delete data upon add-on removal (relevant for version upgrades). |
| Optional | `false` | If true, existing add-on installions will be upgraded to the version stored here, but the add-on will not be installed on servers that do not already have it. |
| Description | `*` | Descriptive details for this add-on. |

You can push out an updated add-on by placing the new *.addon* file in the SFTP server, updating the version column in the add-on whitelist, and waiting for connected servers to sync. You should not create a whitelist entry for *PostgreSQL_Connect.addon* because self-updates require special handling. For that functionality, you should take a look at [Shared Settings](#shared-settings).

### Add-On Blacklist

During each sync, any blasklisted add-ons currently installed are removed.

| Column | Example | Description |
| - | - | - |
| Name | `operatortraining7_0` | Reference name to uniquely identity the add-on. |
| Min. Version | `8.0` | If the WebCTRL version is less than this value, the add-on will not be removed. If left blank, this restriction is ignored. |
| Max. Version | `9.0` | If the WebCTRL version is greater than this value, the add-on will not be removed. If left blank, this restriction is ignored. |
| Clear Data | `true` | Whether to delete data upon add-on removal. |

### Operator Whitelist

During each sync, the add-on enforces that these whitelisted operators are available.

| Column | Example | Description |
| - | - | - |
| Username | `cvogt` | Username to uniquely identify this user. |
| Display Name | `Cameron Vogt - ACES` | User-friendly display name. |
| Password Hash | `{SSHA512}8cPPJX8...` | A password hash for the user. You can enter a plaintext password into this field, and it will be automatically hashed when you submit the form. |
| Session Timeout | `-1` | Specifies how many seconds to wait before automatically logging this user out. 0 disables automatic logoff. -1 uses the system default. |
| Auto-Collapse Trees | `true` | This pertains to the geographic and network trees. If true, previously expanded nodes will collapse when you try to expand an unrelated node. If false, you can have as many nodes expanded at the same time as you like. |

### View Local Operators

The table structure of this page is identical to that of the operator whitelist except that all columns are read-only. Only non-whitelisted operators are displayed here. Hence the modifier *local* in 'local operators' as opposed to *global*. Local operators can be exported into a JSON document that can be imported into other WebCTRL servers. The import-export function allows admins to quickly copy operators from one server to another without having to globally whitelist anyone. Beware that only the information present in the displayed columns can be imported and exported. For example, information related to permissions or location-dependent security is ignored.

### Reverse Operator Sync

When whitelisted operators changes their passwords (or other attributes) locally on the WebCTRL server, the changes are pushed back to the database during the next sync interval. It can take up to two full sync cycles for the password change to propogate to other connected server. This process can be sped up by clicking the *Sync Operator Data* item in WebCTRL's main menu in the upper-right corner (then propogation will only take one sync cycle).

### Operator Blacklist

During each sync, blacklisted operators are deleted if any exist.

| Column | Example | Description |
| - | - | - |
| Username | `aces` | The username of the operator to be blacklisted. |
| Exceptions | `1` | Counts the number of WebCTRL servers which have an exception allowing an operator with this username to exist. (Read-only) |

### Operator Blacklist Exceptions

Exceptions entered into this table through the add-on's configuration pages affect only the current WebCTRL server. To create an exception on multiple servers, you should log into each server individually and enter a new exception in each. Or you can edit the database directly without relying upon the web interface.

| Column | Example | Description |
| - | - | - |
| Username | `aces` | Specifies a blacklisted username which should be allowed to exist on this WebCTRL server. |

### Pending Commands

Commands entered into this table are executed on servers during their next sync interval.

| Column | Example | Description |
| - | - | - |
| Command ID | `4` | Unique identifier for this command. (Read-only) |
| Server ID | `2` | Unique identifier for the WebCTRL server. You can retrieve the current server's ID from the add-on's main page. |
| Server Name | `ACES Main` | User-friendly name of the WebCTRL server. (Read-only) |
| Ordering | `3` | When there are multiple command entries for a single server, this column specifies the ascending order in which commands are executed. |
| Command | `notify "Hello!"` | The command(s) to execute. Multiple commands can be separated by newlines for fail-fast semantics. |

Commands chained together using new-lines in a single entry are fail-fast, which means that execution is terminated immediately when an error is encountered. However, errors in one command entry do not affect other entries. Generally, commands are case-insensitive. Commands are tokenized using whitespace as delimiters. Double quotes can be used if a token must include whitespace. The caret character `^` can be used as an escape character. The local file path are specified, paths starting with `/` or `\` are treated as relative to WebCTRL's installation directory, and paths starting with `./` or `.\` are treated as relative to WebCTRL's active system directory. The two dots in `a/../b` go to the parent folder of `a`, so that `b` would be a sibling folder of `a`. Environment variables enclosed in percent signs (e.g, `%USERNAME%`) are expanded when present in local paths. Single-line comments are supported when a line is starts with `//`. The following commands are supported.

| Command | Description |
| - | - |
| `duplicate [id1,id2,...]` | When a new pending command is created, and `duplicate` is on the first line, the command is copied to all servers with the specified IDs. If no server IDs are specified, then the command is copied to all servers. You can use `%ID%` or `%NAME%` anywhere in the command after the `duplicate` statement, and it will be replaced with the server ID or name of each server the command is copied to. |
| `about` | Logs a bunch of information relevant to the WebCTRL server. This functions similarly to the `about` manual command. |
| `log <message>` | Writes a message to the add-on's log file. |
| `notify <message>` | Functions identically to the `notify` manual command. Logged in operators get a popup message in their web browsers. |
| `email <recipients> <subject> <message>` | Sends a email using WebCTRL's primary email server configuration. Recipient addresses should be semi-colon delimited. |
| `sleep <milliseconds>` | Sleeps for the specified number of milliseconds. |
| `reboot` | Functions identically to the `rebootserver` manual command. The WebCTRL server reboots. |
| `uptime` | Logs a message saying how long the WebCTRL server has been online. |
| `set <key> <value>` | Sets a parameter to the specified value. Most of the parameters can be changed on the add-on's main page on each server, but this command allows remote editing in bulk. The following keys are supported: `connectionURL`, `username`, `password`, `keystorePassword`, `maxRandomOffset`, `cronSync`, `RAM`. The `RAM` key changes the amount of memory allocated to WebCTRL in MB and goes into affect at the next reboot. |
| `decrypt <value>` | Logs a message containing the decrypted value. This function is only meant to work on encrypted WebCTRL database passwords found in *db.properties* in the system folder. |
| `mkdir <folder_path>` | Creates a new directory on the local file system of the WebCTRL server. |
| `rmdir <folder_path>` | Deletes a directory and contents on the local file system of the WebCTRL server. |
| `rm <file_path>` | Deletes a file on the local file system of the WebCTRL server. |
| `cp <src_path> <dst_path>` | Copies a file on the local file system of the WebCTRL server. |
| `mv <src_path> <dst_path>` | Moves a file or directory on the local file system of the WebCTRL server. Try using `cp` and `rmdir` to move a folder if you encounter errors with this command; this command generally only works when the source and destination are on the same drive. |
| `cat <file_path>` | Logs the contents of the specified file. |
| `exists <path>` | Asserts that the specified file or directory exists. If non-existent, then command execution is terminated. |
| `!exists <path>` | Asserts that the specified file or directory does not exists. If it exists, then command execution is terminated. |
| `regex <file_path> <find> [replace]` | If a replacement string is not given, then this commands logs all matches of the regular expression in the specified file. If a replacement string is given, then this command edits the specified file by replacing all matches of the regular expression. The file's contents are assumed to be UTF-8 encoded text. The [MULTILINE](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html#MULTILINE) and [DOTALL](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html#DOTALL) flags are used by default. |
| `download <sftp_file_path> <local_file_path>` | Downloads a file from the SFTP server into the local file system of the WebCTRL server. |
| `upload <local_file_path> <sftp_file_path>` | Uploads a file from the local file system of the WebCTRL server into the SFTP server. |
| `canApplyUpdate <file_path>` | Asserts that WebCTRL is able to apply the specified *.update* patch file. If the update cannot be applied, then command execution is terminated. |
| `!canApplyUpdate <file_path>` | Asserts that WebCTRL is not able to apply the specified *.update* patch file. If the update can be applied, then command execution is terminated. |
| `updateDST` | Updates daylight savings dates stored in the WebCTRL database and marks controllers for a pending parameter download. |

#### Examples

Here are a few sample scripts that can be modified to install WebCTRL patches in bulk.

```
duplicate 9,42,43,36,55,48,54,6,52,45,32,23,14,5,59,10,41,50,17,4
download "/webctrl/updates/8.5/WS85_Security_Issue_Fix.update" "/WS85_Security_Issue_Fix.update"
canApplyUpdate "/WS85_Security_Issue_Fix.update"
log "WS85_Security_Issue_Fix.update can be applied!"
rm "/WS85_Security_Issue_Fix.update"

duplicate 9,42,43,36,55,48,54,6,52,45,32,23,14,5,59,10,41,50,17,4
exists "/WS85_Security_Issue_Fix.update"
log "WS85_Security_Issue_Fix.update cannot be applied!"
rm "/WS85_Security_Issue_Fix.update"

duplicate 14,50,42,55,36,32
mkdir "/update_on_restart"
download "/webctrl/updates/8.5/WS85_Security_Issue_Fix.update" "/update_on_restart/WS85_Security_Issue_Fix.update"
canApplyUpdate "/update_on_restart/WS85_Security_Issue_Fix.update"
notify "Rebooting to install security update."
log "Rebooting to install security update."
reboot

duplicate 14,50,42,55,36,32
exists "/update_on_restart"
log "Update did not install."
rmdir "/update_on_restart"
```

### Find Trends

This webpage helps you to find the persistent identifier for trends in order to track them in the database. See the next section for more details on how to use this identifier. You can browse the Geographic tree to search for trends. When you find a trend you want to track, click the turquoise colored trend type in order to copy the persistent identifier.

![](./resources/trend_browser.png)

### Trend Mappings

You can view all trend mappings from every WebCTRL server using this webpage.

| Column | Example | Description |
| - | - | - |
| Trend ID | `5` | Unique identifier for this trend mapping. (Read-only) |
| Server ID | `2` | Unique identifier for the WebCTRL server. You can retrieve the current server's ID from the add-on's main page. |
| Server Name | `ACES Main` | User-friendly name of the WebCTRL server. (Read-only) |
| Name | `Boiler Temp` | User-friendly name to identity the trend mapping. |
| Persistent Identifier | `DBID:1:2176071:hws` | Unique identifier for the microblock value to be trended. Use the 'Find Trends' page to retrieve this. |
| Retain Data | `60` | Specifies how many days of historical data should be kept in the database. |
| Field Access | `false` | Whether to use field access when gathering trend data. If field access is disabled, then data collection will be faster, but it may not include the most up-to-date samples available. |
| Sample Count | `3972` | Number of samples stored in the database. (Read-only) |
| First Sample | `2024-11-19 14:10:00` | Timestamp of the oldest sample. (Read-only) |
| Last Sample | `2024-12-03 09:05:00` | Timestamp of the most recent sample. (Read-only) |

It is up to you how you use trend data after it is pulled into the database. A snippet from the setup SQL file is shown below. This snippet should make it obvious for how to access the trend data. You will need to have a working knowledge of PostgreSQL.

```sql
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
```

## Technical Information

### Packaged Dependencies

- [PostgreSQL JDBC 42.7.5](https://jdbc.postgresql.org/) - Used to connect to PostgreSQL databases.
- [JSch 0.2.22](https://github.com/mwiede/jsch) - Used to connect to SFTP servers.
- [JSON-java 20250107](https://github.com/stleary/JSON-java) - Used to encode and decode JSON data.

### Server ID Reset

There is a *Reset ID* button on the add-on's main page. This will make the add-on forget its current server ID and re-register with the database at the start of the next sync. This server ID is the primary mechanism for how the database separates out data corresponding to different WebCTRL servers. If you ever need to force the add-on to choose a particular ID, you can craft and submit a particular HTTP request to the WebCTRL server. For example, *https://localhost/PostgreSQL_Connect/index?type=resetNow&newID=3* will force the ID of the WebCTRL server accessible at *localhost* to 3.