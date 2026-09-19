# Security Policy

## Reporting a vulnerability

Please **do not** open a public GitHub issue for a security vulnerability.

Instead, use GitHub's private reporting flow for this repository:
[Security → Report a vulnerability](https://github.com/eilonwy06/claudecode-eclipse-ide/security/advisories/new).
That opens a private advisory visible only to the maintainer and you, with
its own discussion thread, so a fix and a disclosure timeline can be worked
out before any details are public.

If you can't use that flow for some reason, reach the maintainer through
their GitHub profile instead.

## Scope

This repository builds the plugin and also hosts, via GitHub Pages, the p2
update site that Eclipse's *Install New Software* uses to install and update
it. A report doesn't have to be a bug in the plugin's own code to be in
scope — a way to get unreviewed or unintended content into that update site,
or into the native library shipped for any platform, is just as serious and
just as welcome.

## Supported versions

Only the latest published release is supported. If you're reporting a bug
rather than a vulnerability, please confirm it reproduces on the latest
release first.
