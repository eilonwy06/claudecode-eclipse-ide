# Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what
you would like to change.

This repository also hosts the update site, so nobody pushes to it directly — a change
reaches users through a pull request, and then through a release the maintainer publishes.

1. **Fork** the repository on GitHub and clone your fork.
2. **Create a branch** for your change.
3. **Import the projects** into Eclipse: *File ▸ Import ▸ Existing Projects into Workspace*,
   pointing at the clone.
4. **Make your change.** If it touches Rust, run `cargo test` in `claude-eclipse-core` and
   rebuild the native library for your own platform (see
   [Building the Native Library](README.md#building-the-native-library)) so you can run what you wrote.
5. **Try it** — *Run As ▸ Eclipse Application* launches a second Eclipse with the plugin
   installed. Confirm the behaviour there before sending anything.
6. **Push to your fork** and open a pull request against `master`, describing what changed
   and how you tested it. CI builds and tests `claude-eclipse-core` on Linux, macOS and
   Windows, and reviews the dependency diff for known vulnerabilities.

Please leave these to the maintainer, and keep them out of your pull request:

- the version numbers in `MANIFEST.MF`, `feature.xml` and `site.xml`
- the `CHANGELOG.md` entry
- the generated p2 artifacts under `com.anthropic.claudecode.eclipse.site/`
- native libraries for platforms you cannot build and test on yourself

Once a release is published, GitHub Pages redeploys the update site within about a minute
and the new version becomes available to install.

Found a security issue instead of a regular bug? Please see [SECURITY.md](SECURITY.md)
rather than opening a public pull request or issue.
