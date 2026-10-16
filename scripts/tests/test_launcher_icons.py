#!/usr/bin/env python3
"""在隔离的 Gradle 项目中验证应用图标生成与覆盖防护。"""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TASK_SOURCE = ROOT / "native-ui/buildSrc"
SVG = '''<svg xmlns="http://www.w3.org/2000/svg" width="108px" height="108px" viewBox="0 0 108 108">
<g id="brand-logo" fill="#000000">
<path id="left-fold" d="M30 40 L40 40 L40 70 L30 70 Z"/>
<path id="top-fold" d="M32 32 L48 30 L70 55 L70 65 Z"/>
<path id="right-fold" d="M74 44 L80 40 L80 66 L74 72 Z"/>
</g></svg>'''


class LauncherIconGenerationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="nexara-icon-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / "native-ui"
        self.project.mkdir(parents=True)
        self.assets = self.root / "assets/branding"
        self.assets.mkdir(parents=True)
        self.master = self.assets / "nexara-fold-master.svg"
        self.master.write_text(SVG, encoding="utf-8")
        (self.project / "settings.gradle.kts").write_text('rootProject.name = "icon-contract"\n')
        (self.project / "build.gradle.kts").write_text('''import com.promenar.nexara.buildlogic.GenerateLauncherIcons
tasks.register<GenerateLauncherIcons>("generateNexaraLauncherIcons") {
    masterSvg.set(layout.projectDirectory.file("../assets/branding/nexara-fold-master.svg"))
    sourceRoots.from(layout.projectDirectory.dir("app/src"))
    outputDirectory.set(layout.buildDirectory.dir("generated/nexaraLauncherIcons/res"))
}
''')
        shutil.copytree(TASK_SOURCE, self.project / "buildSrc", ignore=shutil.ignore_patterns("build", ".gradle"))
        self.resources = self.project / "build/generated/nexaraLauncherIcons/res"

    def run_generator(self, *, expect_success: bool = True) -> subprocess.CompletedProcess:
        env = {key: value for key, value in os.environ.items() if not key.startswith("NEXARA_")}
        result = subprocess.run(
            [str(ROOT / "native-ui/gradlew"), "-p", str(self.project), "--offline",
             "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx256m",
             "generateNexaraLauncherIcons"],
            env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180,
        )
        if expect_success:
            self.assertEqual(result.returncode, 0, result.stdout[-6000:])
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout[-6000:])
        return result

    def xml(self, name: str) -> ET.Element:
        return ET.parse(self.resources / name).getroot()

    def test_normal_round_and_monochrome_share_geometry_with_day_night_colors(self) -> None:
        self.run_generator()
        for api in (26, 33):
            for name in ("ic_launcher", "ic_launcher_round"):
                icon = self.xml(f"mipmap-anydpi-v{api}/{name}.xml")
                self.assertEqual(icon.tag, "adaptive-icon")
                self.assertEqual(icon.find("foreground").get(ANDROID + "drawable"), "@drawable/ic_launcher_foreground")
                if api == 33:
                    self.assertEqual(icon.find("monochrome").get(ANDROID + "drawable"), "@drawable/ic_launcher_monochrome")
        foreground = self.xml("drawable/ic_launcher_foreground.xml")
        monochrome = self.xml("drawable/ic_launcher_monochrome.xml")
        source_paths = [p.get("d") for p in ET.fromstring(SVG).iter() if p.tag.endswith("}path")]
        for vector in (foreground, monochrome):
            self.assertEqual(vector.get(ANDROID + "viewportWidth"), "108")
            self.assertEqual([p.get(ANDROID + "pathData") for p in vector], source_paths)
        self.assertEqual({p.get(ANDROID + "fillColor") for p in monochrome}, {"#000000"})
        for folder, expected in (("values", {"nexara_launcher_background": "#FFFFFF", "nexara_launcher_foreground": "#000000"}),
                                 ("values-night", {"nexara_launcher_background": "#000000", "nexara_launcher_foreground": "#FFFFFF"})):
            self.assertEqual({p.get("name"): p.text for p in self.xml(f"{folder}/launcher_icon_colors.xml")}, expected)
        self.assertEqual(self.xml("mipmap-anydpi/ic_launcher.xml").tag, "vector")

    def test_rebuild_consumes_changed_master_without_stale_paths(self) -> None:
        self.run_generator()
        self.master.write_text(SVG.replace("L40 70", "L41 70"), encoding="utf-8")
        self.run_generator()
        paths = [p.get(ANDROID + "pathData") for p in self.xml("drawable/ic_launcher_foreground.xml")]
        self.assertIn("L41 70", paths[0])
        self.assertNotIn("L40 70", paths[0])

    def test_rejects_legacy_and_variant_resource_overrides(self) -> None:
        override = self.project / "app/src/release/res/mipmap-xxxhdpi/ic_launcher.png"
        override.parent.mkdir(parents=True)
        override.write_bytes(b"stale icon")
        result = self.run_generator(expect_success=False)
        self.assertIn("图标资源必须由母版生成", result.stdout)

    def test_rejects_custom_resource_root_without_res_in_its_path(self) -> None:
        build = self.project / "build.gradle.kts"
        build.write_text(build.read_text().replace('sourceRoots.from(layout.projectDirectory.dir("app/src"))',
                                                  'sourceRoots.from(layout.projectDirectory.dir("customResources"))'))
        override = self.project / "customResources/mipmap-anydpi-night-v26/ic_launcher.xml"
        override.parent.mkdir(parents=True)
        override.write_text('<adaptive-icon/>')
        result = self.run_generator(expect_success=False)
        self.assertIn("图标资源必须由母版生成", result.stdout)

    def test_rejects_manifest_icon_override(self) -> None:
        manifest = self.project / "app/src/release/AndroidManifest.xml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:icon="@drawable/old_logo"/></manifest>')
        result = self.run_generator(expect_success=False)
        self.assertIn("Manifest 图标引用必须统一", result.stdout)

    def test_rejects_night_color_alias_override_in_arbitrary_values_file(self) -> None:
        override = self.project / "app/src/main/res/values-night/old_palette.xml"
        override.parent.mkdir(parents=True)
        override.write_text('<resources><color name="nexara_launcher_foreground">#6366F1</color></resources>')
        result = self.run_generator(expect_success=False)
        self.assertIn("图标资源必须由母版生成", result.stdout)

    def test_rejects_raster_or_transformed_master(self) -> None:
        self.master.write_text(SVG.replace('<g id="brand-logo"', '<image href="photo.png"/><g transform="scale(2)" id="brand-logo"'))
        result = self.run_generator(expect_success=False)
        self.assertIn("母版仅允许", result.stdout)

    def test_rejects_nested_viewport_instead_of_silently_changing_geometry(self) -> None:
        nested = SVG.replace('<path id="left-fold"', '<svg width="108" height="108" viewBox="0 0 216 216"><path id="left-fold"').replace('</g>', '</svg></g>')
        self.master.write_text(nested, encoding="utf-8")
        result = self.run_generator(expect_success=False)
        self.assertIn("母版", result.stdout)


if __name__ == "__main__":
    unittest.main()
