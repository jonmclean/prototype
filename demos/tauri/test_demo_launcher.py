import asyncio
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from rich.text import Text

import demo_launcher


class LogApp:
    def __init__(self):
        self.messages = []

    def _log(self, tab_id, text):
        self.messages.append(
            Text.from_markup(text) if isinstance(text, str) else text
        )


class ProcessManagerTests(unittest.IsolatedAsyncioTestCase):
    async def stream_output(self, output):
        app = LogApp()
        manager = demo_launcher.ProcessManager(app)
        ready = asyncio.Event()
        with tempfile.TemporaryDirectory() as directory:
            proc = await asyncio.create_subprocess_exec(
                sys.executable, "-c",
                "import sys; sys.stdout.buffer.write(sys.stdin.buffer.read())",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT,
            )

            async def write_output():
                proc.stdin.write(output)
                await proc.stdin.drain()
                proc.stdin.close()

            writer = asyncio.create_task(write_output())
            try:
                with patch.object(demo_launcher, "LOGS_DIR", Path(directory)):
                    await asyncio.wait_for(
                        manager._stream("server", proc, "server", ready, "Ready"),
                        timeout=5,
                    )
                await writer
                contents = (Path(directory) / "server.log").read_text(
                    encoding="utf-8"
                )
            finally:
                writer.cancel()
                await asyncio.gather(writer, return_exceptions=True)
                proc.stdin.close()
                if proc.returncode is None:
                    proc.kill()
                await proc.stdout.read()
                await proc.wait()
        return app, ready, contents

    async def test_large_trace_line_does_not_block_child_and_is_saved_in_full(self):
        output = "TRACE payload=" + "a" * (3 * 1024 * 1024) + "\nReady\n"
        app, ready, contents = await self.stream_output(output.encode())
        self.assertTrue(ready.is_set())
        self.assertEqual(contents, output + "\n[Process exited with code 0]\n")
        self.assertLess(max(len(message.plain) for message in app.messages), 8192)

    async def test_subprocess_markup_is_displayed_literally(self):
        output = "TRACE payload=[/not-a-rich-tag]\nReady\n"
        app, ready, contents = await self.stream_output(output.encode())
        self.assertTrue(ready.is_set())
        self.assertEqual(app.messages[0].plain, output.splitlines()[0])
        self.assertEqual(contents, output + "\n[Process exited with code 0]\n")

    async def test_ansi_and_unterminated_output(self):
        output = b"\x1b[32mReady\x1b[0m\nlast line"
        _, ready, contents = await self.stream_output(output)
        self.assertTrue(ready.is_set())
        self.assertEqual(
            contents, "Ready\nlast line\n[Process exited with code 0]\n"
        )

    async def test_utf8_and_ready_marker_across_fragments(self):
        app = LogApp()
        manager = demo_launcher.ProcessManager(app)
        reader = asyncio.StreamReader(limit=32)
        proc = SimpleNamespace(stdout=reader, wait=AsyncMock(return_value=0))
        ready = asyncio.Event()
        chunks = [
            b"x" * 40 + b"\xe2",
            b"\x82\xac" + b"y" * 40 + b"Rea",
            b"dy\ntrailing incomplete UTF-8: \xe2",
        ]
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(demo_launcher, "LOGS_DIR", Path(directory)):
                task = asyncio.create_task(
                    manager._stream("server", proc, "server", ready, "Ready")
                )
                try:
                    for chunk in chunks:
                        reader.feed_data(chunk)
                        await asyncio.sleep(0)
                    reader.feed_eof()
                    await asyncio.wait_for(task, timeout=5)
                finally:
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
            contents = (Path(directory) / "server.log").read_text(
                encoding="utf-8"
            )
        self.assertTrue(ready.is_set())
        self.assertEqual(
            contents,
            b"".join(chunks).decode("utf-8", errors="replace")
            + "\n[Process exited with code 0]\n",
        )


if __name__ == "__main__":
    unittest.main()
