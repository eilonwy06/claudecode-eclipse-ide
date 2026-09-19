## What changed and why

## How was this tested?

## Checklist

- [ ] I have **not** changed version numbers in `MANIFEST.MF`, `feature.xml`, or `site.xml` — the maintainer sets these on release.
- [ ] I have **not** added a `CHANGELOG.md` entry — the maintainer adds this on release.
- [ ] I have **not** hand-edited the generated p2 artifacts under `com.anthropic.claudecode.eclipse.site/`.
- [ ] If this touches `claude-eclipse-core`: I ran `cargo test`, and for any platform I can build and run on, rebuilt the native library and exercised it in a running Eclipse instance.
- [ ] If this would need a native library for a platform I can't build and test myself, I've left that out and said so above rather than including an untested binary.
