"""Creates the Apple project skeleton WITHOUT AI, so agents never write Xcode project files by hand.

Layout (one shared SwiftUI codebase, an iPhone app and a Mac app):
    apple/
      project.yml      XcodeGen spec -> `xcodegen generate` makes <Name>.xcodeproj
      Shared/          SwiftUI code used by both apps (agents write here)
      iOS/             iPhone-only code (optional)
      macOS/           Mac-only code (optional)
      Tests/           XCTest unit tests, run on both platforms
Schemes: <Name>-iOS and <Name>-macOS, each runs the shared tests.
"""
import re
from pathlib import Path

PROJECT_YML = """\
name: {name}
options:
  bundleIdPrefix: {bundle_prefix}
  deploymentTarget:
    iOS: "17.0"
    macOS: "14.0"
  createIntermediateGroups: true
settings:
  base:
    SWIFT_VERSION: "5.0"
    MARKETING_VERSION: "1.0"
    CURRENT_PROJECT_VERSION: "1"
    GENERATE_INFOPLIST_FILE: YES
    ENABLE_USER_SCRIPT_SANDBOXING: YES
targets:
  {name}-iOS:
    type: application
    platform: iOS
    sources:
      - path: Shared
      - path: iOS
        optional: true
    settings:
      base:
        PRODUCT_NAME: {name}
        PRODUCT_MODULE_NAME: {name}
        PRODUCT_BUNDLE_IDENTIFIER: {bundle_prefix}.{lower}
        TARGETED_DEVICE_FAMILY: "1,2"
        INFOPLIST_KEY_UILaunchScreen_Generation: YES
        INFOPLIST_KEY_UIApplicationSceneManifest_Generation: YES
        INFOPLIST_KEY_UISupportedInterfaceOrientations: UIInterfaceOrientationPortrait
  {name}-macOS:
    type: application
    platform: macOS
    sources:
      - path: Shared
      - path: macOS
        optional: true
    settings:
      base:
        PRODUCT_NAME: {name}
        PRODUCT_MODULE_NAME: {name}
        PRODUCT_BUNDLE_IDENTIFIER: {bundle_prefix}.{lower}.mac
        CODE_SIGN_IDENTITY: "-"
        ENABLE_HARDENED_RUNTIME: NO
  {name}-iOSTests:
    type: bundle.unit-test
    platform: iOS
    sources: [Tests]
    dependencies:
      - target: {name}-iOS
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: {bundle_prefix}.{lower}.tests
  {name}-macOSTests:
    type: bundle.unit-test
    platform: macOS
    sources: [Tests]
    dependencies:
      - target: {name}-macOS
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: {bundle_prefix}.{lower}.mac.tests
        CODE_SIGN_IDENTITY: "-"
schemes:
  {name}-iOS:
    build:
      targets:
        {name}-iOS: all
    test:
      targets: [{name}-iOSTests]
  {name}-macOS:
    build:
      targets:
        {name}-macOS: all
    test:
      targets: [{name}-macOSTests]
"""

# Minimal working app + test: used by `doctor --smoke` and as the starting point agents build on.
STARTER = {
    "Shared/{name}App.swift": """\
import SwiftUI

@main
struct {name}App: App {{
    var body: some Scene {{
        WindowGroup {{
            ContentView()
        }}
    }}
}}
""",
    "Shared/ContentView.swift": """\
import SwiftUI

struct ContentView: View {{
    @State private var count = Counter()

    var body: some View {{
        VStack(spacing: 16) {{
            Text("{name}").font(.largeTitle.bold())
            Text("Taps: \\(count.value)").font(.title2)
            Button("Tap") {{ count.increment() }}
                .buttonStyle(.borderedProminent)
        }}
        .padding()
    }}
}}

struct Counter {{
    private(set) var value = 0
    mutating func increment() {{ value += 1 }}
}}
""",
    "Tests/{name}Tests.swift": """\
import XCTest
@testable import {name}

final class {name}Tests: XCTestCase {{
    func testCounterIncrements() {{
        var c = Counter()
        c.increment()
        c.increment()
        XCTAssertEqual(c.value, 2)
    }}
}}
""",
}


def app_name(text, fallback="IrisApp"):
    """'habit tracker!' -> 'HabitTracker'. Letters/digits only, starts with a letter, max 30 chars."""
    words = re.findall(r"[A-Za-z0-9]+", str(text or ""))
    name = "".join(w[:1].upper() + w[1:] for w in words)[:30]
    if not name or not name[0].isalpha():
        name = fallback
    return name


def create(project_dir, name, starter=True, bundle_prefix="com.iris.apps"):
    """Writes apple/project.yml (+ starter files). Never overwrites existing files. Returns the apple/ dir."""
    name = app_name(name)
    if not re.fullmatch(r"[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+", bundle_prefix):
        raise ValueError("bundle prefix must look like com.example.apps")
    root = Path(project_dir) / "apple"
    for d in ("Shared", "iOS", "macOS", "Tests"):
        (root / d).mkdir(parents=True, exist_ok=True)
    files = {"project.yml": PROJECT_YML.format(name=name, lower=name.lower(), bundle_prefix=bundle_prefix)}
    if starter:
        files.update({k.format(name=name): v.format(name=name) for k, v in STARTER.items()})
    for rel, content in files.items():
        p = root / rel
        if not p.exists():
            p.write_text(content, encoding="utf-8")
    return root
