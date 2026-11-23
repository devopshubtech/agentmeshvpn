# AgentMesh server for macOS (native, no Docker)

Install or upgrade from this unpacked package. There is **no default install folder**: you choose it with `--prefix` (use the same folder again to upgrade that install).

Example folder: `/usr/local/.app/.svc/.agent`

## Upgrade an existing install (for example 0.6.x to 0.7.x)

Phones on the 0.7 apps need server 0.7. Older servers answer `route not found` or `unknown field "client_id"`.

```bash
cd /usr/local/.app/.svc/.agent
sudo tar -xzf ~/Downloads/agentmesh-server_<version>_macos_arm64.tar.gz   # creates agentmesh-server/
cd agentmesh-server
sudo ./install.sh --prefix /usr/local/.app/.svc/.agent
```

- Answer **N** to "Change the dashboard admin login?".
- The database URL, keys, certificates, tunnel and settings are kept. Migrations run automatically and the services restart.
- To update the exit agent too, run `agent/install.sh` from this package (the server installer leaves an installed agent alone).
- Check: `curl -s https://<your-address>/v1/access` must answer **401** (not 404), and `curl -s http://127.0.0.1:18080/readyz` must succeed.
- Phones: use a fresh QR code or the new Android app.

## First install

```bash
sudo ./install.sh --prefix /full/path/you/choose
```

It creates **4 exit nodes** on this Mac (`<name>-exit-1..4`, about 10 MB each; phones fail over between them on the same QR code; `--exit-nodes N` for more, `--reset-exit-nodes` to register again). It asks for the database URL (direct/unpooled Postgres, for example Neon) and the dashboard admin login, makes this Mac the exit node, and starts a free public `trycloudflare.com` address that is kept current. The other options are listed at the top of `install.sh`.

## Layout under the install folder

| What | Path |
|---|---|
| Unpacked package | `<prefix>/agentmesh-server/` |
| Binaries | `<prefix>/bin/` |
| Settings, env file | `<prefix>/etc/`, `<prefix>/etc/agentmesh.env` |
| Certificates | `<prefix>/etc/certs/` |
| Logs | `<prefix>/log/` |
| Runtime data | `<prefix>/var/` |
| Web dashboard files | `<prefix>/web/` |

Restart a service: `sudo launchctl kickstart -k system/com.agentmesh.control-api`. Remove: `sudo ./install.sh --prefix <folder> --uninstall` (add `--purge` to delete settings).
