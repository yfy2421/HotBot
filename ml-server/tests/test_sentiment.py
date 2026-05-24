import unittest
from unittest.mock import patch

from services.sentiment import analyze


class SentimentServiceTest(unittest.TestCase):

    def test_analyze_real_text_backtest_with_small_labeled_set(self):
        comments = [
            "非常好，体验超出预期",
            "值得期待，进展不错",
            "一般般，没有明显感觉",
            "太差了，完全无法接受",
            "烂透了，别再出了",
        ]

        result = analyze(comments)

        self.assertEqual({"positive": 2, "negative": 2, "neutral": 1}, {
            "positive": result["positive"],
            "negative": result["negative"],
            "neutral": result["neutral"],
        })
        self.assertEqual("争议较大，观点分化", result["summary"])

    @patch("services.sentiment.get_sentiment_config", return_value={"positive_threshold": 0.6, "negative_threshold": 0.4})
    @patch("services.sentiment._score", side_effect=[0.82, 0.73, 0.55, 0.38, 0.21])
    def test_analyze_threshold_regression_against_labeled_examples(self, _score, _config):
        comments = [
            "产品终于有进展了，我比较看好",
            "这次合作大概率能成，值得继续观察",
            "信息还不够，多看看再说",
            "这个节奏太慢了，感觉很难落地",
            "完全不靠谱，风险明显偏高",
        ]

        result = analyze(comments)

        self.assertEqual({"positive": 2, "negative": 2, "neutral": 1}, {
            "positive": result["positive"],
            "negative": result["negative"],
            "neutral": result["neutral"],
        })
        self.assertEqual("争议较大，观点分化", result["summary"])


if __name__ == "__main__":
    unittest.main()