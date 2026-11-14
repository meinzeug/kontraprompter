import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("remote", pathlib.Path(__file__).with_name("remote.py"))
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)


def hierarchy(*texts):
    nodes = "".join('<node package="de.kontrakurs.kontraprompter" text="%s" />' % text for text in texts)
    return '<?xml version="1.0"?><hierarchy>' + nodes + '</hierarchy>'


class RemoteFixtureTest(unittest.TestCase):
    def test_status_accepts_only_unambiguous_app_state(self):
        self.assertEqual((remote.RUNNING, "Pause"), remote.ui_state(hierarchy(remote.RUNNING, "Pause")))
        with self.assertRaises(RuntimeError):
            remote.ui_state(hierarchy(remote.RUNNING, remote.PAUSED, "Pause"))
        with self.assertRaises(RuntimeError):
            remote.ui_state(hierarchy("Ein beliebiges Skript"))

    def test_single_and_double_use_key_down_and_up(self):
        fixture = remote.Fixture("unused")
        fixture.selected = lambda: True
        fixture.device_block = lambda: "present"
        events = []
        fixture.write = events.append
        fixture.press(2)
        self.assertEqual(["inject", "delay", "inject", "delay", "inject", "delay", "inject"], [item["command"] for item in events])
        self.assertEqual([1, 28, 1, 0, 0, 0], events[0]["events"])
        self.assertEqual([1, 28, 0, 0, 0, 0], events[-1]["events"])
        self.assertEqual(150, events[3]["duration"])

    def test_unselected_or_removed_device_does_not_send(self):
        fixture = remote.Fixture("unused")
        fixture.selected = lambda: False
        fixture.device_block = lambda: "present"
        fixture.write = lambda command: self.fail("event must not be sent")
        with self.assertRaises(RuntimeError):
            fixture.press(1)


if __name__ == "__main__":
    unittest.main()
