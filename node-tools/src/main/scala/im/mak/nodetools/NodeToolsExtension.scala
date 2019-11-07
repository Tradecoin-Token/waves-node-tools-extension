package im.mak.nodetools

import com.wavesplatform.account.{Address, AddressOrAlias, KeyPair}
import com.wavesplatform.extensions.{Extension, Context => ExtensionContext}
import com.wavesplatform.state.TransactionId
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.utils.ScorexLogging
import im.mak.nodetools.settings.NodeToolsSettings
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import net.ceedubs.ficus.Ficus._
import scalaj.http.Http

import scala.concurrent.Future
import scala.util.Try
import scala.util.matching.Regex

class NodeToolsExtension(context: ExtensionContext) extends Extension with ScorexLogging {
  private[this] implicit def implicitContext: ExtensionContext = context

  private[this] val chainId: Byte         = context.settings.blockchainSettings.addressSchemeCharacter.toByte
  private[this] val minerKeyPair: KeyPair = context.wallet.privateKeyAccounts.head //TODO addresses with >= 1000 Waves
  private[this] val minerAddress: Address = Address.fromPublicKey(minerKeyPair.publicKey, chainId)
  private[this] val settings              = context.settings.config.as[NodeToolsSettings]("node-tools")

  @volatile
  private[this] var lastKnownHeight = 0

  override def start(): Unit = {
    import scala.concurrent.duration._

    val stateVersion = PayoutDB.getVersion("payout_db").getOrElse(0)
    require(stateVersion <= 2, "Unsupported version")
    PayoutDB.setVersion("payout_db", 2)

    if (stateVersion < 2) Try {
      log.info("Starting DB migration")
      PayoutDBMigrate.migratePayouts(context.blockchain)
      log.info("Migration finished")
    }

    if (settings.payout.enable) {
      require(
        settings.payout.delay >= context.settings.dbSettings.maxRollbackDepth,
        "Payout delay can't be less than Node's maxRollbackDepth parameter."
          + s" Delay: ${settings.payout.delay}, maxRollbackDepth: ${context.settings.dbSettings.maxRollbackDepth}"
      )
      //TODO fromHeight / fromHeightDb / lastCheckedHeight
    }
    notifications.info(s"$settings")

    lastKnownHeight = PayoutDB.lastRegisteredHeight().getOrElse(context.blockchain.height)
    val generatingBalance = context.blockchain.generatingBalance(minerAddress)

    if (settings.notifications.startStop) {
      notifications.info(
        s"Started at $lastKnownHeight height for miner ${minerAddress.stringRepr}. " +
          s"Generating balance: ${Format.waves(generatingBalance)} Waves"
      )
    }

    if (context.settings.minerSettings.enable) {
      if (generatingBalance < 1000 * 100000000)
        notifications.error(
          s"Node doesn't mine blocks!" +
            s" Generating balance is ${Format.waves(generatingBalance)} Waves but must be at least 1000 Waves"
        )
      if (context.blockchain.hasScript(minerAddress))
        notifications.error(
          s"Node doesn't mine blocks! Account ${minerAddress.stringRepr} is scripted." +
            s" Send SetScript transaction with null script or use another account for mining"
        )

      Observable
        .interval(2 seconds) // blocks are generated no more than once every 5 seconds
        .foreachL(_ => checkNextBlock())
        .runAsyncLogErr
    } else {
      notifications.warn("Mining is disabled! Enable this (waves.miner.enable) in the Node config and restart node")
    }
  }

  override def shutdown(): Future[Unit] = Future {
    notifications.info(s"Turned off at $lastKnownHeight height for miner ${minerAddress.stringRepr}")
  }

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
    (lastKnownHeight until height).foreach { height =>
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
        val canceled = block.transactionData.collect {
          case tx: LeaseCancelTransaction =>
            context.blockchain.leaseDetails(tx.leaseId) match {
              case Some(lease) if lease.recipient.isMiner => lease.amount
              case None                                   => 0
            }
        }.sum

        if (leased != canceled)
          notifications.info(
            s"Leasing amount was ${if (leased > canceled) "increased" else "decreased"}" +
              s" by ${Format.waves(Math.abs(leased - canceled))} Waves at ${blockUrl(lastKnownHeight)}"
          )
      }

      if (settings.notifications.wavesReceived) {
        val wavesReceived = block.transactionData.collect {
          case tr: TransferTransaction if tr.assetId == Waves && tr.recipient.isMiner => tr.amount
          case mt: MassTransferTransaction if mt.assetId == Waves =>
            mt.transfers.collect {
              case t if t.address.isMiner => t.amount
            }.sum
          case is: InvokeScriptTransaction if context.settings.dbSettings.storeInvokeScriptResults =>
            context.blockchain
              .invokeScriptResult(TransactionId(is.id()))
              .right
              .get
              .transfers
              .collect {
                case pmt if pmt.address.isMiner && pmt.asset == Waves => pmt.amount
              }
              .sum
        }.sum

        if (wavesReceived > 0) notifications.info(s"Received ${Format.waves(wavesReceived)} Waves at ${blockUrl(lastKnownHeight)}")
      }

      //TODO notifications.mined-block=yes
      val reward = miningRewardAt(lastKnownHeight)
      if (reward > 0) notifications.info(s"Mined ${Format.waves(reward)} Waves ${blockUrl(lastKnownHeight)}")

      //TODO interval + delay
      if (settings.payout.enable) Payouts.initPayouts(settings.payout, minerKeyPair)

      Payouts.finishUnconfirmedPayouts(settings.payout, context.utx, context.blockchain, minerKeyPair)
    }

    if (height < lastKnownHeight) {
      notifications.warn(s"Rollback detected, resetting payouts to height $height")
      PayoutDB.processRollback(height)
    }

    lastKnownHeight = height
  }

  private[this] implicit lazy val notifications: NotificationService = new NotificationService {
    private[this] def sendNotification(text: String): Unit = {
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
        .postData(settings.webhook.body.replaceAll("%s", Regex.quoteReplacement(text)))
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

  private[this] def blockUrl(height: Int): String = settings.blockUrl.format(height)

  private[this] implicit class AddressExt(a: AddressOrAlias) {
    def isMiner: Boolean =
      context.blockchain.resolveAlias(a).exists(_ == minerAddress)
  }
}
