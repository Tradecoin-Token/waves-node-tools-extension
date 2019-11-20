package im.mak.nodetools

import com.wavesplatform.common.utils.Base58
import com.wavesplatform.state.Blockchain
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.utils.ScorexLogging

import scala.util.Try

//noinspection TypeAnnotation
object PayoutDBMigrate extends ScorexLogging {
  import PayoutDB._
  import ctx.{lift => liftQ, _}

  case class MinedBlock(height: Int, reward: Long)

  case class Payout(
      id: Int,
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      generatingBalance: Long,
      activeLeases: Array[Byte],
      txId: Option[String],
      txHeight: Option[Int],
      confirmed: Boolean
  )

  case class PayoutTransaction(id: String, payoutId: Int, transaction: MassTransferTransaction, height: Option[Int])

  private[this] implicit val payoutsMeta            = schemaMeta[Payout]("payouts")
  private[this] implicit val payoutTransactionsMeta = schemaMeta[PayoutTransaction]("payout_transactions")

  def migratePayouts(b: Blockchain): Unit = {
    def migrationNeeded =
      Try(executeQuery[ResultRow]("select tx_id from payouts")).isSuccess

    if (!migrationNeeded) return

    ctx.transaction {
      log.info("Starting DB migration")
      val oldPayouts = run(quote(query[Payout].filter(p => query[PayoutTransaction].filter(_.payoutId == p.id).isEmpty)))
      val withTxs = oldPayouts.map(
        p =>
          p.txId match {
            case Some(txId) =>
              val tx = b.transactionInfo(Base58.decode(txId)).map(_._2)
              p -> tx

            case None =>
              p -> None
          }
      )

      def addTransaction(id: Int, tx: MassTransferTransaction) = quote {
        val txId = liftQ(tx.id().toString)
        query[PayoutTransaction].insert(_.payoutId -> liftQ(id), _.id -> txId, _.transaction -> liftQ(tx))
      }

      def delPayout(id: Int) = quote {
        query[Payout].filter(_.id == liftQ(id)).delete
      }

      withTxs.foreach {
        case (p, tx) =>
          tx match {
            case Some(mtt: MassTransferTransaction) =>
              log.info(s"Registering transaction for $p: $mtt")
              run(addTransaction(p.id, mtt))
            case Some(tx) =>
              sys.error(s"Not mass transfer: $tx")
            case None =>
              log.warn(s"Transaction for $p not found, removing")
              run(delPayout(p.id))
          }
      }

      executeAction("""
                      |alter table payouts
                      |    drop column active_leases, tx_id, tx_height, confirmed;
                      |""".stripMargin)
      log.info("Migration finished")
    }
  }
}
