"""Planned real-client integration tests; placeholders must not report success.

Do not point a future runner at a developer service, production credentials, or
real recordings. It must own and verify both the fixture server and its data.
"""
import unittest


@unittest.skip("not implemented: isolated server lifecycle and real Kotlin client runner")
class LiveUploadRecoveryScenarios(unittest.TestCase):
    def test_put_interrupted_and_new_process_recovers(self):
        """Interrupt a 1-MiB PUT, restart client, reconcile actual server chunks."""
        self.fail("placeholder")

    def test_sigkill_after_server_commit_before_client_receipt(self):
        """Kill own client after committed response; restart performs receipt GET only."""
        self.fail("placeholder")

    def test_muxer_rewrites_and_final_hash_match_original_and_receipt(self):
        """Rewrite initial bytes and final chunk; assert final size/SHA on both ends."""
        self.fail("placeholder")

    def test_live_and_whole_file_uploads_never_overlap(self):
        """Race live binding with final full-file journal; only one path may own the take."""
        self.fail("placeholder")
