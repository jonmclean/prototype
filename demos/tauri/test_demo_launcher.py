import unittest
from unittest.mock import patch

from textual.widgets import Button, RichLog, TabbedContent

from demo_launcher import DemoLauncher


class DemoLauncherTests(unittest.IsolatedAsyncioTestCase):
    async def test_build_button_mounts_log_before_starting_build(self):
        with patch("demo_launcher._detect_risc0", return_value=False):
            app = DemoLauncher()

        def start_build():
            self.assertEqual(app.phase, "building")
            self.assertFalse(app.query("#setup-screen"))
            for widget_id in (
                "main-screen", "button-bar", "launch-btn", "stop-all-btn",
                "tabs", "tab-build", "log-build", "status-text", "cpu-display",
            ):
                self.assertTrue(app.query_one(f"#{widget_id}").is_mounted)
            self.assertEqual(app.query_one("#tabs", TabbedContent).active, "tab-build")
            self.assertTrue(app.query_one("#launch-btn", Button).disabled)
            app._log("build", "Build started")

        with patch.object(app, "_run_build", side_effect=start_build) as run_build:
            async with app.run_test(size=(120, 60)) as pilot:
                await pilot.click("#build-button")
                await pilot.pause()
                run_build.assert_called_once_with()
                self.assertTrue(app.query_one("#log-build", RichLog).lines)
                await pilot.press("q")


if __name__ == "__main__":
    unittest.main()
