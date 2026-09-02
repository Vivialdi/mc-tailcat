# mc-tailcat

A Minecraft mod that puts a server and its players on the same
[tailcat](https://github.com/tailscale/tailcat) tunnel, so people can join
without port forwarding, a public IP, or any kind of account.

The server publishes its tailcat address when it starts. Clients read that
address, bring up their own end of the tunnel, and add the server to the
multiplayer list automatically. After the one-time setup, a player just launches
the game and clicks the server.

## Why it works on almost any Minecraft version

The mod never touches a Minecraft class. It has no mixins, no Fabric API
dependency, and no version-specific code anywhere — it compiles against Fabric
Loader alone. Everything it does goes through two things that have been stable
for the whole modern era of the game:

- `server.properties` and `servers.dat`, read and written directly.
- A loopback TCP listener. Minecraft connects to `127.0.0.1`, and the mod
  bridges that connection through tailcat to the real server. The game has no
  idea anything unusual is happening.

That means **one jar runs on every Minecraft version Fabric Loader supports**,
1.17 and up, with no per-version builds to maintain. There is no
`minecraft` entry in `fabric.mod.json`'s dependencies at all, so the loader
never rejects it for a version mismatch.

## Requirements

- Fabric Loader 0.14 or newer, on Minecraft 1.17+ (Java 17 is the real floor).
- `tailcat`. If it isn't installed, the mod downloads a copy into the game
  directory and verifies it against the release's published SHA-256. tailcat
  needs no root and no account.

## Setting up a server

On Windows, [`setup-server.ps1`](setup-server.ps1) does the whole thing from
nothing — it finds or installs a JDK, fetches the Fabric server for the version
you name, drops the mod in, checks the [`localhost`
prerequisite](#windows-servers-localhost-has-to-resolve), and starts it:

```powershell
powershell -ExecutionPolicy Bypass -File setup-server.ps1 -AcceptEula -GameVersion 1.21.1
```

`-AcceptEula` records that you accept the [Minecraft
EULA](https://aka.ms/MinecraftEULA); without it the script stops and leaves
`eula.txt` for you to edit yourself. `-NoStart` sets everything up without
launching. Otherwise, by hand:

1. Put the jar in the server's `mods/` folder and start the server.
2. On first start it writes `config/tailcat-server.json`, brings up tailcat, and
   prints a banner:

   ```
   =========================== Tailcat ===========================
    This server is reachable over Tailcat. No port forwarding
    needed -- give players either of the following.

    Address: xxxxxxxxxxxxxxxxxxxxxxxxxxx
    File:    /srv/minecraft/tailcat-network.json
   ===============================================================
   ```

3. Give players either the address or the file. That's the whole handoff.

The file is the better half of that pair. Everything a client needs is in it —
address, port, display name, and any client-side flags you locked in — so a
player drops it into their `config/` folder, or a modpack ships it there, and
they never open a config file at all. See [Handing the server to a
group](#handing-the-server-to-a-group).

The address is stable across restarts. Two things are needed for that, and the
mod does both: it asks tailcat for a *saved* key (named `minecraft` by default)
rather than an ephemeral one, and it generates that key with `--fixed-region`.
The second part matters more than it looks — a tailcat address encodes the DERP
relay region, and without a fixed one tailcat re-picks the region by latency at
every startup, changing the published address and stranding players who already
saved it. A saved key on its own is not enough.

### `config/tailcat-server.json`

| Key | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Turn the mod off without removing it. |
| `serverName` | `""` | Name players see. Empty derives one from the MOTD. |
| `tailcatPath` | `""` | Use a specific tailcat executable. Empty means find or download one. |
| `downloadTailcat` | `true` | Allow downloading tailcat if it isn't installed. |
| `keyName` | `"minecraft"` | Saved tailcat key. Change it to retire an address that has spread too far. |
| `fixedRegion` | `true` | Bake a fixed relay region into the key. This is what keeps the address stable; see above. Applies when the key is created, so change `keyName` too. |
| `fullAddress` | `false` | Publish a longer, self-contained address with relay details embedded. |
| `port` | `0` | Port to expose. `0` reads `server-port` from `server.properties`. |
| `publishPath` | `""` | Where to write the network file. Empty means `<game dir>/tailcat-network.json`. |
| `isolateState` | `true` | Keep tailcat's saved keys inside the server directory instead of the host user's home. |
| `tailcatArgs` | `[]` | Extra flags for every tailcat call, e.g. `"--derpmap-url=..."` to use your own relay, or `"--allow=nodekey:..."` to restrict clients. |
| `clientTailcatArgs` | `[]` | Flags written into the published file for *players* to use. Set this to your `"--derpmap-url=..."` if you run your own relay — without it clients cannot reach you at all. |
| `clientServerListSuffix` | `""` | Suffix players see after this server's name in their multiplayer list, e.g. `" [SMP]"`. Empty leaves it to each player. |

Those last two are the "lock it in" settings: they are copied into
`tailcat-network.json` so the file alone is enough to connect. They are
deliberately *not* derived from `tailcatArgs`, because most of what belongs
there is server-only — `--allow=nodekey:...` above all, which you do not want
published to everyone holding the file.

> If you set `server-ip` in `server.properties` to a single external address,
> the server won't be listening on `127.0.0.1` and tailcat connections will be
> refused. Leave `server-ip` empty. The mod warns about this at startup.

### Windows servers: `localhost` has to resolve

If you run the server on Windows, check that your hosts file has this line:

```
127.0.0.1 localhost
```

On a default Windows install it is **commented out**, because Windows answers
`localhost` inside its DNS Client service rather than from a file. tailcat is
built with Go's own resolver (`netgo`), which never asks that service — it goes
straight to your configured DNS server. A public resolver like `1.1.1.1` or
`8.8.8.8` has no record for `localhost`, so tailcat cannot resolve the address
it proxies connections to, and **every player is refused with nothing in the
log to explain it**.

The symptom is a server that looks perfectly healthy — banner printed, address
published, tailcat running — while clients see only `connection reset by peer`.
The real cause appears once, in the server's own console:

```
error proxying to localhost:25565: dial tcp: lookup localhost on 1.1.1.1:53: no such host
```

The mod watches for that line and prints the fix. Adding the hosts entry
(`C:\Windows\System32\drivers\etc\hosts`, needs administrator rights) and
restarting the server resolves it. Linux and macOS define `localhost` in
`/etc/hosts` as a matter of course, so this only bites on Windows.

## Setting up a client

**The short version: put the jar in `mods/`, put the server's
`tailcat-network.json` in `config/`, start the game.** The server is in the
multiplayer list, ready to click. There is no config file to edit.

That works because the client checks a few standard places on every launch,
with nothing configured:

| Where | For |
| --- | --- |
| `config/tailcat-network.json` | The file the operator sent you, or the one a modpack ships. |
| `config/tailcat-servers/*.json` | A pack that ships more than one server. |
| `<game dir>/tailcat-network.json` | A client and server sharing one machine. |

Servers found this way are **not** copied into `tailcat-client.json`. The
published file stays the source of truth, so when the operator rotates their
key and ships an updated pack, the existing multiplayer entry is repointed
rather than joined by a dead one. Set `autoDiscover` to `false` to turn this
off.

### Handing the server to a group

For a modpack, put the file the server published at `config/tailcat-network.json`
inside the pack, next to the mod jar in `mods/`. Players install the pack and
are done — no address to paste, no instructions to follow, and if you locked in
a relay or a display name on the server, those come along with it.

For a group that is not using a pack, send them the file and tell them to drop
it in `config/`. Same result.

### The manual routes

Both still work, and are the right choice for a player adding one server to a
setup they otherwise control.

**Paste the address:**

```json
{
  "servers": [
    {
      "name": "Survival",
      "address": "tcEXAMPLEaddressForDocsAndTestsOnly_NotARealServer00000000",
      "port": 25565,
      "enabled": true
    }
  ]
}
```

**Or point at the server's file**, which is picked up fresh on every launch —
useful when the client and server share a machine or a synced folder, and the
right choice for a modpack you hand to a group of players:

```json
{
  "importFrom": [
    "/srv/minecraft/tailcat-network.json",
    "https://example.com/tailcat-network.json"
  ]
}
```

Restart the game. The server appears in the multiplayer list as
`Survival (Tailcat)`, pointing at a loopback address. Click it and play.

### `config/tailcat-client.json`

| Key | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Turn the mod off without removing it. |
| `tailcatPath` | `""` | Use a specific tailcat executable. |
| `downloadTailcat` | `true` | Allow downloading tailcat if it isn't installed. |
| `isolateState` | `true` | Keep tailcat's state inside the game directory. |
| `tailcatArgs` | `[]` | Extra flags for every tailcat call, e.g. `"--derpmap-url=..."` for a self-hosted relay. |
| `addToServerList` | `true` | Manage entries in `servers.dat`. |
| `serverListSuffix` | `" (Tailcat)"` | Appended to each name in the multiplayer list. A server that locked in its own suffix overrides this. |
| `autoDiscover` | `true` | Pick up any `tailcat-network.json` sitting in the standard places above. This is what makes a modpack work with no setup. |
| `importFrom` | `[]` | Files, directories, or URLs to read server details from on each launch. |
| `servers` | `[]` | Servers to connect to. |

## How a connection actually flows

```
Minecraft client
  -> 127.0.0.1:3xxxx            (local listener owned by the mod)
    -> tailcat <address> <port>  (encrypted tunnel, NAT traversal, DERP relay)
      -> 127.0.0.1:25565         (tailcat serve, on the server host)
        -> Minecraft server
```

The local port is derived from a hash of the server's address, so it is the
same every launch. That matters: the port is written into `servers.dat`, and a
port that moved between launches would leave a dead entry in the player's list.
If the port is genuinely taken by something else, the mod picks another before
writing the entry.

Startup order is deliberate. Choosing each port, **binding** it, and writing the
multiplayer list all happen synchronously while the mod initialises, which is
long before the game reaches its main menu. Only finding the tailcat binary is
left to the background, because on a first launch that means a download.

So the entry a player sees is never ahead of the listener behind it. If they
reach the multiplayer screen while that download is still running, the
connection waits for it rather than being refused — which on a first launch is
the difference between joining and having to back out and try again. If the
binary can't be had at all, the listener is closed instead, so the server
honestly reads as down rather than accepting a connection it can never carry.

## A note on sharing the address

A tailcat address is an invitation. Anyone who has it can reach the Minecraft
port it points at, without an account or a password. Share it the way you'd
share a server IP and a whitelist slot, keep using the game's own whitelist and
allowlist for who may actually play, and generate a new key (change `keyName`)
if an address gets out further than you meant.

## Building

Needs JDK 17 or newer. There's no pinned toolchain, so any modern JDK works.

```sh
./gradlew build          # jar lands in fabric/build/libs/
./gradlew :core:test     # the test suite, no network needed
```

The project is two modules:

- **`core`** — all the logic, in plain Java with no dependencies at all: tailcat
  process management, the loopback forwarder, NBT, config, downloads. Fully unit
  tested without a game.
- **`fabric`** — two small entrypoint classes. Nothing else.

That split is also why porting to NeoForge would be a short job: it's a new
adapter over the same core, not a rewrite.

## What has been verified

The core logic is covered by 101 tests, and the mod's assumptions about the
tailcat CLI were checked against a real tailcat v0.4.0 build: the `genkey`
and `serve` flag shapes, that flags must precede positional arguments, that
the startup banner goes to stderr, that client mode keeps stdout free of
anything but tunnel data, and that redirecting `XDG_CONFIG_HOME`/`HOME`/
`APPDATA` puts saved keys where the mod expects them.

The handoff itself has been run end to end outside the game, against real
files: an operator publishing with locked-in settings, a pack shipping the
result into `config/`, and a client with no configuration discovering it,
writing the right `servers.dat` entry, and leaving its own config untouched —
including the rotated-key case, where the existing entry is repointed rather
than duplicated. The first-launch ordering was checked the same way: the
loopback port accepts a connection with zero wait after the mod initialises,
holds one that arrives before tailcat is ready, and closes if tailcat turns out
to be unavailable.

**The whole thing has now been run in a live game.** A player joined a real
dedicated server through the tunnel, over a real DERP relay, having done
nothing but drop the published `tailcat-network.json` into `config/`. The
server logged them arriving from `[::1]` — tailcat handing the connection over
loopback — and they spawned in the world.

Specifically confirmed by that run:

- The same jar loaded on **Minecraft 1.20.1 and 1.21.1**, under Fabric Loader
  0.16.2 and 0.19.3, with no version-specific build. This is the claim the
  whole design rests on.
- The server downloaded tailcat on first start, verified it against the
  published SHA-256, created its saved key, and published the file.
- The address was **unchanged across a full server restart**, which is what
  `fixedRegion` plus a saved key is for.
- The supervisor restarted tailcat by itself after the process was killed, with
  backoff, and republished the same address.
- The client found the shipped file with no configuration, wrote the right
  `servers.dat` entry before the tunnel existed, and held a connection open
  while it downloaded its own copy of tailcat — the first-launch case, arriving
  unprompted in a real launch.
- It coexisted with unrelated client mods (Sodium, Iris) without interacting
  with them.

Still not exercised: a direct peer-to-peer path rather than a relayed one, and
anything beyond a short session — no long-running stability or throughput
testing.

## Caveats

- tailcat is new and makes no CLI stability promises. If a future release
  changes its flags or output, this mod may need updating; `tailcatPath` lets
  you pin a known-good binary in the meantime.
- tailcat 0.4.0 proxies to the hostname `localhost` rather than to `127.0.0.1`,
  which makes a Windows server dependent on that name resolving. See
  [Windows servers](#windows-servers-localhost-has-to-resolve). Nothing the mod
  passes on the command line can avoid it — `serve` takes ports, not addresses —
  so the mod detects the failure and prints the fix instead.
- Traffic that can't establish a direct path falls back to a public DERP relay,
  which has no uptime guarantee and will add latency.
- The mod manages entries in `servers.dat`. It matches on name or address and
  leaves every other entry — and every field it doesn't understand — untouched,
  but it is writing to a file the game also owns.

## License

MIT. See [LICENSE](LICENSE).
