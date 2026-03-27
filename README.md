# Claude Code for Eclipse IDE

An Eclipse IDE plugin that integrates [Claude Code](https://claude.ai/code) — Anthropic's AI-powered CLI — directly into your Eclipse development environment.

## Installation

1. Open Eclipse and go to **Help → Install New Software**
2. Click **Add** and enter:
   - Name: `Claude Code`
   - URL: `https://eilonwy06.github.io/claudecode-eclipse-ide/`
3. Select the **Claude Code for Eclipse IDE** feature and follow the install prompts
4. Restart Eclipse when prompted

## Project Structure

| Project | Description |
|---|---|
| `com.anthropic.claudecode.eclipse.feature` | Eclipse feature definition — declares the plugin and its metadata |
| `com.anthropic.claudecode.eclipse.site` | p2 update site — the installable artifacts hosted via GitHub Pages |

## Updating the Plugin

After making changes and rebuilding the update site in Eclipse:

```bash
git add .
git commit -m "vX.X.X - description of changes"
git push
```

GitHub Pages will redeploy within ~1 minute and the new version will be available to install.

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

## License

[MIT](LICENSE)
