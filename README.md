# SkyWorlds (Paper only + optional Fabric test gate)

Production plugin for **A Zombie Pigman Broke My Door**.

- **Dragon's Breath** fly-up (3s) or **ender pearl** through Y 1320 → local Paper `mirror_overworld` / `mirror_nether`.
- **Barrier** in hand + fly-up from the **ground** overworld/nether → Fabric **test** sky (builds may not persist).
- Redis down → classic Paper sky still works; barrier says the test sky is offline.
- `/server fabric` stays blocked (Velocity allow-token only).

## Versions

- **2.2.3** — time-sync strobe fix
- **2.2.4** — heal `minecraft:movement_speed` when it is 0
- **2.2.5** — optional barrier → Fabric test gate (shaded Jedis). Does **not** unload Paper mirrors.

Hybrid Fabric/Velocity code: [SkyWorlds-Fabric](https://github.com/wilderop/SkyWorlds-Fabric). Source on host: `/mnt/pool/projects/SkyWorlds-Paper` and `/mnt/pool/projects/SkyWorld`.

## Deploy

```bash
mvn clean package
install-plugin-jar target/skyworlds-2.2.5.jar /mnt/pool/survival/plugins/SkyWorlds-2.2.jar
```

Takes effect on the next survival JVM start. Also needs Velocity `SkyWorlds-Velocity.jar` bounced to load the allow-token plugin. Do not `cp` over a live plugin jar.
