package im.mak.nodetools.settings

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader.arbitraryTypeValueReader
import net.ceedubs.ficus.readers.ValueReader
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

case class NodeToolsSettings(
    blockUrl: String,
    notifications: NotificationsSettings,
    webhook: WebhookSettings,
    payout: PayoutSettings
)

object NodeToolsSettings {
  implicit val valueReader: ValueReader[NodeToolsSettings] = arbitraryTypeValueReader
}

case class WebhookSettings(
    url: String,
    method: String,
    headers: Seq[String],
    body: String
)

object WebhookSettings {
  implicit val valueReader: ValueReader[WebhookSettings] = arbitraryTypeValueReader
}

case class NotificationsSettings(
    startStop: Boolean,
    wavesReceived: Boolean,
    leasing: Boolean
  //TODO payouts
  //TODO mined
)

object NotificationsSettings {
  implicit val valueReader: ValueReader[NotificationsSettings] = arbitraryTypeValueReader
}

case class PayoutSettings(
    enable: Boolean,
    fromHeight: Int,
    interval: Int,
    delay: Int,
    percent: Int
) {
  if (enable) {
    require(fromHeight > 0, s"Initial payout height must be positive. Actual: $fromHeight")
    require(interval > 0, s"Payout interval must be positive. Actual: $interval")
    require(delay > 0, s"Payout delay must be positive. Actual: $delay")
    require(percent >= 0, s"Payout percent can't be negative. Actual: $percent")
  }
}

object PayoutSettings {
  implicit val valueReader: ValueReader[PayoutSettings] = arbitraryTypeValueReader
}
