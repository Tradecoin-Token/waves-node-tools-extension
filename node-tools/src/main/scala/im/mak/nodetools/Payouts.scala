package im.mak.nodetools

import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.utils.{Base58, _}
import com.wavesplatform.extensions.Context
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.{Asset, TxVersion}
import com.wavesplatform.transaction.TxValidationError.AlreadyInTheState
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer.{Attachment, MassTransferTransaction}
import com.wavesplatform.utils.{ScorexLogging, Time}
import im.mak.nodetools.PayoutDB.{Payout, PayoutTransaction}
import im.mak.nodetools.settings.PayoutSettings

object Payouts extends ScorexLogging {
  val GenBalanceDepth: Int = sys.props.get("node-tools.gen-balance-depth").fold(1000)(_.toInt)

  def registerBlock(height: Int, wavesReward: Long): Unit =
    PayoutDB.addMinedBlock(height, wavesReward)

  def initPayouts(settings: PayoutSettings, minerKey: KeyPair)(implicit context: Context, notifications: NotificationService): Unit = {
    val minerAddress = minerKey.toAddress

    val currentHeight = context.blockchain.height
    if (!settings.enable || currentHeight < settings.fromHeight) return

    val last = PayoutDB.lastPayoutHeight()
    if ((currentHeight - last) < settings.interval) return

    val fromHeight = math.max(last + 1, settings.fromHeight)
    val toHeight   = currentHeight - 1
    if (toHeight < fromHeight) return

    def registerPayout(from: Int, to: Int): Unit = {
      val leases = getLeasesAtHeight(context.blockchain, from, minerAddress)

      val generatingBalance = context.blockchain.balanceSnapshots(minerAddress, from, context.blockchain.lastBlockId.get).map(_.effectiveBalance).max
      val wavesReward       = PayoutDB.calculateReward(from, to)
      val payoutAmount      = wavesReward * settings.percent / 100

      if (payoutAmount > 0) {
        val payout = PayoutDB.addPayout(from, to, payoutAmount, generatingBalance, leases)
        createPayoutTransactions(context.blockchain, context.time, payout, leases.map(_._2), settings, minerKey)
        notifications.info(s"Registering payout [$from-$to]: ${Format.waves(payoutAmount)} of ${Format.waves(wavesReward)} Waves")
      }
    }

    (fromHeight to toHeight).by(settings.interval + 1).foreach { from =>
      val to = math.min(from + settings.interval, toHeight)
      registerPayout(from, to)
    }
  }

  def finishUnconfirmedPayouts(settings: PayoutSettings, key: KeyPair)(implicit context: Context, notifications: NotificationService): Unit = {
    import context.{blockchain, utx}

    def commitTx(tx: MassTransferTransaction): Unit = {
      utx.putIfNew(tx).resultE match {
        case Right(_) | Left(_: AlreadyInTheState) =>
          val attachmentStr = tx.attachment match {
            case Some(Attachment.Bin(bytes)) => new String(bytes)
            case Some(Attachment.Str(str)) => str
            case _ => "???"
          }
          notifications.info(s"Payout for blocks $attachmentStr was sent. Tx id ${tx.id()}")
        case Left(value) => notifications.error(s"Error sending transaction: $value (tx = ${tx.json()})")
      }
    }

    val unconfirmed = PayoutDB.unconfirmedTransactions(blockchain.height, settings.delay)
    unconfirmed foreach {
      case PayoutTransaction(txId, _, transaction, _) =>
        blockchain.transactionHeight(Base58.decode(txId)) match {
          case Some(txHeight) => PayoutDB.confirmTransaction(txId, txHeight)
          case _              => commitTx(transaction)
        }
    }
  }

  private[this] def getLeasesAtHeight(blockchain: Blockchain, fromHeight: Int, minerAddress: Address): Seq[(Int, LeaseTransaction)] = {
    blockchain
      .collectActiveLeases(1, fromHeight) { lease =>
        blockchain.resolveAlias(lease.recipient).contains(minerAddress)
      }
      .map { lease =>
        val Some(height) = blockchain.transactionHeight(lease.id())
        (height, lease)
      }
      .filter { case (height, _) => (height + GenBalanceDepth) <= fromHeight }
  }

  private[this] def createPayoutTransactions(
      blockchain: Blockchain,
      time: Time,
      payout: Payout,
      leases: Seq[LeaseTransaction],
      settings: PayoutSettings,
      key: KeyPair
  ): Unit = {
    import scala.concurrent.duration._

    val totalBalance = payout.generatingBalance
    val transfers = leases.groupBy(_.sender).mapValues { leases =>
      val leasesSum = leases.map(_.amount).sum
      val share     = leasesSum.toDouble / totalBalance
      val reward    = payout.amount * share
      log.info(s"${leases.head.sender.toAddress} leases sum is ${Format.waves(leasesSum)} of ${Format
        .waves(totalBalance)} (${share * 100}%), reward is ${Format.waves(reward.toLong)}")
      reward
    }

    val allTransfers = transfers
      .collect { case (sender, amount) if amount > 0 => MassTransferTransaction.ParsedTransfer(sender.toAddress, amount.toLong) }
      .ensuring(_.map(_.amount).sum <= payout.amount, "Incorrect payments total amount")

    val timestamp = time.correctedTime() + settings.delay.minutes.toMillis - 5000
    val transactions = allTransfers.toList
      .grouped(100)
      .map { txTransfers =>
        val transactionFee: Long = {
          val dummyTx = MassTransferTransaction(TxVersion.V1, key, Asset.Waves, txTransfers, 0, timestamp, None, Nil)
          FeeValidation.getMinFee(blockchain, dummyTx).fold(_ => FeeValidation.FeeUnit * 2, _.minFeeInWaves)
        }

        val transfersWithoutFee =
          txTransfers.map(t => t.copy(amount = t.amount - (transactionFee / txTransfers.length)))

        MassTransferTransaction
          .selfSigned(
            TxVersion.V1,
            key,
            Asset.Waves,
            transfersWithoutFee,
            transactionFee,
            timestamp,
            Some(Attachment.Bin(s"${payout.fromHeight}-${payout.toHeight}".getBytes))
          )
          .explicitGet()
      }
      .filter(_.transfers.nonEmpty)
      .toVector

    log.info(s"Payout #${payout.id} transactions: [${transactions.map(_.json()).mkString(", ")}]")
    PayoutDB.addPayoutTransactions(payout.id, transactions)
  }
}
