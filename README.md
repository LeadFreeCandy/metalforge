# MetalForge

**A native Apple Metal renderer for Minecraft on NeoForge, with smooth frame pacing.** Drop it into a NeoForge 26.1.2 instance on an Apple Silicon Mac and Minecraft renders through Metal instead of Apple's deprecated OpenGL-on-Metal layer. It works alongside Sodium, and includes a set of exact (pixel-identical) client optimizations that also apply on Windows and Linux.

- **Loader:** NeoForge 26.1.2.109 or newer (Minecraft 26.1.2). Client-only: don't install it on servers.
- **Metal renderer:** Apple Silicon Macs (M1 or newer), macOS 14+. On other platforms MetalForge stays on OpenGL and only applies the CPU/GPU optimizations.
- **Sodium:** optional and recommended. With Sodium installed, Sodium's terrain renderer runs on Metal too.
- **Java:** 25 (what Minecraft 26.1.2 ships with).

The Metal backend is a NeoForge port of [mcopt](https://github.com/noahdunnagan/mcopt) by Noah Dunnagan (Fabric, Apache-2.0), extended to work with Sodium and large modpacks.

## Results

Measured on a fanless MacBook Air M2 (24 GB), FTB Evolution 2 (about 420 mods). Settings were render distance 20 and simulation distance 8, a 1920x1080 window on a 100 Hz display, and 12 GB heap with ZGC. Averages over four ground-level spots after terrain finished loading.

| | OpenGL + Sodium | MetalForge (uncapped) |
|---|---|---|
| Standing still, looking at terrain | 124–151 fps | 287–294 fps |
| Turning in place | 97–118 fps | 189–225 fps |

High fps alone doesn't look smooth. Uncapped, frames take 2–4 ms but every client tick (20/s) produces a 10–20 ms frame. The display then shows unevenly spaced frames: on that 100 Hz screen, **25% of displayed frames** repeated the previous image or skipped ahead, measured from the actual on-screen present times. With **VSync on, MetalForge's smooth pacing** brings that to **about 2%** at a steady refresh rate (98 fps on 100 Hz). See [Smooth pacing](#smooth-pacing).

Your numbers will differ with hardware, pack and settings.

## Install

1. Install NeoForge 26.1.2 (CurseForge, Prism, Modrinth App or the official installer all work).
2. Put `metalforge-26.1.2-<version>.jar` in the instance's `mods` folder.
3. **Turn VSync on** in Video Settings for smooth pacing (recommended).
4. On a Mac: if **Iris** is installed, MetalForge keeps OpenGL (Iris only works with OpenGL), so remove Iris to use Metal. Shader packs need Iris and OpenGL.

You can tell Metal is active when F3 lists the renderer as `Metal 3 (MetalForge/mcopt)`, and the log says `MetalForge Metal backend (ported from mcopt) on Apple M…`.

For large modpacks, give the game enough memory. 8 GB was too little for a 420-mod pack: the garbage collector stalled for up to 1 s while chunks loaded. 10–12 GB fixed it. On Java 25 use `-XX:+UseZGC`; `-XX:+ZGenerational` is obsolete and only prints a warning.

## What it does

### Metal renderer (Apple Silicon)

- Implements Minecraft's renderer interface (Blaze3D's GPU backend) on Metal, and is tried before OpenGL. Shaders go from GLSL to SPIR-V (shaderc) to Metal Shading Language (SPIRV-Cross) when they load.
- From mcopt:
  - merged render passes and deferred clears, so fewer tile loads and stores on Apple's tile-based GPUs
  - the main depth buffer is discarded at frame end instead of written to memory
  - display-synced present pacing, and presenting from a second command queue so the render thread never waits for a drawable
- Sodium on Metal: Sodium's chunk renderer, multidraw and command lists run on Metal buffers.
- Uploads that replace a whole buffer get fresh memory instead of a GPU copy. This removes most per-frame encoder breaks from immediate-mode drawing.

### Smooth pacing

With VSync on, MetalForge doesn't block on the display:
- It starts frames on a fixed beat of one display refresh (or a multiple of it if you set a frame limit).
- It schedules each frame for a specific refresh, using `presentDrawable:atTime:`.
- After a slow frame (a client tick, chunk work), the next frames start immediately and catch up into their scheduled slots instead of skipping a refresh.

The result is evenly spaced motion at your display's refresh rate, lower GPU load, and less heat on fanless Macs. With VSync off you get uncapped frame rates instead.

### Exact optimizations (all platforms)

Each one leaves the rendered image unchanged and can be switched off individually.

| Option | What it does |
|---|---|
| `outline` | Skips the full-screen entity-outline clear and blend while nothing is glowing (the blend of an empty target is a no-op). |
| `prelude` | Drops the main-target clear that the level's own clear immediately overwrites, plus a render-target round trip on tick frames. |
| `fbo` | (OpenGL) Fewer framebuffer switches: depth-only clears happen in the framebuffer already holding that depth, and no redundant unbinds. |
| `gui_layer` | Vanilla places each GUI element by testing it against every element already on screen (quadratic). A small spatial index gives the same answer. This is about 2% of frame time with busy HUDs such as minimaps. |
| `gui_sort` | Sorts GUI elements with an unboxed comparator in exactly vanilla's order. |
| `particle_collide` | Particles in open air skip the full collision computation when none of the blocks they could touch has a collision shape. |
| `alpha_const` | (Sodium) The alpha cutoff is constant per pass. It's baked into the shader, removing a per-vertex varying and per-fragment unpacking. |
| `lean_sampling` | (Sodium) With NEAREST filtering (Sodium's default), uses cheaper but identical texel and LOD math in the terrain shader. |
| `cutout_sort` | (Sodium) Sorts cutout quads (leaves, grass) front-to-back within each facing at mesh time, so the GPU rejects more hidden fragments. |
| `toro_light` | (ToroHealth) Looks up entity light only for entities that actually get an in-world health bar. |
| `lootr_far` | (Lootr) Skips Lootr's per-tick client particle logic for containers beyond 32 blocks, where vanilla discards any particle it would spawn. |

Integrations marked with a mod name only apply when that mod is installed.

## Configuration (JVM arguments)

| Argument | Effect |
|---|---|
| `-Dmetalforge.metal=false` | Use OpenGL even on Apple Silicon. |
| `-Dmetalforge.opts=gui_sort=off,lootr_far=off` | Turn individual optimizations off (`all=off` turns them all off). |
| `-Dmetalforge.metal.smooth=on\|off\|vsync` | Smooth pacing always, never, or with VSync (default `vsync`). |
| `-Dmetalforge.metal.smoothMaxLead=3` | Most frames of lead smooth pacing may use (latency vs. tolerance of slow frames). |
| `-Dmetalforge.metal.presentQueue=false` | Present on the render thread instead of the second queue. |
| `-Dmetalforge.metal.pace=false` | With VSync off, present every frame instead of the last one per refresh. |

In CurseForge these go in the instance's *Profile Options → Java arguments*.

## Compatibility

- **Iris / shader packs:** with Iris installed, MetalForge uses OpenGL (and its other optimizations).
- **Mods that call OpenGL directly** draw nothing on Metal. Mods that render through Minecraft's own renderer (nearly all modern ones) work. MetalForge was tested with the whole FTB Evolution 2 pack, about 420 mods, including Sodium, ImmediatelyFast, EntityCulling, FTB Chunks and JEI. If something is invisible, check it with `-Dmetalforge.metal=false` and please open an issue.
- **Intel Macs:** not supported by the Metal renderer (it's arm64-only); the other optimizations still apply.

## Building

Build on macOS with the Xcode Command Line Tools (`xcode-select --install`), since the native library is compiled with clang:

```
./gradlew build
```

The jar lands in `build/libs/`. Sodium and Lootr are fetched from Modrinth's Maven at build time for compiling the integrations; they are not bundled.

## Credits and license

- Metal backend ported from **[mcopt](https://github.com/noahdunnagan/mcopt)** by Noah Dunnagan (Apache-2.0).
- The jar embeds LWJGL's shaderc and SPIRV-Cross bindings (BSD-3-Clause; the libraries themselves are Apache-2.0).
- MetalForge is licensed under the **Apache License 2.0**: see [LICENSE](LICENSE) and [NOTICE](NOTICE).
