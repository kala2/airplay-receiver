# Contributing

Thanks for helping! Bug reports, device test results and pull requests are welcome.

## Getting set up
Follow [docs/BUILDING.md](docs/BUILDING.md), then enable the commit-message hook once:
```bash
git config core.hooksPath scripts/git-hooks
```

## Commit messages — Conventional Commits (required)
Releases and version numbers are computed from commit messages, so every commit must follow
[Conventional Commits](https://www.conventionalcommits.org) (checked locally by the hook and in CI):

| Type | Use for | Release |
|---|---|---|
| `fix:` | a bug fix | patch (1.2.**3**) |
| `feat:` | a new feature | minor (1.**3**.0) |
| `feat!:` / `fix!:` or a `BREAKING CHANGE:` footer | incompatible change | major (**2**.0.0) |
| `perf:` `refactor:` `docs:` `test:` `build:` `ci:` `chore:` `style:` `revert:` | everything else | none on its own |

Examples: `fix(audio): stop crackle after pause`, `feat: add 1440p quality option`,
`docs: explain CEC setup`. Optional scope in parentheses: `video`, `audio`, `discovery`, `ui`,
`native`, `build`, `ci`, …

## Pull requests
1. Branch from `main`; keep changes focused.
2. Run before pushing: `scripts/test-native.sh`, `./gradlew testDebugUnitTest lintDebug assembleDebug`.
3. Changes to video, audio, discovery or CEC can only be verified on a real device — please say
   in the PR what you tested (device, Android version, iPhone/iOS version) and what you saw.
4. Never edit files under `third_party/` (it is downloaded, not committed). Changes to UxPlay go
   into `app/src/main/cpp/shim/` or as `.patch` files in `patches/`, explained in the PR.
5. Explain notable design choices in code comments (the *why*) and in the PR description.

CI must be green. When a PR is merged into `main`, **semantic-release** publishes a new version
automatically if it contains a `fix:`/`feat:`/breaking commit (squash-merge titles count — give
the PR a Conventional Commit title).

## Updating dependencies
- GitHub Actions and Gradle plugins: Dependabot opens PRs weekly.
- UxPlay / libplist / OpenSSL: change the version + SHA-256 in `scripts/fetch-deps.sh`, run it
  with `--force`, rebuild OpenSSL (`scripts/build-openssl.sh --force`), test on a device, and
  describe the change in a `build(deps): …` commit.
- semantic-release tooling: exact versions in `.github/workflows/release.yml` (`env`).

## Code style
Kotlin: official style (4 spaces). C/C++: 4 spaces, `-Wall -Wextra` clean for our code. Comments
explain *why*. Every JNI function and callback documents which thread it runs on. See
`.editorconfig`.

## Release setup (maintainers, or your own fork)
Releases run only where they are configured; forks without this setup just skip the release job.
1. Create a signing key (see [docs/BUILDING.md](docs/BUILDING.md#5-release-builds-optional)).
2. Repository → **Settings → Secrets and variables → Actions**:
   - **Secrets:** `ANDROID_KEYSTORE_BASE64` (`base64 < airplayrx-release.jks`),
     `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
   - **Variable:** `RELEASE_CERT_SHA256` — the certificate fingerprint as lower-case hex without
     colons:
     ```bash
     keytool -list -v -keystore airplayrx-release.jks -alias airplayrx \
       | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
     ```
     The release fails if an APK is signed with anything else.
3. GitHub Actions must be allowed to create tags (default). semantic-release never commits to
   `main`: the version exists only as the tag, and the release notes on its GitHub Release are
   the changelog.
