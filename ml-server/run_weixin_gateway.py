from __future__ import annotations

import argparse
import logging

from weixin_gateway.config import load_config
from weixin_gateway.gateway import WeixinGatewayBot


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Weixin gateway for hotspot bot")
    parser.add_argument("--logout", action="store_true", help="Clear saved Weixin credentials and runtime state")
    args = parser.parse_args()

    configure_logging()

    config = load_config()
    bot = WeixinGatewayBot(config)
    if args.logout:
        bot.clear_local_state()
        print("已清除微信登录凭证和上下文缓存，下次启动需要重新扫码。")
        return

    bot.start()


if __name__ == "__main__":
    main()
