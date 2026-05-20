from __future__ import annotations

import logging
import time

from .backend import AssistantBackendClient, AssistantReply
from .client import WeixinIlinkClient
from .config import WeixinGatewayConfig
from .state import WeixinStateStore
from .types import LoginCredentials, WeixinMessage


logger = logging.getLogger("weixin-gateway")

USER_MESSAGE_TYPE = 1
TEXT_ITEM_TYPE = 1
MAX_QR_REFRESH_COUNT = 3


class WeixinGatewayBot:
    def __init__(self, config: WeixinGatewayConfig):
        self.config = config
        self.store = WeixinStateStore(config.state_dir)
        self.client = WeixinIlinkClient(config)
        self.backend = AssistantBackendClient(config)
        self.context_tokens = self.store.load_context_tokens()
        self.cursor = self.store.load_cursor()
        self.credentials = self.store.load_credentials()
        self.running = False

    def clear_local_state(self) -> None:
        self.store.clear_credentials()
        self.store.clear_runtime_state()
        self.context_tokens.clear()
        self.cursor = ""
        self.credentials = None

    def ensure_login(self, force: bool = False) -> LoginCredentials:
        if self.credentials and not force:
            logger.info("using saved credentials account_id=%s", self.credentials.account_id)
            return self.credentials

        refresh_count = 0
        qr = self.client.fetch_qr_code(local_token_list=self._known_tokens())
        qrcode = qr.get("qrcode", "")
        qrcode_url = qr.get("qrcode_img_content", "")
        if not qrcode or not qrcode_url:
            raise RuntimeError("failed to fetch Weixin login QR code")

        logger.info("scan the QR code below with WeChat")
        print("\n请用微信扫码登录：")
        print(qrcode_url)
        print()

        deadline = time.time() + 8 * 60
        current_base_url = self.config.ilink_base_url

        while time.time() < deadline:
            status = self.client.poll_qr_status(qrcode, base_url=current_base_url)
            state = str(status.get("status", "wait"))

            if state == "wait":
                time.sleep(1)
                continue
            if state == "scaned":
                logger.info("QR code scanned, confirm on your phone")
                time.sleep(1)
                continue
            if state == "scaned_but_redirect":
                redirect_host = str(status.get("redirect_host", "")).strip()
                if redirect_host:
                    current_base_url = redirect_host if redirect_host.startswith("http") else f"https://{redirect_host}"
                time.sleep(1)
                continue
            if state == "need_verifycode":
                raise RuntimeError("Weixin login requires verify code, current adapter does not automate this flow yet")
            if state == "verify_code_blocked":
                raise RuntimeError("Weixin login verify code was blocked by server")
            if state == "expired":
                refresh_count += 1
                if refresh_count > MAX_QR_REFRESH_COUNT:
                    raise RuntimeError("QR code expired too many times")
                qr = self.client.fetch_qr_code(
                    local_token_list=self._known_tokens(),
                    base_url=self.config.ilink_base_url,
                )
                qrcode = qr.get("qrcode", "")
                qrcode_url = qr.get("qrcode_img_content", "")
                print("二维码已过期，请重新扫码：")
                print(qrcode_url)
                print()
                continue
            if state in {"confirmed", "binded_redirect"}:
                token = str(status.get("bot_token", "")).strip()
                account_id = str(status.get("ilink_bot_id", "")).strip()
                base_url = str(status.get("baseurl", current_base_url)).strip() or current_base_url
                user_id = str(status.get("ilink_user_id", "")).strip() or None
                if state == "binded_redirect" and self.credentials:
                    logger.info("account already linked, reusing saved credentials")
                    return self.credentials
                if not token or not account_id:
                    raise RuntimeError(f"login confirmed but token/account_id missing: {status}")
                credentials = LoginCredentials(
                    token=token,
                    base_url=base_url,
                    account_id=account_id,
                    user_id=user_id,
                )
                self.credentials = credentials
                self.store.save_credentials(credentials)
                logger.info("Weixin login succeeded account_id=%s", account_id)
                return credentials

            raise RuntimeError(f"unexpected QR status: {state}")

        raise RuntimeError("Weixin login timed out")

    def start(self) -> None:
        credentials = self.ensure_login()
        logger.info("gateway started account_id=%s", credentials.account_id)
        self.running = True
        failures = 0

        while self.running:
            try:
                updates = self.client.get_updates(
                    credentials.token,
                    self.cursor,
                    base_url=credentials.base_url,
                )
                if updates.get("ret") not in (None, 0):
                    failures += 1
                    logger.error(
                        "getupdates failed ret=%s errcode=%s errmsg=%s",
                        updates.get("ret"),
                        updates.get("errcode"),
                        updates.get("errmsg"),
                    )
                    self._sleep_after_failure(failures)
                    continue

                failures = 0
                next_cursor = str(updates.get("get_updates_buf", "") or self.cursor)
                if next_cursor != self.cursor:
                    self.cursor = next_cursor
                    self.store.save_cursor(self.cursor)

                for message in updates.get("msgs", []) or []:
                    self.handle_message(message)
            except KeyboardInterrupt:
                logger.info("gateway stopped by keyboard interrupt")
                self.running = False
            except Exception as exc:
                failures += 1
                logger.exception("gateway loop failed: %s", exc)
                self._sleep_after_failure(failures)

    def stop(self) -> None:
        self.running = False

    def handle_message(self, msg: WeixinMessage) -> None:
        if msg.get("message_type") != USER_MESSAGE_TYPE:
            return
        from_user = str(msg.get("from_user_id", "")).strip()
        if not from_user:
            return

        context_token = str(msg.get("context_token", "")).strip()
        if context_token:
            self.context_tokens[from_user] = context_token
            self.store.save_context_tokens(self.context_tokens)

        text = extract_text_from_message(msg)
        if not text:
            return

        message_id = build_message_id(msg)
        logger.info(
            "received message from=%s text_preview=%s text_length=%s",
            from_user,
            text[:120],
            len(text),
        )

        try:
            assistant_reply = self.backend.chat(from_user, text, message_id)
        except Exception as exc:
            logger.exception("assistant backend failed: %s", exc)
            assistant_reply = AssistantReply("抱歉，后端暂时不可用，请稍后再试。")

        token = context_token or self.context_tokens.get(from_user)
        if not token:
            logger.warning("skip sending reply because context_token is missing for %s", from_user)
            return
        if not self.credentials:
            raise RuntimeError("missing login credentials while sending reply")

        reply_text = assistant_reply.reply
        if assistant_reply.has_media:
            media_source = assistant_reply.media_path or assistant_reply.media_url
            if media_source:
                caption = assistant_reply.media_caption or reply_text
                try:
                    self.client.send_media_message(
                        token=self.credentials.token,
                        to_user_id=from_user,
                        media_source=media_source,
                        media_type=assistant_reply.media_type,
                        text=caption,
                        context_token=token,
                        base_url=self.credentials.base_url,
                        cdn_base_url=self.config.cdn_base_url,
                    )
                    logger.info(
                        "sent media reply to=%s media_type=%s reply_preview=%s reply_length=%s",
                        from_user,
                        assistant_reply.media_type,
                        caption[:120],
                        len(caption),
                    )
                    return
                except Exception as exc:
                    logger.exception("media reply failed, falling back to text: %s", exc)

        if not reply_text:
            logger.warning("skip text reply because reply body is empty for %s", from_user)
            return

        self.client.send_text_message(
            token=self.credentials.token,
            to_user_id=from_user,
            text=reply_text,
            context_token=token,
            base_url=self.credentials.base_url,
        )
        logger.info(
            "sent reply to=%s reply_preview=%s reply_length=%s",
            from_user,
            reply_text[:120],
            len(reply_text),
        )

    def _sleep_after_failure(self, failures: int) -> None:
        if failures >= self.config.max_consecutive_failures:
            logger.warning(
                "consecutive failures=%s, backing off for %ss",
                failures,
                self.config.backoff_delay_seconds,
            )
            time.sleep(self.config.backoff_delay_seconds)
        else:
            time.sleep(self.config.retry_delay_seconds)

    def _known_tokens(self) -> list[str]:
        if self.credentials and self.credentials.token:
            return [self.credentials.token]
        return []


def extract_text_from_message(msg: WeixinMessage) -> str:
    for item in msg.get("item_list", []) or []:
        if item.get("type") != TEXT_ITEM_TYPE:
            continue
        text = str((item.get("text_item") or {}).get("text", "")).strip()
        if not text:
            continue
        ref_title = str((item.get("ref_msg") or {}).get("title", "")).strip()
        if ref_title:
            return f"[引用: {ref_title}]\n{text}"
        return text
    return ""


def build_message_id(msg: WeixinMessage) -> str:
    for key in ("message_id", "seq", "client_id"):
        value = msg.get(key)
        if value is not None and str(value).strip():
            return str(value)
    return ""
