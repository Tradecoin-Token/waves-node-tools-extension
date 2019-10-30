package im.mak.notifier

import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.utils.{Base58, _}
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.utx.UtxPool
import im.mak.notifier.PayoutDB.Payout
import im.mak.notifier.settings.PayoutSettings

object Payouts {
  val GenBalanceThreshold: Int = sys.props.get("mining-notifier.gen-balance-threshold").fold(1000)(_.toInt)

  def initPayouts(settings: PayoutSettings, blockchain: Blockchain, address: Address)(
      implicit notifications: NotificationService
  ): Unit = {
    val currentHeight = blockchain.height
    if (!settings.enable || currentHeight < settings.fromHeight) return

    val last = PayoutDB.lastPayoutHeight()
    if ((currentHeight - last) < settings.interval) return

    val fromHeight = math.max(last + 1, settings.fromHeight)
    val toHeight   = currentHeight - 1
    if (toHeight < fromHeight) return

    val leases = blockchain.collectActiveLeases(fromHeight, toHeight) { lease =>
      lazy val height = blockchain.transactionHeight(lease.id())
      blockchain.resolveAlias(lease.recipient).contains(address) && height.exists(h => (fromHeight - h) >= GenBalanceThreshold)
    }

    val generatingBalance = blockchain.balanceSnapshots(address, fromHeight, blockchain.lastBlockId.get).map(_.effectiveBalance).max
    val wavesReward       = PayoutDB.calculateReward(fromHeight, toHeight)

    PayoutDB.addPayout(fromHeight, toHeight, wavesReward, generatingBalance, leases)

    notifications.info(s"Registering payout $fromHeight - $toHeight: ${Format.waves(wavesReward)} Waves")
  }

  def finishUnconfirmedPayouts(settings: PayoutSettings, utx: UtxPool, blockchain: Blockchain, key: KeyPair)(
      implicit notifications: NotificationService
  ): Unit = {
    def commitPayout(payout: Payout): Unit = {
      val total = payout.generatingBalance
      val transfers = payout.activeLeasesDecoded.groupBy(_.sender).mapValues { leases =>
        val amount = leases.map(_.amount).sum
        val share  = amount.toDouble / total
        payout.amount * share
      }

      val txTransfers = transfers
        .map { case (sender, amount) => MassTransferTransaction.ParsedTransfer(sender.toAddress, amount.toLong) }
        .toList
        .ensuring(_.map(_.amount).sum <= payout.amount, "Incorrect payments total amount")
      val fee: Long = {
        val dummyTx = MassTransferTransaction(Asset.Waves, key, txTransfers, System.currentTimeMillis(), 0, Array.emptyByteArray, Nil)
        FeeValidation.getMinFee(blockchain, blockchain.height, dummyTx).fold(_ => FeeValidation.FeeUnit * 2, _.minFeeInWaves)
      }

      val transaction =
        MassTransferTransaction.selfSigned(Asset.Waves, key, txTransfers, System.currentTimeMillis(), fee, Array.emptyByteArray).explicitGet()

      utx.putIfNew(transaction).resultE match {
        case Right(_) =>
          notifications.info(s"Committed payout #${payout.id}: ${transaction.json()} at ${blockchain.height}")
          PayoutDB.setPayoutTxId(payout.id, Base58.encode(transaction.id()), blockchain.height)
        case Left(error) =>
          notifications.error(s"Error committing payout #${payout.id}: $error ($transaction)")
      }
    }

    val unconfirmed = PayoutDB.unconfirmedPayouts()
    unconfirmed filter (p => (p.toHeight + settings.delay) < blockchain.height) foreach { p =>
      p.txId match {
        case Some(txId) =>
          blockchain.transactionHeight(Base58.decode(txId)) match {
            case Some(height) if height >= (p.txHeight.get + settings.delay) =>
              notifications.info(s"Payout #${p.id} (${p.amount} Waves) confirmed at $height")
              PayoutDB.confirmPayout(p.id, height)
            case None => commitPayout(p)
            case _    => // Wait for more confirmations
          }

        case None =>
          commitPayout(p)
      }
    }
  }

  def registerBlock(height: Int, wavesReward: Long): Unit =
    PayoutDB.addMinedBlock(height, wavesReward)
}
