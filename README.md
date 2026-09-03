# SkyWorlds (Paper only)

Production plugin for **A Zombie Pigman Broke My Door**.

Fly above Y 1320 holding dragon's breath (3s linger) to enter local Paper `mirror_overworld` / `mirror_nether`. The End stays on Paper.

- **2.2.3** — ignore non-`NIGHT_SKIP` time skips so copying time to the mirror cannot strobe the ground clock.
- **2.2.4** — also reset `minecraft:movement_speed` to 0.1 when it is 0 (PlayerDataSync used to skip applying 0 and leave a frozen player.dat).

The Fabric/Velocity hybrid lives in [SkyWorlds-Fabric](https://github.com/wilderop/SkyWorlds-Fabric).

## Build

```bash
mvn clean package
install-plugin-jar target/skyworlds-2.2.4.jar /mnt/pool/survival/plugins/SkyWorlds-2.2.jar
```

A jar on disk is for the next JVM start. Do not `cp` over a live plugin jar.
