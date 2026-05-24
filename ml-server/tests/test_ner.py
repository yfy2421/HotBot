import unittest
from unittest.mock import patch

from services import ner


class NerServiceTest(unittest.TestCase):

    def tearDown(self):
        ner._nlp = None
        ner._ner_status = "idle"

    def test_get_nlp_logs_warning_and_uses_blank_fallback(self):
        fallback_nlp = object()
        with patch("services.ner.spacy.load", side_effect=Exception("missing model")):
            with patch("services.ner.spacy.blank", return_value=fallback_nlp) as mock_blank:
                with self.assertLogs("services.ner", level="WARNING") as captured:
                    nlp = ner.get_nlp()

        self.assertIs(fallback_nlp, nlp)
        self.assertEqual("fallback_blank_zh", ner.ner_backend_status())
        mock_blank.assert_called_once_with("zh")
        self.assertTrue(any("falling back to blank zh pipeline" in message for message in captured.output))


if __name__ == "__main__":
    unittest.main()