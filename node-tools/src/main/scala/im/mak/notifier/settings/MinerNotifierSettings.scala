package im.mak.notifier.settings

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader.arbitraryTypeValueReader
import net.ceedubs.ficus.readers.ValueReader
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

case class MinerNotifierSettings(
    blockUrl: String,
    notifications: NotificationsSettings,
    webhook: WebhookSettings,
    payout: PayoutSettings
)

object MinerNotifierSettings {
  implicit val valueReader: ValueReader[MinerNotifierSettings] = arbitraryTypeValueReader
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
  require(interval > 0, s"Invalid interval: $interval")
  require(percent > 0 && percent <= 100, s"Invalid payout percent: $percent")
}

object PayoutSettings {
  implicit val valueReader: ValueReader[PayoutSettings] = arbitraryTypeValueReader
}
