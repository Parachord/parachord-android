#!/usr/bin/env bash
# Bump the Android app's versionName (and auto-increment versionCode),
# commit, and tag. Mirrors desktop's `npm version <x>` workflow.
#
# Usage:
#   scripts/release-version.sh 0.4.0-beta.2
#   scripts/release-version.sh 0.4.0
#
# On success, prints the push command. Does NOT push automatically —
# that matches npm version's behavior and gives one last chance to
# review the commit before triggering the release workflow.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <versionName>" >&2
    echo "Example: $0 0.4.0-beta.2" >&2
    exit 1
fi

NEW_VERSION="$1"
# Strip a leading 'v' if someone typed `v0.4.0` out of desktop habit.
NEW_VERSION="${NEW_VERSION#v}"

# semver-ish sanity check — same regex npm version applies.
if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
    echo "error: '$NEW_VERSION' is not a valid semver" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_FILE="$ROOT/app/build.gradle.kts"
TAG="v$NEW_VERSION"

if git -C "$ROOT" rev-parse "$TAG" >/dev/null 2>&1; then
    echo "error: tag '$TAG' already exists" >&2
    exit 1
fi

if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
    echo "error: working tree has uncommitted changes — commit or stash first" >&2
    git -C "$ROOT" status --short >&2
    exit 1
fi

# Parse + bump versionCode. Android's Play Store requires versionCode
# to strictly increase across every uploaded build, so auto-increment
# regardless of whether the semver went up or down (hotfix demotions
# still need a higher versionCode).
CURRENT_CODE="$(grep -E '^\s*versionCode = ' "$BUILD_FILE" | head -1 | sed -E 's/.*versionCode = ([0-9]+).*/\1/')"
if ! [[ "$CURRENT_CODE" =~ ^[0-9]+$ ]]; then
    echo "error: couldn't parse versionCode from $BUILD_FILE" >&2
    exit 1
fi
NEW_CODE=$((CURRENT_CODE + 1))

# In-place edits. `sed -i ''` is macOS BSD; `sed -i` alone is GNU. Use
# the portable two-arg form.
sed -i.bak -E "s/^(\s*versionCode = )[0-9]+/\1${NEW_CODE}/" "$BUILD_FILE"
sed -i.bak -E "s/^(\s*versionName = )\"[^\"]+\"/\1\"${NEW_VERSION}\"/" "$BUILD_FILE"
rm -f "${BUILD_FILE}.bak"

echo "Bumped versionCode $CURRENT_CODE → $NEW_CODE, versionName → $NEW_VERSION"

git -C "$ROOT" add "$BUILD_FILE"
git -C "$ROOT" commit -m "Release $TAG"
git -C "$ROOT" tag -a "$TAG" -m "Release $TAG"

echo
echo "Ready. To publish:"
echo "  git push && git push origin $TAG"
