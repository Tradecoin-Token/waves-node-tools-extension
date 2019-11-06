package im.mak.nodetools

import com.google.common.io.ByteStreams
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.state.Blockchain
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.transaction.{Transaction, TransactionParsers}
import com.wavesplatform.utils.ScorexLogging

//noinspection TypeAnnotation
class PayoutDBMigrate extends ScorexLogging {
  import io.getquill.{MappedEncoding, _}
  lazy val ctx = new H2JdbcContext(SnakeCase, "node-tools.db.ctx")
  import ctx.{lift => liftQ, _}

  private[this] implicit def transactionEncoding[T <: Transaction]: MappedEncoding[T, Array[Byte]] =
    MappedEncoding[T, Array[Byte]](_.bytes())
  private[this] implicit def transactionDecoding[T <: Transaction]: MappedEncoding[Array[Byte], T] =
    MappedEncoding[Array[Byte], T](data => TransactionParsers.parseBytes(data).get.asInstanceOf[T])

  type LeasesSnapshot = Seq[LeaseTransaction]
  object LeasesSnapshot {
    def toBytes(transactions: LeasesSnapshot): Array[Byte] = {
      val dataOutput = ByteStreams.newDataOutput()
      dataOutput.writeInt(transactions.length)
      transactions.foreach { tx =>
        val txBytes = tx.bytes()
        dataOutput.writeInt(txBytes.length)
        dataOutput.write(txBytes)
      }
      dataOutput.toByteArray
    }

    def fromBytes(bytes: Array[Byte]): LeasesSnapshot = {
      val dataInput = ByteStreams.newDataInput(bytes)
      val length    = dataInput.readInt()
      for (_ <- 1 to length) yield {
        val txLength = dataInput.readInt()
        val txBytes  = new Array[Byte](txLength)
        dataInput.readFully(txBytes)
        TransactionParsers.parseBytes(txBytes).asInstanceOf[LeaseTransaction]
      }
    }
  }

  case class MinedBlock(height: Int, reward: Long)

  case class Payout(
      id: Int,
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      assetId: Option[String],
      generatingBalance: Long,
      activeLeases: Array[Byte],
      txId: Option[String],
      txHeight: Option[Int],
      confirmed: Boolean
  ) {
    lazy val activeLeasesDecoded: LeasesSnapshot = LeasesSnapshot.fromBytes(activeLeases)
  }

  case class PayoutTransaction(id: String, payoutId: Int, transaction: MassTransferTransaction, height: Option[Int])
  case class Lease(id: String, transaction: LeaseTransaction, height: Int)
  case class PayoutLease(id: Int, leaseId: String)

  private[this] implicit val minedBlocksMeta        = schemaMeta[MinedBlock]("mined_blocks")
  private[this] implicit val payoutsMeta            = schemaMeta[Payout]("payouts")
  private[this] implicit val payoutTransactionsMeta = schemaMeta[PayoutTransaction]("payout_transactions")
  private[this] implicit val leasesMeta             = schemaMeta[Lease]("leases")
  private[this] implicit val payoutLeasesMeta       = schemaMeta[PayoutLease]("payout_leases")

  def migratePayouts(b: Blockchain): Unit = ctx.transaction {
    executeAction("""
                    |alter table payouts
                    |    alter column reward rename to amount;
                    |""".stripMargin)

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
  }
}
