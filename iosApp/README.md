# Parachord iOS

SwiftUI app that links the KMP `shared` module's `Shared.framework` and
renders a smoke-test view exercising shared-module APIs from Swift.

## Status: Phase 2 — Hello-shared

The current `ContentView.swift` is a smoke test, not a production UI.
It exists to prove that the static `Shared.framework` produced by
`./gradlew :shared:linkDebugFrameworkIos*` links and exposes its KMP
classes to Swift.

## Build

Open `Parachord.xcodeproj` in Xcode and pick a target Simulator. The
project has a "Run gradle" build phase that runs
`:shared:embedAndSignAppleFrameworkForXcode` before Compile Sources,
so the shared framework is always (re)built from source for the
matching `$CONFIGURATION` + `$SDK_NAME` + `$ARCHS` before Swift
compilation starts.

Headless build:

```bash
xcodebuild build \
  -project iosApp/Parachord.xcodeproj \
  -scheme Parachord \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro'
```

## Layout

```
iosApp/
├── Parachord.xcodeproj/         # Single iOS app target ("Parachord")
└── Parachord/
    ├── ParachordApp.swift       # @main SwiftUI App entry
    ├── ContentView.swift        # Hello-shared smoke test
    └── Assets.xcassets/         # App icon stub + brand-purple AccentColor
```

The `Info.plist` is synthesised from `INFOPLIST_KEY_*` build settings —
no separate file in the source tree.

## Bundle ID

`com.parachord.ios` — distinct from the Android `com.parachord.android`
so the two can coexist on the same Apple ID without provisioning
conflicts.

## Deployment target

iOS 17.0. Matches the KMP `iosMain` source set and current
`IPHONEOS_DEPLOYMENT_TARGET`. iPad and iPhone supported
(`TARGETED_DEVICE_FAMILY = "1,2"`).
