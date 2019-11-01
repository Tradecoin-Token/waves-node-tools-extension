package im.mak.nodetools

import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.utils.{Base58, _}
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.TxValidationError.AlreadyInTheState
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.utx.UtxPool
import im.mak.nodetools.PayoutDB.{Payout, PayoutTransaction}
import im.mak.nodetools.settings.PayoutSettings

object Payouts {
  val genBalanceDepth: Int = sys.props.get("node-tools.gen-balance-depth").fold(1000)(_.toInt)

  def initPayouts(settings: PayoutSettings, blockchain: Blockchain, minerAddress: Address)(
      implicit notifications: NotificationService
  ): Unit = {
    val currentHeight = blockchain.height
    if (!settings.enable || currentHeight < settings.fromHeight) return

    val last = PayoutDB.lastPayoutHeight()
    if ((currentHeight - last) < settings.interval) return

    val fromHeight = math.max(last + 1, settings.fromHeight)
    val toHeight   = currentHeight - 1
    if (toHeight < fromHeight) return

    val leases = blockchain
      .collectActiveLeases(1, toHeight) { lease =>
        blockchain.resolveAlias(lease.recipient).contains(minerAddress)
      }
      .map { lease =>
        val Some(height) = blockchain.transactionHeight(lease.id())
        (height, lease)
      }

    val generatingBalance = blockchain.balanceSnapshots(minerAddress, fromHeight, blockchain.lastBlockId.get).map(_.effectiveBalance).max
    val wavesReward       = PayoutDB.calculateReward(fromHeight, toHeight)

    if (wavesReward > 0) {
      PayoutDB.addPayout(fromHeight, toHeight, wavesReward, generatingBalance, leases)
      notifications.info(s"Registering payout [$fromHeight-$toHeight]: ${Format.waves(wavesReward)} Waves")
    }
  }

  def createPayoutTransactions(
      payout: Payout,
      leases: Seq[LeaseTransaction],
      settings: PayoutSettings,
      utx: UtxPool,
      blockchain: Blockchain,
      key: KeyPair
  ): Unit = {
    val total = payout.generatingBalance
    val transfers = leases.groupBy(_.sender).mapValues { leases =>
      val amount = leases.map(_.amount).sum
      val share  = amount.toDouble / total
      payout.amount * share
    }

    val allTransfers = transfers
      .collect { case (sender, amount) if amount > 0 => MassTransferTransaction.ParsedTransfer(sender.toAddress, amount.toLong) }
      .ensuring(_.map(_.amount).sum <= payout.amount, "Incorrect payments total amount")

    val transactions = allTransfers.toList
      .grouped(100)
      .map { txTransfers =>
        val transactionFee: Long = {
          val dummyTx = MassTransferTransaction(Asset.Waves, key, txTransfers, System.currentTimeMillis(), 0, Array.emptyByteArray, Nil)
          FeeValidation.getMinFee(blockchain, blockchain.height, dummyTx).fold(_ => FeeValidation.FeeUnit * 2, _.minFeeInWaves)
        }

        MassTransferTransaction
          .selfSigned(Asset.Waves, key, txTransfers, System.currentTimeMillis(), transactionFee, Array.emptyByteArray)
          .explicitGet()
      }
      .filter(_.transfers.nonEmpty)
      .toVector

    PayoutDB.addPayoutTransactions(payout.id, transactions)
  }

  def finishUnconfirmedPayouts(settings: PayoutSettings, utx: UtxPool, blockchain: Blockchain, key: KeyPair)(
      implicit notifications: NotificationService
  ): Unit = {
    def commitTx(transferTransaction: MassTransferTransaction): Unit = {
      utx.putIfNew(transferTransaction).resultE match {
        case Right(_) | Left(_: AlreadyInTheState) => notifications.info(s"Transaction sent: $transferTransaction")
        case Left(value)                           => notifications.error(s"Error sending transaction: $value (tx = $transferTransaction)")
      }
    }

    val unconfirmed = PayoutDB.unconfirmedTransactions(blockchain.height, settings.delay)
    unconfirmed foreach {
      case PayoutTransaction(txId, _, transaction, _) =>
        blockchain.transactionHeight(Base58.decode(txId)) match {
          case Some(txHeight) => PayoutDB.confirmTransaction(txId, txHeight)
          case None           => commitTx(transaction)
        }
    }
  }

  def registerBlock(height: Int, wavesReward: Long): Unit =
    PayoutDB.addMinedBlock(height, wavesReward)
}
