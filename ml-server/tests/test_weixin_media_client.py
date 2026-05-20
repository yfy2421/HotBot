import unittest
from pathlib import Path

from weixin_gateway.client import UploadedMedia, WeixinIlinkClient
from weixin_gateway.config import WeixinGatewayConfig


class WeixinMediaClientTest(unittest.TestCase):

    def setUp(self):
        self.client = WeixinIlinkClient(
            WeixinGatewayConfig(
                ilink_base_url="https://ilinkai.weixin.qq.com",
                cdn_base_url="https://novac2c.cdn.weixin.qq.com/c2c",
                ilink_app_id="bot",
                bot_type="3",
                channel_version="2.4.3",
                bot_agent="hotspot-bot/1.0",
                assistant_api_url="http://localhost:8080/api/assistant/chat",
                state_dir=Path("./storage/weixin_gateway"),
                api_timeout_seconds=15,
                long_poll_timeout_seconds=35,
                qr_poll_timeout_seconds=35,
                max_consecutive_failures=5,
                retry_delay_seconds=2,
                backoff_delay_seconds=30,
            )
        )

    def test_image_upload_request_includes_thumb_fields(self):
        plaintext = b"fake-png-bytes"

        body = self.client._build_upload_request_body(
            filekey="abc123",
            media_type="image",
            to_user_id="wx-user",
            plaintext=plaintext,
            aes_key_hex="00112233445566778899aabbccddeeff",
            thumb_plaintext=None,
        )

        self.assertTrue(body["no_need_thumb"])
        self.assertNotIn("thumb_rawsize", body)
        self.assertNotIn("thumb_rawfilemd5", body)
        self.assertNotIn("thumb_filesize", body)

    def test_image_message_contains_openclaw_compatible_aes_key_and_hex_aeskey(self):
        uploaded = UploadedMedia(
            download_encrypted_query_param="orig-param",
            aes_key=bytes.fromhex("00112233445566778899aabbccddeeff"),
            aes_key_hex="00112233445566778899aabbccddeeff",
            file_size=256,
            file_size_ciphertext=272,
            file_name="card.png",
        )

        item = self.client._build_media_item("image", uploaded)

        self.assertEqual(2, item["type"])
        self.assertEqual("orig-param", item["image_item"]["media"]["encrypt_query_param"])
        self.assertEqual("00112233445566778899aabbccddeeff", item["image_item"]["aeskey"])
        self.assertEqual(272, item["image_item"]["hd_size"])
        self.assertNotIn("thumb_media", item["image_item"])
        self.assertEqual(
            "MDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmY=",
            item["image_item"]["media"]["aes_key"],
        )


if __name__ == "__main__":
    unittest.main()