from __future__ import annotations

import base64
import hashlib
import json
import mimetypes
import os
import random
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

import requests
from Crypto.Cipher import AES

from .config import WeixinGatewayConfig
from .types import GetUpdatesResponse, QRCodeResponse, QRStatusResponse


TEXT_ITEM_TYPE = 1
IMAGE_ITEM_TYPE = 2
FILE_ITEM_TYPE = 4
VIDEO_ITEM_TYPE = 5
MESSAGE_TYPE_BOT = 2
MESSAGE_STATE_FINISH = 2
UPLOAD_MEDIA_TYPE_IMAGE = 1
UPLOAD_MEDIA_TYPE_VIDEO = 2
UPLOAD_MEDIA_TYPE_FILE = 3


@dataclass(frozen=True)
class PreparedMediaSource:
    file_path: Path
    file_name: str
    cleanup_path: Path | None = None


@dataclass(frozen=True)
class UploadedMedia:
    download_encrypted_query_param: str
    aes_key: bytes
    aes_key_hex: str
    file_size: int
    file_size_ciphertext: int
    file_name: str
    thumb_download_encrypted_query_param: str | None = None
    thumb_file_size_ciphertext: int | None = None


class WeixinIlinkClient:
    def __init__(self, config: WeixinGatewayConfig):
        self.config = config
        self.session = requests.Session()

    def fetch_qr_code(
        self,
        local_token_list: list[str] | None = None,
        base_url: str | None = None,
    ) -> QRCodeResponse:
        body = {"local_token_list": local_token_list or []}
        return self._post(
            endpoint=f"ilink/bot/get_bot_qrcode?bot_type={self.config.bot_type}",
            body=body,
            token=None,
            timeout=self.config.api_timeout_seconds,
            base_url=base_url,
        )

    def poll_qr_status(
        self,
        qrcode: str,
        verify_code: str | None = None,
        base_url: str | None = None,
    ) -> QRStatusResponse:
        endpoint = f"ilink/bot/get_qrcode_status?qrcode={requests.utils.quote(qrcode)}"
        if verify_code:
            endpoint += f"&verify_code={requests.utils.quote(verify_code)}"
        return self._get(
            endpoint=endpoint,
            timeout=self.config.qr_poll_timeout_seconds,
            base_url=base_url,
        )

    def get_updates(self, token: str, cursor: str, base_url: str | None = None) -> GetUpdatesResponse:
        try:
            return self._post(
                endpoint="ilink/bot/getupdates",
                body={"get_updates_buf": cursor},
                token=token,
                timeout=self.config.long_poll_timeout_seconds,
                base_url=base_url,
            )
        except requests.Timeout:
            return {"ret": 0, "msgs": [], "get_updates_buf": cursor}

    def send_text_message(
        self,
        token: str,
        to_user_id: str,
        text: str,
        context_token: str | None,
        base_url: str | None = None,
    ) -> None:
        if not text:
            return
        self._send_message_items(
            token=token,
            to_user_id=to_user_id,
            item_list=[
                {
                    "type": TEXT_ITEM_TYPE,
                    "text_item": {"text": text},
                }
            ],
            context_token=context_token,
            base_url=base_url,
        )

    def send_media_message(
        self,
        token: str,
        to_user_id: str,
        media_source: str,
        media_type: str | None,
        text: str | None,
        context_token: str | None,
        base_url: str | None = None,
        cdn_base_url: str | None = None,
    ) -> None:
        prepared = self._prepare_media_source(media_source)
        try:
            normalized_media_type = self._normalize_media_type(media_type, prepared.file_path)
            uploaded = self._upload_media(
                token=token,
                to_user_id=to_user_id,
                prepared=prepared,
                media_type=normalized_media_type,
                base_url=base_url,
                cdn_base_url=cdn_base_url or self.config.cdn_base_url,
            )
            if text:
                self.send_text_message(
                    token=token,
                    to_user_id=to_user_id,
                    text=text,
                    context_token=context_token,
                    base_url=base_url,
                )
            self._send_message_items(
                token=token,
                to_user_id=to_user_id,
                item_list=[self._build_media_item(normalized_media_type, uploaded)],
                context_token=context_token,
                base_url=base_url,
            )
        finally:
            if prepared.cleanup_path and prepared.cleanup_path.exists():
                prepared.cleanup_path.unlink(missing_ok=True)

    def _send_message_items(
        self,
        token: str,
        to_user_id: str,
        item_list: list[dict],
        context_token: str | None,
        base_url: str | None = None,
    ) -> None:
        client_id = f"py-hotspot-{uuid.uuid4().hex[:12]}"
        body = {
            "msg": {
                "from_user_id": "",
                "to_user_id": to_user_id,
                "client_id": client_id,
                "message_type": MESSAGE_TYPE_BOT,
                "message_state": MESSAGE_STATE_FINISH,
                "context_token": context_token,
                "item_list": item_list,
            }
        }
        self._post(
            endpoint="ilink/bot/sendmessage",
            body=body,
            token=token,
            timeout=self.config.api_timeout_seconds,
            base_url=base_url,
        )

    def _prepare_media_source(self, media_source: str) -> PreparedMediaSource:
        if media_source.startswith(("http://", "https://")):
            response = self.session.get(
                media_source,
                timeout=self.config.api_timeout_seconds,
            )
            response.raise_for_status()
            temp_dir = self.config.state_dir / "outbound-temp"
            temp_dir.mkdir(parents=True, exist_ok=True)
            ext = self._guess_extension(response.headers.get("content-type"), media_source)
            temp_path = temp_dir / f"weixin-{uuid.uuid4().hex}{ext}"
            temp_path.write_bytes(response.content)
            parsed_name = Path(urlparse(media_source).path).name
            file_name = parsed_name or temp_path.name
            return PreparedMediaSource(
                file_path=temp_path,
                file_name=file_name,
                cleanup_path=temp_path,
            )

        resolved_path = self._resolve_local_media_path(media_source)
        if not resolved_path.exists() or not resolved_path.is_file():
            raise FileNotFoundError(f"media source not found: {resolved_path}")
        return PreparedMediaSource(file_path=resolved_path, file_name=resolved_path.name)

    def _resolve_local_media_path(self, media_source: str) -> Path:
        if media_source.startswith("file://"):
            parsed = urlparse(media_source)
            resolved = unquote(parsed.path)
            if os.name == "nt" and resolved.startswith("/"):
                resolved = resolved.lstrip("/")
            path = Path(resolved)
        else:
            path = Path(media_source)
        return path if path.is_absolute() else path.resolve()

    def _upload_media(
        self,
        token: str,
        to_user_id: str,
        prepared: PreparedMediaSource,
        media_type: str,
        base_url: str | None,
        cdn_base_url: str,
    ) -> UploadedMedia:
        plaintext = prepared.file_path.read_bytes()
        thumb_plaintext = self._resolve_thumb_plaintext(media_type, plaintext)
        file_size = len(plaintext)
        file_size_ciphertext = self._aes_padded_size(file_size)
        filekey = uuid.uuid4().hex
        aes_key = os.urandom(16)
        aes_key_hex = aes_key.hex()
        upload_resp = self._post(
            endpoint="ilink/bot/getuploadurl",
            body=self._build_upload_request_body(
                filekey=filekey,
                media_type=media_type,
                to_user_id=to_user_id,
                plaintext=plaintext,
                aes_key_hex=aes_key_hex,
                thumb_plaintext=thumb_plaintext,
            ),
            token=token,
            timeout=self.config.api_timeout_seconds,
            base_url=base_url,
        )
        upload_full_url = str(upload_resp.get("upload_full_url", "")).strip()
        upload_param = str(upload_resp.get("upload_param", "")).strip()
        if not upload_full_url and not upload_param:
            raise RuntimeError("getuploadurl returned no upload_full_url or upload_param")
        download_param = self._upload_buffer_to_cdn(
            plaintext=plaintext,
            aes_key=aes_key,
            upload_full_url=upload_full_url or None,
            upload_param=upload_param or None,
            filekey=filekey,
            cdn_base_url=cdn_base_url,
        )
        thumb_download_param = None
        thumb_file_size_ciphertext = None
        if thumb_plaintext is not None:
            thumb_upload_param = str(upload_resp.get("thumb_upload_param", "")).strip()
            if not thumb_upload_param:
                raise RuntimeError("getuploadurl returned no thumb_upload_param for image media")
            thumb_download_param = self._upload_buffer_to_cdn(
                plaintext=thumb_plaintext,
                aes_key=aes_key,
                upload_full_url=None,
                upload_param=thumb_upload_param,
                filekey=filekey,
                cdn_base_url=cdn_base_url,
            )
            thumb_file_size_ciphertext = self._aes_padded_size(len(thumb_plaintext))
        return UploadedMedia(
            download_encrypted_query_param=download_param,
            aes_key=aes_key,
            aes_key_hex=aes_key_hex,
            file_size=file_size,
            file_size_ciphertext=file_size_ciphertext,
            file_name=prepared.file_name,
            thumb_download_encrypted_query_param=thumb_download_param,
            thumb_file_size_ciphertext=thumb_file_size_ciphertext,
        )

    def _build_upload_request_body(
        self,
        filekey: str,
        media_type: str,
        to_user_id: str,
        plaintext: bytes,
        aes_key_hex: str,
        thumb_plaintext: bytes | None,
    ) -> dict:
        body = {
            "filekey": filekey,
            "media_type": self._resolve_upload_media_type(media_type),
            "to_user_id": to_user_id,
            "rawsize": len(plaintext),
            "rawfilemd5": hashlib.md5(plaintext).hexdigest(),
            "filesize": self._aes_padded_size(len(plaintext)),
            "no_need_thumb": thumb_plaintext is None,
            "aeskey": aes_key_hex,
        }
        if thumb_plaintext is not None:
            body["thumb_rawsize"] = len(thumb_plaintext)
            body["thumb_rawfilemd5"] = hashlib.md5(thumb_plaintext).hexdigest()
            body["thumb_filesize"] = self._aes_padded_size(len(thumb_plaintext))
        return body

    def _upload_buffer_to_cdn(
        self,
        plaintext: bytes,
        aes_key: bytes,
        upload_full_url: str | None,
        upload_param: str | None,
        filekey: str,
        cdn_base_url: str,
    ) -> str:
        cdn_url = upload_full_url or self._build_cdn_upload_url(
            cdn_base_url=cdn_base_url,
            upload_param=upload_param or "",
            filekey=filekey,
        )
        ciphertext = self._encrypt_aes_ecb(plaintext, aes_key)
        response = self.session.post(
            cdn_url,
            data=ciphertext,
            headers={"Content-Type": "application/octet-stream"},
            timeout=self.config.api_timeout_seconds,
        )
        response.raise_for_status()
        download_param = str(response.headers.get("x-encrypted-param", "")).strip()
        if not download_param:
            raise RuntimeError("cdn upload response missing x-encrypted-param header")
        return download_param

    def _build_media_item(self, media_type: str, uploaded: UploadedMedia) -> dict:
        encoded_aes_key = self._encode_media_aes_key(uploaded.aes_key_hex)
        media_ref = {
            "encrypt_query_param": uploaded.download_encrypted_query_param,
            "aes_key": encoded_aes_key,
            "encrypt_type": 1,
        }
        if media_type == "image":
            image_item = {
                "media": media_ref,
                "aeskey": uploaded.aes_key_hex,
                "mid_size": uploaded.file_size_ciphertext,
                "hd_size": uploaded.file_size_ciphertext,
            }
            if uploaded.thumb_download_encrypted_query_param:
                image_item["thumb_media"] = {
                    "encrypt_query_param": uploaded.thumb_download_encrypted_query_param,
                    "aes_key": encoded_aes_key,
                    "encrypt_type": 1,
                }
                image_item["thumb_size"] = uploaded.thumb_file_size_ciphertext or uploaded.file_size_ciphertext
            return {
                "type": IMAGE_ITEM_TYPE,
                "image_item": image_item,
            }
        if media_type == "video":
            return {
                "type": VIDEO_ITEM_TYPE,
                "video_item": {
                    "media": media_ref,
                    "video_size": uploaded.file_size_ciphertext,
                },
            }
        return {
            "type": FILE_ITEM_TYPE,
            "file_item": {
                "media": media_ref,
                "file_name": uploaded.file_name,
                "len": str(uploaded.file_size),
            },
        }

    @staticmethod
    def _normalize_media_type(media_type: str | None, file_path: Path) -> str:
        normalized = (media_type or "").strip().lower()
        if normalized in {"image", "video", "file"}:
            return normalized
        mime_type, _ = mimetypes.guess_type(str(file_path))
        if mime_type:
            if mime_type.startswith("image/"):
                return "image"
            if mime_type.startswith("video/"):
                return "video"
        return "file"

    @staticmethod
    def _resolve_upload_media_type(media_type: str) -> int:
        if media_type == "image":
            return UPLOAD_MEDIA_TYPE_IMAGE
        if media_type == "video":
            return UPLOAD_MEDIA_TYPE_VIDEO
        return UPLOAD_MEDIA_TYPE_FILE

    @staticmethod
    def _aes_padded_size(plaintext_size: int) -> int:
        return ((plaintext_size // 16) + 1) * 16

    @staticmethod
    def _encrypt_aes_ecb(plaintext: bytes, aes_key: bytes) -> bytes:
        pad_len = 16 - (len(plaintext) % 16)
        padded = plaintext + bytes([pad_len]) * pad_len
        return AES.new(aes_key, AES.MODE_ECB).encrypt(padded)

    @staticmethod
    def _build_cdn_upload_url(cdn_base_url: str, upload_param: str, filekey: str) -> str:
        base = cdn_base_url.rstrip("/")
        return (
            f"{base}/upload?encrypted_query_param={quote(upload_param)}"
            f"&filekey={quote(filekey)}"
        )

    @staticmethod
    def _guess_extension(content_type: str | None, source_url: str) -> str:
        cleaned_content_type = (content_type or "").split(";", 1)[0].strip().lower()
        extension = mimetypes.guess_extension(cleaned_content_type) if cleaned_content_type else None
        if extension:
            return ".jpg" if extension == ".jpe" else extension
        url_path = Path(urlparse(source_url).path)
        if url_path.suffix:
            return url_path.suffix.lower()
        return ".bin"

    @staticmethod
    def _resolve_thumb_plaintext(media_type: str, plaintext: bytes) -> bytes | None:
        return None

    @staticmethod
    def _encode_media_aes_key(aes_key_hex: str) -> str:
        return base64.b64encode(aes_key_hex.encode("utf-8")).decode("utf-8")

    def _get(self, endpoint: str, timeout: int, base_url: str | None = None) -> dict:
        url = self._build_url(endpoint, base_url)
        response = self.session.get(
            url,
            headers=self._build_common_headers(),
            timeout=timeout,
        )
        response.raise_for_status()
        text = response.text.strip()
        return json.loads(text) if text else {}

    def _post(
        self,
        endpoint: str,
        body: dict,
        token: str | None,
        timeout: int,
        base_url: str | None = None,
    ) -> dict:
        request_body = dict(body)
        request_body["base_info"] = {
            "channel_version": self.config.channel_version,
            "bot_agent": self.config.bot_agent,
        }
        raw = json.dumps(request_body, ensure_ascii=False).encode("utf-8")
        headers = self._build_post_headers(token=token, content_length=len(raw))
        response = self.session.post(
            self._build_url(endpoint, base_url),
            data=raw,
            headers=headers,
            timeout=timeout,
        )
        response.raise_for_status()
        text = response.text.strip()
        return json.loads(text) if text and text != "{}" else {}

    def _build_url(self, endpoint: str, base_url: str | None = None) -> str:
        base = (base_url or self.config.ilink_base_url).rstrip("/")
        return f"{base}/{endpoint.lstrip('/')}"

    def _build_common_headers(self) -> dict[str, str]:
        return {
            "iLink-App-Id": self.config.ilink_app_id,
            "iLink-App-ClientVersion": str(self._build_client_version(self.config.channel_version)),
        }

    def _build_post_headers(self, token: str | None, content_length: int) -> dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "AuthorizationType": "ilink_bot_token",
            "X-WECHAT-UIN": self._random_wechat_uin(),
            "Content-Length": str(content_length),
            **self._build_common_headers(),
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return headers

    @staticmethod
    def _random_wechat_uin() -> str:
        number = random.randint(0, 0xFFFFFFFF)
        return base64.b64encode(str(number).encode("utf-8")).decode("utf-8")

    @staticmethod
    def _build_client_version(version: str) -> int:
        parts = [int(item) if item.isdigit() else 0 for item in version.split(".")]
        major = parts[0] if len(parts) > 0 else 0
        minor = parts[1] if len(parts) > 1 else 0
        patch = parts[2] if len(parts) > 2 else 0
        return ((major & 0xFF) << 16) | ((minor & 0xFF) << 8) | (patch & 0xFF)
