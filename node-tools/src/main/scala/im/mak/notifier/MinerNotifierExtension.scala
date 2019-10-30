package im.mak.notifier

import com.wavesplatform.account.{Address, AddressOrAlias, KeyPair}
import com.wavesplatform.extensions.{Extension, Context => ExtensionContext}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.utils.ScorexLogging
import im.mak.notifier.settings.MinerNotifierSettings
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import net.ceedubs.ficus.Ficus._
import scalaj.http.Http

import scala.concurrent.Future

class MinerNotifierExtension(context: ExtensionContext) extends Extension with ScorexLogging {
  private[this] val chainId: Byte         = context.settings.blockchainSettings.addressSchemeCharacter.toByte
  private[this] val minerKeyPair: KeyPair = context.wallet.privateKeyAccounts.head
  private[this] val minerAddress: Address = Address.fromPublicKey(minerKeyPair.publicKey, chainId)
  private[this] val settings              = context.settings.config.as[MinerNotifierSettings]("miner-notifier")

  @volatile
  private[this] var lastKnownHeight = 1

  def blockUrl(height: Int): String = settings.blockUrl.format(height)

  def checkNextBlock(): Unit = {
    def miningRewardAt(height: Int): Long = context.blockchain.blockAt(height) match {
      case Some(block) if Address.fromPublicKey(block.getHeader().signerData.generator, chainId).isMiner =>
        val blockFee     = context.blockchain.totalFee(height).getOrElse(0L)
        val prevBlockFee = context.blockchain.totalFee(height - 1).getOrElse(0L)
        val blockReward  = context.blockchain.blockReward(height).getOrElse(0L)
        val reward       = (prevBlockFee * 0.6 + blockFee * 0.4 + blockReward).toLong
        reward

      case _ => 0L
    }

    val height = context.blockchain.height
    val startHeight = Seq(1, lastKnownHeight - 1, height - 1 - settings.payout.interval - settings.payout.delay)
      .filter(n => n > 0).max
    (startHeight until height - 1).foreach { height =>
      val reward = miningRewardAt(height)
      Payouts.registerBlock(height, reward)
    }

    if (height == lastKnownHeight + 1) { // otherwise, most likely, the node isn't yet synchronized
      val block = context.blockchain.blockAt(lastKnownHeight).get

      if (settings.notifications.leasing) {
        val leased = block.transactionData.collect {
          case tx: LeaseTransaction =>
            if (tx.recipient.isMiner) tx.amount
            else 0
        }.sum
        val leaseCanceled = block.transactionData.collect {
          case tx: LeaseCancelTransaction =>
            context.blockchain.leaseDetails(tx.leaseId) match {
              case Some(lease) if lease.recipient.isMiner => lease.amount
              case None                                   => 0
            }
        }.sum

        if (leased != leaseCanceled)
          Notifications.info(
            s"Leasing amount was ${if (leased > leaseCanceled) "increased" else "decreased"}" +
              s" by ${Format.waves(Math.abs(leased - leaseCanceled))} Waves at ${blockUrl(lastKnownHeight)}"
          )
      }

      if (settings.notifications.wavesReceived) {
        val wavesReceived = block.transactionData.collect {
          case mt: MassTransferTransaction if mt.assetId == Waves =>
            mt.transfers.collect {
              case t if t.address.isMiner => t.amount
            }.sum
          case t: TransferTransaction if t.assetId == Waves && t.recipient.isMiner => t.amount
        }.sum

        if (wavesReceived > 0) Notifications.info(s"Received ${Format.waves(wavesReceived)} Waves at ${blockUrl(lastKnownHeight)}")
      }

      val reward = miningRewardAt(lastKnownHeight)
      if (reward > 0) Notifications.info(s"Mined ${Format.waves(reward)} Waves ${blockUrl(lastKnownHeight)}")

      if (settings.payout.enable && height % settings.payout.interval == 0) {
        Payouts.initPayouts(settings.payout, context.blockchain, minerAddress)
        Payouts.finishUnconfirmedPayouts(settings.payout, context.utx, context.blockchain, minerKeyPair)
      }
    }

    if (height < lastKnownHeight) {
      Notifications.warn(s"Rollback detected, resetting payouts to height $height")
      PayoutDB.processRollback(height)
    }

    lastKnownHeight = height
  }

  override def start(): Unit = {
    import scala.concurrent.duration._
    Notifications.info(s"$settings")

    lastKnownHeight = context.blockchain.height
    //TODO wait until node is synchronized
    val generatingBalance = context.blockchain.generatingBalance(minerAddress)

    if (settings.notifications.startStop) {
      Notifications.info(
        s"Started at $lastKnownHeight height for miner ${minerAddress.stringRepr}. " +
          s"Generating balance: ${Format.waves(generatingBalance)} Waves"
      )
    }

    if (context.settings.minerSettings.enable) {
      if (generatingBalance < 1000 * 100000000)
        Notifications.warn(
          s"Node doesn't mine blocks!" +
            s" Generating balance is ${Format.waves(generatingBalance)} Waves but must be at least 1000 Waves"
        )
      if (context.blockchain.hasScript(minerAddress))
        Notifications.warn(
          s"Node doesn't mine blocks! Account ${minerAddress.stringRepr} is scripted." +
            s" Send SetScript transaction with null script or use another account for mining"
        )

      Observable
        .interval(1 seconds) // blocks are mined no more than once every 5 seconds
        .foreachL(_ => checkNextBlock())
        .runAsyncLogErr
    } else {
      Notifications.error("Mining is disabled! Enable this (waves.miner.enable) in the Node config and restart node")
      shutdown()
    }
  }

  override def shutdown(): Future[Unit] = Future {
    Notifications.info(s"Turned off at $lastKnownHeight height for miner ${minerAddress.stringRepr}")
  }

  private[this] implicit object Notifications extends NotificationService {
    def sendNotification(text: String): Unit = {
      Http(settings.webhook.url)
        .headers(
          settings.webhook.headers.flatMap(
            s =>
              s.split(":") match {
                case Array(a, b) =>
                  Seq((a.trim, b.trim))
                case _ =>
                  log.error(s"""Can't parse "$s" header! Please check "webhook.headers" config. Its values must be in the "name: value" format""")
                  Seq()
              }
          )
        )
        .postData(settings.webhook.body.replaceAll("%s", text))
        .method(settings.webhook.method)
        .asString
    }

    def info(message: String): Unit = {
      log.info(message)
      sendNotification(message)
    }

    def warn(message: String): Unit = {
      log.warn(message)
      sendNotification(message)
    }

    def error(message: String): Unit = {
      log.error(message)
      sendNotification(message)
    }
  }

  private[this] implicit class AddressExt(a: AddressOrAlias) {
    def isMiner: Boolean =
      context.blockchain.resolveAlias(a).exists(_ == minerAddress)
  }
}
