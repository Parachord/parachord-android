# Play Store publishing

The CI release workflow uploads every tagged build (`v*`) to the Play
Console's internal-testing track automatically. The first release of
the app still has to be uploaded manually through the Play Console web
UI — Google refuses API uploads until the store listing (description,
screenshots, content rating, data safety form, privacy policy URL,
target audience, ads declaration) is filled out for the production
track at least once.

Once that's done, tag-driven uploads just work.

## One-time setup

### 1. Create a Play Console service account

1. Play Console → **Settings → API access**
2. **Link** a Google Cloud project (create one if you don't have one
   already).
3. **Create service account** — this takes you to Google Cloud IAM.
   Give it a name like `parachord-ci-publisher`. Skip the optional
   permission grants; we add them in Play Console.
4. Back in Cloud IAM → open the service account → **Keys** → **Add
   key → Create new key → JSON**. Download the JSON.
5. Back in Play Console → API access → **Grant access** on the new
   account:
   - App permissions: your app(s)
   - Account permissions: **Release manager** (enough to upload; not
     admin).
6. Save.

### 2. Add the secret to GitHub

Repo → **Settings → Secrets and variables → Actions → New
repository secret**:

- Name: `PLAY_SERVICE_ACCOUNT_JSON`
- Value: paste the **entire contents** of the JSON file (one big blob,
  newlines and all). GitHub handles multi-line secrets correctly — no
  base64 needed.

That's the only secret required. The existing `CI_KEYSTORE_*` secrets
continue to handle APK signing; the Play upload reuses the same signed
AAB that the GitHub Release step publishes.

## Tagging a release

```
./scripts/release-version.sh 0.4.0-beta.2
git push && git push origin v0.4.0-beta.2
```

CI builds the signed AAB, attaches it to a GitHub Release, and uploads
it to the Play Console **internal** track. Prerelease version names
(anything containing a hyphen — `0.4.0-beta.1`, `1.0.0-rc.3`) always
route to `internal` even if you override `-Pplay.track=production`, so
stable-flow tooling can't accidentally push a beta to prod.

## Promoting to other tracks

Use the Play Console web UI — it's quick and keeps the staged-rollout
controls (percentage-based rollout, halt, undo) where they belong.
Command-line promotion via `./gradlew promoteArtifact
-Pplay.fromTrack=internal -Pplay.promoteTrack=beta` also works if you
want to script it.

## Resolution strategy

`play.resolutionStrategy = IGNORE` means re-pushing the same tag is a
harmless no-op: the plugin sees the versionCode is already live on the
track and skips the upload. Switch to `FAIL` if you'd rather error
loudly on re-pushes.

## Local debugging

`publishBundle` runs locally too if you place the service-account JSON
at `play-service-account.json` in the repo root (it's in `.gitignore`
so you can't commit it by accident). Useful for debugging credential
problems before they hit CI:

```
./gradlew publishBundle --stacktrace
```
