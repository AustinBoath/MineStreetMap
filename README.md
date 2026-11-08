# OSM Export (Fabric, MC 26.2, no Fabric API)

Build: `gradle wrapper` (or copy the wrapper from the Fabric example mod), then `./gradlew build` -> build/libs/osmexport-1.0.0.jar.
Needs JDK 25. Check gradle.properties versions at https://fabricmc.net/develop.

Commands (all also reachable via clickable chat buttons from `/osm`):
- /osm coast [radius]      scan loaded chunks around you (default 16 chunks). Repeat in other areas; data accumulates.
- /osm line start <name>   begin recording your path (a point every >=1 block)
- /osm line end | cancel
- /osm origin <lat> <lon>  lat/lon of x=0,z=0 (default 0,0)
- /osm export [file]       writes <game dir>/osm_exports/<file>.osm
- /osm status | clear

1 block = 1 meter. Coast = natural=coastline (ocean biome + water on the surface). Rivers = river-biome water as
natural=water + water=river areas. Lines carry only a name tag. Anyone can run the commands (no permission check).

## No PC? Build on GitHub
Upload this folder to a new GitHub repo (keep the .github folder). Actions runs automatically; download the
"osmexport-jar" artifact (use the jar without -sources) and put it in your mods folder with Fabric Loader.
If the build fails, copy the error log and send it to Claude to fix.
