import unittest

from services.intent import classify_intent, precompute_prototype_embeddings


class TestIntentClassification(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        precompute_prototype_embeddings()

    def _classify(self, text: str):
        return classify_intent(text)

    # ── Clear ──

    def test_clear_command(self):
        self.assertEqual(self._classify("清空对话")["intent"], "clear")
        self.assertGreater(self._classify("清空对话")["confidence"], 0.7)

    def test_clear_variants(self):
        for text in ["清除上下文", "重来", "全部删掉", "忘掉之前说的", "从头开始"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "clear", f"'{text}' should be clear, got {result['intent']}")

    # ── Help ──

    def test_help_command(self):
        result = self._classify("帮助")
        self.assertEqual(result["intent"], "help")

    def test_help_variants(self):
        for text in ["怎么用", "有什么功能", "你能做什么", "使用说明"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "help", f"'{text}' should be help, got {result['intent']}")

    # ── Weather ──

    def test_weather_query(self):
        result = self._classify("天气")
        self.assertEqual(result["intent"], "weather")
        self.assertGreater(result["confidence"], 0.6)

    def test_weather_variants(self):
        for text in ["今天冷不冷", "会下雨吗", "温度多少", "空气质量"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "weather", f"'{text}' should be weather, got {result['intent']}")

    # ── News Overview ──

    def test_news_overview(self):
        result = self._classify("今日热点")
        self.assertEqual(result["intent"], "news_overview")

    def test_news_overview_broad_expressions(self):
        """These are expressions that keyword matching would miss."""
        for text in ["有啥新鲜事", "今天发生了什么", "最近怎么样了", "最近有啥消息"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "news_overview",
                                 f"'{text}' should be news_overview, got {result['intent']}")

    def test_news_overview_recent_variants(self):
        for text in ["最近有什么新闻", "最新的消息", "这几天怎么了"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "news_overview",
                                 f"'{text}' should be news_overview, got {result['intent']}")

    # ── Hotlist ──

    def test_hotlist(self):
        result = self._classify("热搜")
        self.assertEqual(result["intent"], "hotlist")

    def test_hotlist_variants(self):
        for text in ["热搜榜", "热榜", "现在什么最火", "有哪些热搜"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "hotlist", f"'{text}' should be hotlist, got {result['intent']}")

    # ── Detail Follow-up ──

    def test_detail_followup(self):
        for text in ["分析一下", "详细说说", "看原文", "展开讲讲"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "detail_followup",
                                 f"'{text}' should be detail_followup, got {result['intent']}")

    def test_detail_followup_variants(self):
        for text in ["原文呢", "全文", "说详细点", "展开分析", "深入分析"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "detail_followup",
                                 f"'{text}' should be detail_followup, got {result['intent']}")

    # ── Follow-up ──

    def test_follow_up(self):
        result = self._classify("后续")
        self.assertEqual(result["intent"], "follow_up")

    def test_follow_up_variants(self):
        for text in ["后面怎么样", "有进展吗", "然后呢", "有后续吗", "接下来呢"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "follow_up",
                                 f"'{text}' should be follow_up, got {result['intent']}")

    # ── Casual Chat ──

    def test_casual_greeting(self):
        for text in ["早上好", "晚安", "你好"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "casual_chat",
                                 f"'{text}' should be casual_chat, got {result['intent']}")

    def test_casual_variants(self):
        for text in ["哈哈", "不错", "谢谢", "在吗", "ok"]:
            with self.subTest(text=text):
                result = self._classify(text)
                self.assertEqual(result["intent"], "casual_chat",
                                 f"'{text}' should be casual_chat, got {result['intent']}")

    # ── Ambiguous / Low Confidence ──

    def test_casual_single_char_acknowledgment(self):
        # "嗯" is a casual acknowledgment, classified as casual_chat
        result = self._classify("嗯")
        self.assertEqual(result["intent"], "casual_chat")

    def test_nonsense_input_defaults(self):
        # Nonsense / out-of-domain input should hit default with low confidence
        result = self._classify("qwerty12345")
        self.assertEqual(result["intent"], "default")
        self.assertLess(result["confidence"], 0.65)

    def test_empty_text(self):
        result = self._classify("")
        self.assertEqual(result["intent"], "default")
        self.assertEqual(result["confidence"], 0.0)

    # ── All scores present ──

    def test_all_scores_present(self):
        result = self._classify("今日热点")
        expected_intents = {"clear", "help", "weather", "news_overview",
                            "hotlist", "detail_followup", "follow_up", "casual_chat", "default"}
        self.assertTrue(expected_intents.issubset(set(result["all_scores"].keys())))


if __name__ == "__main__":
    unittest.main()
