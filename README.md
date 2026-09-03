# SkyWorlds (Paper only)

Production plugin for **A Zombie Pigman Broke My Door**.

Fly above Y 1320 holding dragon's breath (3s linger) or throw an ender pearl through the ceiling to enter local Paper `mirror_overworld` / `mirror_nether`. The End stays on Paper. Time and weather copy from the ground overworld; 2.2.3 ignores non-`NIGHT_SKIP` time skips so that copy cannot strobe the ground clock.

This repo is the **working Paper-only line**. The Fabric/Velocity hybrid lives in [SkyWorlds-Fabric](https://github.com/wilderop/SkyWorlds-Fabric) and must not replace this plugin on survival until that path is proven.

## Build

```bash
mvn clean package
install-plugin-jar target/skyworlds-2.2.3.jar /mnt/pool/survival/plugins/SkyWorlds-2.2.jar
```

A jar on disk is for the next JVM start. Do not `cp` over a live plugin jar.
